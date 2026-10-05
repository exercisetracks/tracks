// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupCryptoTest {
    // A low count keeps the suite fast; the stored count is what open() uses.
    private val iterations = 10_000
    private val plain = "a fortnight of sleep".encodeToByteArray()

    @Test
    fun a_sealed_backup_opens_with_its_passphrase() {
        val sealed = BackupCrypto.seal(plain, "correct horse".toCharArray(), iterations)
        assertArrayEquals(plain, BackupCrypto.open(sealed, "correct horse".toCharArray()))
    }

    @Test
    fun a_wrong_passphrase_is_refused_not_decoded_into_garbage() {
        val sealed = BackupCrypto.seal(plain, "correct horse".toCharArray(), iterations)
        assertThrows(BackupCrypto.WrongPassphraseOrDamaged::class.java) {
            BackupCrypto.open(sealed, "battery staple".toCharArray())
        }
    }

    /** A cut-off download must fail loudly, never restore part of a history. */
    @Test
    fun a_truncated_file_is_refused() {
        val sealed = BackupCrypto.seal(plain, "pw".toCharArray(), iterations)
        assertThrows(BackupCrypto.WrongPassphraseOrDamaged::class.java) {
            BackupCrypto.open(sealed.copyOf(sealed.size - 5), "pw".toCharArray())
        }
    }

    /** The header is authenticated: a lowered iteration count cannot slip through. */
    @Test
    fun a_tampered_header_is_refused() {
        val sealed = BackupCrypto.seal(plain, "pw".toCharArray(), iterations)
        sealed[20] = (sealed[20].toInt() xor 1).toByte() // inside the salt
        assertThrows(BackupCrypto.WrongPassphraseOrDamaged::class.java) {
            BackupCrypto.open(sealed, "pw".toCharArray())
        }
    }

    @Test
    fun a_file_that_is_not_a_backup_says_so() {
        assertThrows(BackupCrypto.NotABackup::class.java) {
            BackupCrypto.open("PK\u0003\u0004 a zip".encodeToByteArray(), "pw".toCharArray())
        }
    }
}
