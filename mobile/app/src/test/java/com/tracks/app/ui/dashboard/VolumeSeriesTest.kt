// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.dashboard

import com.tracks.core.api.WeeklyVolumePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the weekly volume chart plots, and on which axis.
 *
 * The rule has a corner in it. Distance is normally the columns and duration
 * the line, but a window with no distance at all — a month of strength work —
 * promotes hours to the columns, and must not then draw those same hours again
 * as a line against a second axis. That would be one series plotted twice
 * against two different scales, which looks like a real comparison and is not.
 */
class VolumeSeriesTest {

    private fun week(
        start: String,
        km: Double? = null,
        hours: Double? = null,
    ) = WeeklyVolumePoint(weekStart = start, distanceKm = km, durationHours = hours)

    @Test
    fun `distance makes the columns and duration the line`() {
        val series = volumeSeries(
            listOf(
                week("2026-08-03", km = 60.0, hours = 5.0),
                week("2026-08-10", km = 42.0, hours = 4.0),
            )
        )

        assertTrue(series.byDistance)
        assertTrue(series.withDuration)
        assertEquals(listOf(60.0, 42.0), series.columns)
        assertEquals(listOf(5.0, 4.0), series.hours)
    }

    @Test
    fun `with no distance the hours become the columns`() {
        val series = volumeSeries(
            listOf(
                week("2026-08-03", hours = 3.0),
                week("2026-08-10", hours = 4.5),
            )
        )

        assertFalse(series.byDistance)
        assertEquals(listOf(3.0, 4.5), series.columns)
        // And are not also drawn as a line: the columns already are the hours,
        // and a second axis for the same numbers is a comparison with itself.
        assertFalse(series.withDuration)
    }

    @Test
    fun `one week with distance is enough to make distance the columns`() {
        // A single ride in a month of gym work. The bars should be that ride's
        // distance rather than switching the whole chart to hours.
        val series = volumeSeries(
            listOf(
                week("2026-08-03", hours = 2.0),
                week("2026-08-10", km = 30.0, hours = 3.0),
            )
        )

        assertTrue(series.byDistance)
        assertEquals(listOf(0.0, 30.0), series.columns)
        assertTrue(series.withDuration)
    }

    @Test
    fun `missing values are zero rather than gaps`() {
        // The server omits a field it has nothing for, and a chart cannot plot
        // null — but the week still happened and must keep its column position,
        // or every later week slides one to the left.
        val series = volumeSeries(
            listOf(
                week("2026-08-03", km = 20.0),
                week("2026-08-10", hours = 2.0),
                week("2026-08-17", km = 15.0, hours = 1.5),
            )
        )

        assertEquals(listOf(20.0, 0.0, 15.0), series.columns)
        assertEquals(listOf(0.0, 2.0, 1.5), series.hours)
    }

    @Test
    fun `a window with nothing in it draws no line`() {
        val series = volumeSeries(listOf(week("2026-08-03"), week("2026-08-10")))

        assertFalse(series.byDistance)
        assertFalse(series.withDuration)
        assertEquals(listOf(0.0, 0.0), series.columns)
    }

    @Test
    fun `distance without any duration draws no line`() {
        // Possible for imported tracks that carry geometry and no elapsed time.
        val series = volumeSeries(listOf(week("2026-08-03", km = 12.0)))

        assertTrue(series.byDistance)
        assertFalse(series.withDuration)
    }
}
