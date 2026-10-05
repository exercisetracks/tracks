// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import com.tracks.core.api.ActivitySummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TripTotalsTest {

    private fun day(id: Int, start: String, km: Double?, ascent: Double? = null) =
        ActivitySummary(id = id, startedAt = start, distanceMeters = km?.times(1000), durationSeconds = 3600, totalAscent = ascent)

    /** A trip is read in order: its first day is when it began, whichever order the days were picked in. */
    @Test
    fun members_are_ordered_by_start_and_summed() {
        val trip = TripTotals.of(1, "Wind River", null, listOf(
            day(3, "2026-08-03T07:00:00", 18.0, 900.0),
            day(1, "2026-08-01T07:00:00", 12.0, 400.0),
        ))
        assertEquals(listOf(1, 3), trip.activities.map { it.id })
        assertEquals("2026-08-01T07:00:00", trip.startedAt)
        assertEquals("2026-08-03T07:00:00", trip.endedAt)
        assertEquals(30000.0, trip.distanceMeters)
        assertEquals(7200, trip.durationSeconds)
        assertEquals(1300.0, trip.totalAscent)
    }

    /** No member recorded ascent: the trip says nothing about ascent, rather than claiming 0 m. */
    @Test
    fun a_total_nobody_recorded_is_absent_not_zero() {
        val trip = TripTotals.of(1, "Pool week", null, listOf(day(1, "2026-08-01T07:00:00", 1.5)))
        assertNull(trip.totalAscent)
        assertNull(trip.totalCalories)
    }
}
