// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.health

import com.tracks.app.ui.dashboard.HistoryPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * A dial may not report history as though it were current.
 *
 * The complaint this exists to answer: SpO₂ had not been recorded for the best
 * part of a week — the setting off, the watch off at night, it does not matter
 * which — and the dial went on showing the last figure it had, in colour,
 * under the word "Normal", above a chart whose line ran to the right-hand edge.
 * Nothing was broken and nothing was stale in any cache. The app was answering
 * "what is my blood oxygen" with a number from six days ago.
 */
class FreshnessTest {

    private val today: LocalDate = LocalDate.of(2026, 8, 30)

    private fun trend(vararg days: Pair<String, Double>) =
        Trend(days.map { it.first }, days.map { it.second })

    @Test
    fun `last night's reading is this morning's answer`() {
        // One day and not none: the day's row does not exist until something
        // has been written to it, so at nine in the morning last night's sleep
        // and resting heart rate are all there is and all there should be.
        assertTrue(isFresh("2026-08-30", FRESH_DAYS, today))
        assertTrue(isFresh("2026-08-29", FRESH_DAYS, today))
    }

    @Test
    fun `the week before is history`() {
        assertFalse(isFresh("2026-08-28", FRESH_DAYS, today))
        assertFalse(isFresh("2026-08-24", FRESH_DAYS, today))
    }

    @Test
    fun `a reading that never expires is a standing fact`() {
        // Weight, and only weight. A body mass does not stop being true
        // because nobody stood on the scales this morning.
        assertTrue(isFresh("2026-06-01", null, today))
        // But "never expires" is not "exists".
        assertFalse(isFresh(null, null, today))
    }

    @Test
    fun `a date in the future is a clock, not staleness`() {
        // A phone whose clock is behind the server's. Blanking a dial for it
        // would be a bug reported as "my watch data disappeared".
        assertTrue(isFresh("2026-08-31", FRESH_DAYS, today))
    }

    @Test
    fun `an unreadable date is not a current reading`() {
        assertFalse(isFresh("nonsense", FRESH_DAYS, today))
    }

    // ── What the dial actually reads ─────────────────────────────────────────

    @Test
    fun `the dial shows the last reading while it is still current`() {
        val spo2 = trend("2026-08-28" to 93.0, "2026-08-29" to 94.0)
        assertEquals(94.0, spo2.current(FRESH_DAYS, today))
    }

    @Test
    fun `and shows nothing once it is not`() {
        // The same state as a metric that has never recorded anything, which
        // is right: from the point of view of "what is it now", they are.
        val spo2 = trend("2026-08-22" to 96.0, "2026-08-24" to 91.4)
        assertNull(spo2.current(FRESH_DAYS, today))
        // The history is untouched — the sheet behind the dial still has it.
        assertEquals("2026-08-24", spo2.latestDate)
        assertEquals(2, spo2.size)
    }

    @Test
    fun `a metric with no readings at all has no current value`() {
        assertNull(Trend().current(FRESH_DAYS, today))
        assertNull(Trend().latestDate)
    }

    // ── And what the chart draws between readings ────────────────────────────

    @Test
    fun `a line is drawn only across consecutive days`() {
        // A stroke joining Monday to Friday draws three days of readings that
        // were never taken, on the page somebody opened to find out whether
        // they were.
        val runs = splitOnGaps(
            listOf(
                HistoryPoint("2026-08-20", 1.0),
                HistoryPoint("2026-08-21", 2.0),
                HistoryPoint("2026-08-25", 3.0),
                HistoryPoint("2026-08-26", 4.0),
            ),
        ) { LocalDate.parse(it.date) }

        assertEquals(2, runs.size)
        assertEquals(listOf("2026-08-20", "2026-08-21"), runs[0].map { it.date })
        assertEquals(listOf("2026-08-25", "2026-08-26"), runs[1].map { it.date })
    }

    @Test
    fun `a reading with no neighbour is a run of its own`() {
        // Drawn as a dot rather than skipped: it is a real measurement and the
        // invisible endpoint of a line that is not there would lose it.
        val runs = splitOnGaps(
            listOf(HistoryPoint("2026-08-20", 1.0), HistoryPoint("2026-08-27", 2.0)),
        ) { LocalDate.parse(it.date) }

        assertEquals(listOf(1, 1), runs.map { it.size })
    }

    @Test
    fun `an unbroken series is one run`() {
        val runs = splitOnGaps(
            (20..24).map { HistoryPoint("2026-08-$it", it.toDouble()) },
        ) { LocalDate.parse(it.date) }

        assertEquals(1, runs.size)
        assertEquals(5, runs.single().size)
    }

    @Test
    fun `nothing at all splits into nothing`() {
        assertTrue(splitOnGaps(emptyList<HistoryPoint>()) { LocalDate.parse(it.date) }.isEmpty())
    }
}
