// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.dashboard

import com.tracks.core.api.WeeklyVolumePoint
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Weeks with no activity are zeros on the weekly volume chart, not holes.
 *
 * The server sends no row for an empty week, and a chart that plots only the
 * rows it was given draws the duration line straight across the gap — time off
 * that reads as steady training.
 */
class FilledWeeksTest {

    // Anchored to the current week, never a fixed date.
    private val thisMonday = LocalDate.now().with(DayOfWeek.MONDAY)
    private fun weeksAgo(n: Long) = thisMonday.minusWeeks(n)
    private fun week(d: LocalDate, hours: Double = 2.0) =
        WeeklyVolumePoint(d.toString(), distanceKm = 20.0, durationHours = hours, activityCount = 1)

    @Test
    fun `an empty week in the middle is a zero`() {
        val filled = filledWeeks(listOf(week(weeksAgo(2)), week(thisMonday)), after = null, today = thisMonday)

        assertEquals(listOf(weeksAgo(2), weeksAgo(1), thisMonday).map { it.toString() }, filled.map { it.weekStart })
        assertEquals(0.0, filled[1].durationHours)
        assertEquals(0.0, filled[1].distanceKm)
        // And the zero reaches the line, rather than being read as "missing".
        assertEquals(listOf(2.0, 0.0, 2.0), volumeSeries(filled).hours)
    }

    @Test
    fun `time off at the end of the window drops to zero`() {
        val filled = filledWeeks(listOf(week(weeksAgo(3))), after = null, today = thisMonday.plusDays(3))

        assertEquals(4, filled.size)
        assertEquals(thisMonday.toString(), filled.last().weekStart)
        assertTrue(filled.drop(1).all { it.durationHours == 0.0 })
    }

    @Test
    fun `time off at the start of the window drops to zero`() {
        val after = weeksAgo(4).plusDays(2).toString()
        val filled = filledWeeks(listOf(week(weeksAgo(1))), after = after, today = thisMonday)

        assertEquals(weeksAgo(4).toString(), filled.first().weekStart)
        assertEquals(5, filled.size)
    }

    @Test
    fun `real rows are kept as they came`() {
        val rows = listOf(week(weeksAgo(1), hours = 3.5), week(thisMonday, hours = 1.25))

        assertEquals(rows, filledWeeks(rows, after = null, today = thisMonday))
    }
}
