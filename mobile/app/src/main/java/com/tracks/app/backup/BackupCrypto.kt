// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.backup

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
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
 * ## Layout
 *
 * `TRKBAK01` · iterations (u32) · salt (16) · nonce (12) · ciphertext+tag
 */
object BackupCrypto {
    private val MAGIC = "TRKBAK01".encodeToByteArray()
    const val ITERATIONS = 600_000
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val HEADER_BYTES = 8 + 4 + SALT_BYTES + NONCE_BYTES

    class WrongPassphraseOrDamaged : Exception("The passphrase is wrong, or the file is damaged.")
    class NotABackup : Exception("This file is not a Tracks backup.")

    fun seal(plain: ByteArray, passphrase: CharArray, iterations: Int = ITERATIONS): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_BYTES)
            .put(MAGIC).putInt(iterations).put(salt).put(nonce).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(128, nonce))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plain)
    }

    fun open(sealed: ByteArray, passphrase: CharArray): ByteArray {
        if (sealed.size < HEADER_BYTES || !sealed.copyOf(MAGIC.size).contentEquals(MAGIC)) throw NotABackup()
        val buf = ByteBuffer.wrap(sealed, MAGIC.size, HEADER_BYTES - MAGIC.size)
        val iterations = buf.int
        // A header claiming absurd work is a damaged (or hostile) file; refuse
        // it before spending minutes deriving a key that cannot be right.
        if (iterations !in 10_000..10_000_000) throw WrongPassphraseOrDamaged()
        val salt = ByteArray(SALT_BYTES).also(buf::get)
        val nonce = ByteArray(NONCE_BYTES).also(buf::get)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(128, nonce))
        cipher.updateAAD(sealed, 0, HEADER_BYTES)
        return try {
            cipher.doFinal(sealed, HEADER_BYTES, sealed.size - HEADER_BYTES)
        } catch (e: AEADBadTagException) {
            throw WrongPassphraseOrDamaged()
        } catch (e: javax.crypto.BadPaddingException) {
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
}
