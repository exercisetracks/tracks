// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {
    // A low count keeps the suite fast; the stored count is what opening uses.
    private val iterations = 10_000
    private val plain = "a fortnight of sleep".encodeToByteArray()
    private val chunk = BackupCrypto.CHUNK

    private fun seal(data: ByteArray, pass: String = "correct horse"): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCrypto.sealing(out, pass.toCharArray(), iterations).apply { write(data); finish() }
        return out.toByteArray()
    }

    private fun open(sealed: ByteArray, pass: String = "correct horse"): ByteArray =
        BackupCrypto.opening(ByteArrayInputStream(sealed), pass.toCharArray()).readBytes()

    /** Three full chunks seal as exactly three, the third flagged last; this pins that layout. */
    @Test
    fun a_whole_number_of_chunks_adds_no_empty_one() {
        assertTrue(seal(bytes(3 * chunk)).size == 35 + 3 * (chunk + 16))
    }

    private fun bytes(n: Int) = ByteArray(n) { (it * 31).toByte() }

    @Test
    fun a_sealed_backup_opens_with_its_passphrase() {
        assertArrayEquals(plain, open(seal(plain)))
    }

    /** Sizes either side of a chunk boundary, where the last-chunk flag is easiest to get wrong. */
    @Test
    fun payloads_of_every_awkward_size_round_trip() {
        for (n in listOf(0, 1, chunk - 1, chunk, chunk + 1, 2 * chunk, 2 * chunk + 7)) {
            assertArrayEquals("size $n", bytes(n), open(seal(bytes(n))))
        }
    }

    /**
     * Sealed chunks leave before the payload ends. Sealing it whole is what
     * ran a phone with a 134 MB history out of memory.
     */
    @Test
    fun sealing_writes_as_it_goes() {
        var written = 0L
        val counting = object : OutputStream() {
            override fun write(b: Int) { written++ }
            override fun write(b: ByteArray, off: Int, len: Int) { written += len }
        }
        val sealing = BackupCrypto.sealing(counting, "pw".toCharArray(), iterations)
        repeat(3) { sealing.write(bytes(chunk)) }
        assertTrue("only $written bytes out before finish", written >= 2L * chunk)
    }

    @Test
    fun a_wrong_passphrase_is_refused_not_decoded_into_garbage() {
        val sealed = seal(plain)
        assertThrows(BackupCrypto.WrongPassphraseOrDamaged::class.java) { open(sealed, "battery staple") }
    }

    /** A cut-off download must fail loudly, never restore part of a history. */
    @Test
    fun a_truncated_file_is_refused() {
        val sealed = seal(plain)
        assertThrows(BackupCrypto.WrongPassphraseOrDamaged::class.java) { open(sealed.copyOf(sealed.size - 5)) }
    }

    /** Cut exactly between chunks, every chunk left is intact; only the last-chunk flag catches it. */
    @Test
    fun a_file_cut_at_a_chunk_boundary_is_refused() {
        val sealed = seal(bytes(3 * chunk))
        val header = sealed.size - 3 * (chunk + 16)
        val firstTwo = sealed.copyOf(header + 2 * (chunk + 16))
        assertThrows(BackupCrypto.WrongPassphraseOrDamaged::class.java) { open(firstTwo) }
    }

    /** A write that failed partway never called finish, so its file must not open as whole. */
    @Test
    fun an_unfinished_backup_does_not_open() {
        val out = ByteArrayOutputStream()
        BackupCrypto.sealing(out, "pw".toCharArray(), iterations).apply { write(bytes(2 * chunk + 5)) }
        assertThrows(BackupCrypto.WrongPassphraseOrDamaged::class.java) { open(out.toByteArray(), "pw") }
    }

    @Test
    fun chunks_cannot_be_reordered() {
        val sealed = seal(bytes(3 * chunk))
        val header = sealed.size - 3 * (chunk + 16)
        val c = chunk + 16
        val swapped = sealed.copyOf()
        sealed.copyInto(swapped, header, header + c, header + 2 * c)
        sealed.copyInto(swapped, header + c, header, header + c)
        assertThrows(BackupCrypto.WrongPassphraseOrDamaged::class.java) { open(swapped) }
    }

    /** The header is authenticated: a lowered iteration count cannot slip through. */
    @Test
    fun a_tampered_header_is_refused() {
        val sealed = seal(plain)
        sealed[20] = (sealed[20].toInt() xor 1).toByte() // inside the salt
        assertThrows(BackupCrypto.WrongPassphraseOrDamaged::class.java) { open(sealed) }
    }

    /** Backups made before the chunked format must still restore. */
    @Test
    fun a_backup_in_the_first_format_still_opens() {
        val old = BackupCrypto.sealV1(plain, "correct horse".toCharArray(), iterations)
        assertArrayEquals(plain, open(old))
        assertThrows(BackupCrypto.WrongPassphraseOrDamaged::class.java) { open(old, "battery staple") }
    }

    @Test
    fun a_file_that_is_not_a_backup_says_so() {
        assertThrows(BackupCrypto.NotABackup::class.java) {
            open("PK\u0003\u0004 a zip".encodeToByteArray(), "pw")
        }
    }
}
