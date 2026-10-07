// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupProgressTest {
    @Test
    fun the_pill_counts_files_like_the_sync_pill() {
        assertEquals("Backing up 412 of 2,994 files", backupLabel(BackupProgress(412, 2994)))
    }

    /** Before the file list is read there is nothing to count; the ring spins rather than claiming 0%. */
    @Test
    fun before_the_list_is_known_it_says_preparing_and_has_no_fraction() {
        assertEquals("Preparing backup", backupLabel(BackupProgress(0, null)))
        assertNull(BackupProgress(0, null).fraction)
    }

    /** A phone with no files yet still writes a backup of its rows; that must not divide by zero. */
    @Test
    fun an_empty_history_has_no_fraction() {
        assertNull(BackupProgress(0, 0).fraction)
        assertEquals(0.5f, BackupProgress(1, 2).fraction!!, 0f)
    }
}
