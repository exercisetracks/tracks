// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the backup notification fires: only for a phone that is the only copy
 * and overdue, and at most weekly — a nudge that repeats daily gets its
 * channel switched off, and then it protects nobody.
 */
class BackupReminderScheduleTest {

    private val day = 86_400_000L
    private val now = 1_790_000_000_000L

    @Test
    fun a_standalone_phone_never_backed_up_is_reminded() {
        assertTrue(BackupReminderWorker.shouldNotify(false, null, null, now))
    }

    @Test
    fun a_linked_phone_is_never_reminded() {
        // The server is the backup.
        assertFalse(BackupReminderWorker.shouldNotify(true, null, null, now))
    }

    @Test
    fun a_recent_backup_silences_it() {
        assertFalse(BackupReminderWorker.shouldNotify(false, now - 13 * day, null, now))
        assertTrue(BackupReminderWorker.shouldNotify(false, now - 15 * day, null, now))
    }

    @Test
    fun it_repeats_at_most_once_a_week() {
        assertFalse(BackupReminderWorker.shouldNotify(false, null, now - 6 * day, now))
        assertTrue(BackupReminderWorker.shouldNotify(false, null, now - 7 * day, now))
    }
}
