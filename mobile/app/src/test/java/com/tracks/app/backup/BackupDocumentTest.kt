// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.backup

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupDocumentTest {
    /** A failed backup must not leave a file behind that looks like one. */
    @Test
    fun a_failed_write_discards_the_document_and_still_fails() {
        var discarded = false
        assertThrows(OutOfMemoryError::class.java) {
            writeOrDiscard({ ByteArrayOutputStream() }, { discarded = true }) { throw OutOfMemoryError() }
        }
        assertTrue(discarded)
    }

    @Test
    fun a_finished_write_keeps_the_document() {
        var discarded = false
        val out = ByteArrayOutputStream()
        writeOrDiscard({ out }, { discarded = true }) { it.write(byteArrayOf(1, 2)) }
        assertFalse(discarded)
        assertEquals(2, out.size())
    }

    /** The original error is what the person needs to see, not the cleanup's. */
    @Test
    fun a_failing_discard_does_not_hide_the_original_error() {
        val e = assertThrows(IllegalStateException::class.java) {
            writeOrDiscard({ ByteArrayOutputStream() }, { error("cannot delete") }) { error("disk full") }
        }
        assertEquals("disk full", e.message)
    }
}
