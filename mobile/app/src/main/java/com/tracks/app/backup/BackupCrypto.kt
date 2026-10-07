// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.backup

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sealing a backup under a passphrase.
 *
 * ## Why a passphrase and not the Keystore
 *
 * The point of a backup is surviving the loss of this phone, and a Keystore
 * key dies with it. So the key is derived from something the person keeps in
 * their head (or a password manager) instead, and the file is only as strong
 * as that passphrase — which is why the UI asks for one of real length and
 * says so.
 *
 * ## The choices, and what was rejected
 *
 * - **PBKDF2-HMAC-SHA256, 600 000 iterations** (OWASP's 2023 figure). Argon2id
 *   would resist GPU guessing better, but Android ships no implementation, and
 *   vendoring a native one for a file written a few times a month is a poor
 *   trade against an algorithm every JVM verifies identically. The iteration
 *   count is stored in the header, so it can rise later without breaking old
 *   files.
 * - **AES-256-GCM** over the whole payload, with the header as associated
 *   data: a changed iteration count or salt fails authentication exactly like
 *   a changed byte of ciphertext, rather than silently deriving a different
 *   key.
 * - A fresh random salt and nonce per file, so two backups under the same
 *   passphrase share nothing.
 *
 * A wrong passphrase and a tampered file are indistinguishable by design —
 * GCM cannot tell them apart, and saying which would help a guesser.
 *
 * ## Sealed in chunks
 *
 * The first format sealed the payload as one GCM message, which needs the
 * whole backup in memory at once: Android's provider (Conscrypt, over
 * BoringSSL's one-shot AEAD) buffers every byte handed to `update` until
 * `doFinal`, so even a streamed cipher held it all. At 134 MB of history that
 * ran out of heap. So the payload is now cut into 1 MiB chunks, each its own
 * GCM message — the STREAM construction (Hoang, Reyhanitabar, Rogaway, Vizár
 * 2015), which is also what Tink's streaming AEAD does. Each chunk's nonce is
 * a random per-file prefix, the chunk's index, and a flag set only on the
 * last, so chunks cannot be reordered, dropped, or the file cut off at a
 * chunk boundary without the tag failing. Every chunk carries the header as
 * associated data, as before.
 *
 * Files in the old one-message format still open — see [opening].
 *
 * ## Layout
 *
 * `TRKBAK02` · iterations (u32) · salt (16) · nonce prefix (7) · chunks, each
 * ciphertext+tag, every one but the last of exactly [CHUNK] plaintext bytes.
 *
 * The first format, still read: `TRKBAK01` · iterations (u32) · salt (16) ·
 * nonce (12) · ciphertext+tag.
 */
object BackupCrypto {
    private val MAGIC = "TRKBAK02".encodeToByteArray()
    private val MAGIC_V1 = "TRKBAK01".encodeToByteArray()
    const val ITERATIONS = 600_000
    private const val SALT_BYTES = 16
    private const val PREFIX_BYTES = 7
    private const val HEADER_BYTES = 8 + 4 + SALT_BYTES + PREFIX_BYTES
    private const val NONCE_BYTES = 12
    private const val HEADER_BYTES_V1 = 8 + 4 + SALT_BYTES + NONCE_BYTES
    private const val TAG_BYTES = 16
    internal const val CHUNK = 1 shl 20

    class WrongPassphraseOrDamaged : Exception("The passphrase is wrong, or the file is damaged.")
    class NotABackup : Exception("This file is not a Tracks backup.")

    /**
     * Seal into [out]: the header is written at once, each chunk as it fills.
     * Call [SealingStream.finish] when the payload is complete — closing
     * without it leaves no last chunk, so a backup whose writing failed
     * halfway can never be opened as if it were whole.
     */
    fun sealing(out: OutputStream, passphrase: CharArray, iterations: Int = ITERATIONS): SealingStream {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val prefix = ByteArray(PREFIX_BYTES).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_BYTES)
            .put(MAGIC).putInt(iterations).put(salt).put(prefix).array()
        out.write(header)
        return SealingStream(out, key(passphrase, salt, iterations), header, prefix)
    }

    /**
     * The payload of a sealed backup, decrypted as it is read. A wrong
     * passphrase or a damaged chunk throws [WrongPassphraseOrDamaged] from
     * the read that reaches it; a file cut short throws at its end.
     */
    fun opening(input: InputStream, passphrase: CharArray): InputStream {
        val header = ByteArray(HEADER_BYTES)
        if (input.readFully(header, 0, MAGIC.size) < MAGIC.size) throw NotABackup()
        val magic = header.copyOf(MAGIC.size)
        // Backups written before the chunked format were small enough to
        // write in one piece — that was the limit — so opening one whole is fine.
        if (magic.contentEquals(MAGIC_V1)) return ByteArrayInputStream(openV1(magic + input.readBytes(), passphrase))
        if (!magic.contentEquals(MAGIC)) throw NotABackup()
        if (input.readFully(header, MAGIC.size, HEADER_BYTES - MAGIC.size) < HEADER_BYTES - MAGIC.size) {
            throw WrongPassphraseOrDamaged()
        }
        val buf = ByteBuffer.wrap(header, MAGIC.size, HEADER_BYTES - MAGIC.size)
        val iterations = buf.int
        checkIterations(iterations)
        val salt = ByteArray(SALT_BYTES).also(buf::get)
        val prefix = ByteArray(PREFIX_BYTES).also(buf::get)
        return OpeningStream(input, key(passphrase, salt, iterations), header, prefix)
    }

    class SealingStream internal constructor(
        private val out: OutputStream,
        private val key: SecretKeySpec,
        private val header: ByteArray,
        private val prefix: ByteArray,
    ) : OutputStream() {
        private val buf = ByteArray(CHUNK)
        private var filled = 0
        private var index = 0
        private var finished = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!finished) { "already finished" }
            var from = off
            var left = len
            while (left > 0) {
                // A full chunk goes out only once more data arrives, so the
                // one that turns out to be last is still here to be flagged.
                if (filled == CHUNK) emit(last = false)
                val take = minOf(left, CHUNK - filled)
                b.copyInto(buf, filled, from, from + take)
                filled += take
                from += take
                left -= take
            }
        }

        /** Seal the last chunk. Without this the file does not open. */
        fun finish() {
            if (finished) return
            emit(last = true)
            finished = true
            out.flush()
        }

        override fun flush() = out.flush()

        override fun close() = out.close()

        private fun emit(last: Boolean) {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, nonce(prefix, index++, last)))
            cipher.updateAAD(header)
            out.write(cipher.doFinal(buf, 0, filled))
            filled = 0
        }
    }

    private class OpeningStream(
        input: InputStream,
        private val key: SecretKeySpec,
        private val header: ByteArray,
        private val prefix: ByteArray,
    ) : InputStream() {
        private val input = PushbackInputStream(input, 1)
        private val sealed = ByteArray(CHUNK + TAG_BYTES)
        private var plain = ByteArray(0)
        private var pos = 0
        private var index = 0
        private var done = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos == plain.size) {
                if (done) return -1
                next()
            }
            val n = minOf(len, plain.size - pos)
            plain.copyInto(b, off, pos, pos + n)
            pos += n
            return n
        }

        override fun close() = input.close()

        private fun next() {
            val n = input.readFully(sealed, 0, sealed.size)
            // Only the last chunk can be short; a full one is last only if
            // nothing follows it. Either way the flag is in the nonce, so a
            // guess the writer did not make fails the tag.
            val last = n < sealed.size || input.read().also { if (it >= 0) input.unread(it) } < 0
            val cipher = Cipher.getInstance(TRANSFORM)
            try {
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, nonce(prefix, index++, last)))
                cipher.updateAAD(header)
                plain = cipher.doFinal(sealed, 0, n)
            } catch (e: GeneralSecurityException) {
                throw WrongPassphraseOrDamaged()
            }
            pos = 0
            done = last
        }
    }

    /** As many bytes as are there, up to [len]. `readNBytes` would do, but it is API 33. */
    private fun InputStream.readFully(b: ByteArray, off: Int, len: Int): Int {
        var got = 0
        while (got < len) {
            val r = read(b, off + got, len - got)
            if (r < 0) break
            got += r
        }
        return got
    }

    private fun nonce(prefix: ByteArray, index: Int, last: Boolean): ByteArray {
        // 2^31 chunks of 1 MiB is two petabytes; reaching it would mean a bug.
        check(index >= 0) { "backup too large" }
        return ByteBuffer.allocate(NONCE_BYTES).put(prefix).putInt(index).put(if (last) 1 else 0).array()
    }

    /** A header claiming absurd work is a damaged (or hostile) file; refuse it before spending minutes deriving a key that cannot be right. */
    private fun checkIterations(iterations: Int) {
        if (iterations !in 10_000..10_000_000) throw WrongPassphraseOrDamaged()
    }

    /**
     * The first format's sealer, which held everything in memory. Nothing
     * writes it any more; it is kept so the tests can prove such files open.
     */
    internal fun sealV1(plain: ByteArray, passphrase: CharArray, iterations: Int = ITERATIONS): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_BYTES_V1)
            .put(MAGIC_V1).putInt(iterations).put(salt).put(nonce).array()
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(TAG_BYTES * 8, nonce))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plain)
    }

    private fun openV1(sealed: ByteArray, passphrase: CharArray): ByteArray {
        if (sealed.size < HEADER_BYTES_V1) throw WrongPassphraseOrDamaged()
        val buf = ByteBuffer.wrap(sealed, MAGIC_V1.size, HEADER_BYTES_V1 - MAGIC_V1.size)
        val iterations = buf.int
        checkIterations(iterations)
        val salt = ByteArray(SALT_BYTES).also(buf::get)
        val nonce = ByteArray(NONCE_BYTES).also(buf::get)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(TAG_BYTES * 8, nonce))
        cipher.updateAAD(sealed, 0, HEADER_BYTES_V1)
        return try {
            cipher.doFinal(sealed, HEADER_BYTES_V1, sealed.size - HEADER_BYTES_V1)
        } catch (e: GeneralSecurityException) {
            throw WrongPassphraseOrDamaged()
        }
    }

    private fun key(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(raw, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private const val TRANSFORM = "AES/GCM/NoPadding"
}
