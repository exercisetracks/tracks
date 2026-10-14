// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusBoardTest {

    /** "Sending … to the watch…" and the watch's progress are one slot, not two pills. */
    @Test
    fun a_message_still_happening_is_its_sources_running_job() {
        val board = StatusBoard()
        board.post("Sending “Loop” to the watch…")
        assertEquals("Sending “Loop” to the watch…", board.jobs.value[StatusSource.Watch]?.label)
        assertTrue(board.notes.value.isEmpty())
    }

    @Test
    fun a_result_ends_the_job_its_source_posted() {
        val board = StatusBoard()
        board.post("Sending “Loop” to the watch…")
        board.post("“Loop” is on the watch")
        assertNull(board.jobs.value[StatusSource.Watch])
        assertEquals(1, board.notes.value.size)
    }

    /** A runner's progress clears itself when it stops; a receipt mid-sync must not hide it. */
    @Test
    fun a_result_leaves_a_runners_progress_alone() {
        val board = StatusBoard()
        board.setJob(StatusSource.Watch, StatusJob("Reading the watch", 0.4f))
        board.post("“Loop” is on the watch")
        assertEquals("Reading the watch", board.jobs.value[StatusSource.Watch]?.label)
    }

    /** "Restart your watch" no longer applies once the watch has sent its files. */
    @Test
    fun a_success_answers_the_same_sources_failure() {
        val board = StatusBoard()
        board.post("Your watch refused to send its 3 file(s). Restart the watch and sync again.")
        assertTrue(board.notes.value.single().failed)
        board.post("Watch had nothing new.")
        assertFalse(board.notes.value.any { it.failed })
    }

    @Test
    fun the_same_words_twice_are_one_note() {
        val board = StatusBoard()
        board.post("Could not save that place")
        board.post("Could not save that place")
        assertEquals(1, board.notes.value.size)
    }

    @Test
    fun failures_are_read_from_the_words() {
        assertTrue(StatusBoard.isFailure("Could not import a.gpx: bad XML"))
        assertTrue(StatusBoard.isFailure("The watch couldn’t finish syncing — it will try again"))
        assertTrue(StatusBoard.isFailure("Sync agent registration failed: timeout"))
        assertFalse(StatusBoard.isFailure("Imported 4 places"))
        assertFalse(StatusBoard.isFailure("Signed out. This phone's data was kept."))
    }
}
