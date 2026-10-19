// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pill pops up and goes; the drawer keeps the record. A long sync used to
 * keep the pill over every screen for as long as it ran.
 */
class StatusPopTest {

    private fun note(failed: Boolean) = StatusNote(1, StatusSource.Watch, "x", failed)

    @Test
    fun a_failure_stays_up_longer_than_news() {
        assertEquals(POP_MS, popDurationMs(note(failed = false)))
        assertEquals(FAILURE_POP_MS, popDurationMs(note(failed = true)))
        assertTrue("both are only a few seconds", FAILURE_POP_MS <= 10_000)
    }

    @Test
    fun the_drawer_says_how_long_ago_in_words() {
        val now = 10_000_000L
        assertEquals("just now", ago(now, now - 10_000))
        assertEquals("4 min ago", ago(now, now - 4 * 60_000))
        assertEquals("2 h ago", ago(now, now - 2 * 3_600_000))
    }

    @Test
    fun opening_the_drawer_sees_every_failure_so_far() {
        val board = StatusBoard()
        board.post("Could not reach the server")
        val newest = board.notes.value.first().id
        assertTrue(board.seenUpTo.value < newest)
        board.markSeen()
        assertEquals(newest, board.seenUpTo.value)
    }
}
