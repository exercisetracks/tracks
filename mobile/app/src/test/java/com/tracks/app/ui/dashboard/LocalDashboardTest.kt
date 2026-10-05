// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.dashboard

import com.tracks.core.api.ActivitySummary
import com.tracks.core.time.ZoneOffsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The dashboard the phone works out for itself.
 *
 * This is the code that runs when there is no server, which is the case it
 * exists for and the one nobody notices being wrong: a summary computed from
 * the wrong window still looks like a summary. So the assertions here are about
 * boundaries — which activities fall inside a window, which day a late-evening
 * ride belongs to, which Monday a week starts on — rather than about arithmetic
 * that would fail loudly.
 */
class LocalDashboardTest {

    /** An account in UTC, where the local day is the stored one. */
    private val utc: (Long) -> Int = { 0 }

    private fun activity(
        id: Int,
        startedAt: String,
        sport: String? = "running",
        distanceMeters: Double? = 10_000.0,
        durationSeconds: Int? = 3_600,
        avgHeartRate: Int? = null,
        totalAscent: Double? = null,
        totalCalories: Int? = null,
    ) = ActivitySummary(
        id = id,
        sport = sport,
        startedAt = startedAt,
        distanceMeters = distanceMeters,
        durationSeconds = durationSeconds,
        avgHeartRate = avgHeartRate,
        totalAscent = totalAscent,
        totalCalories = totalCalories,
    )

    private val log = listOf(
        activity(1, "2026-08-18T02:14:52Z", sport = "rock_climbing", distanceMeters = null,
            durationSeconds = 2_281, avgHeartRate = 115, totalAscent = 33.0, totalCalories = 271),
        activity(2, "2026-08-17T09:00:00Z", distanceMeters = 12_000.0, durationSeconds = 3_600,
            avgHeartRate = 145),
        activity(3, "2026-08-17T18:30:00Z", sport = "cycling", distanceMeters = 40_000.0,
            durationSeconds = 7_200, avgHeartRate = 125, totalAscent = 600.0),
        activity(4, "2026-07-01T09:00:00Z", distanceMeters = 8_000.0, durationSeconds = 2_700),
    )

    @Test
    fun `the window keeps only what falls inside it`() {
        // The window starts at the local midnight of its first day, as the
        // server's does, so the boundary day is included.
        val month = computeDashboard(log, after = "2026-08-01", sport = null, offsetSecondsAt = utc)
        assertEquals(3, month.summary.activityCount)

        val lifetime = computeDashboard(log, after = null, sport = null, offsetSecondsAt = utc)
        assertEquals(4, lifetime.summary.activityCount)
    }

    @Test
    fun `average distance ignores the activities that went nowhere`() {
        // The climb has no distance at all. Counting it as zero would drag the
        // average of the window down by a third, and the server does not — this
        // is its rule reproduced, so the number does not change when the
        // network does.
        val window = computeDashboard(log, after = "2026-08-01", sport = null, offsetSecondsAt = utc).summary

        assertEquals(52.0, window.totalDistanceKm!!, 0.001)
        assertEquals(26.0, window.avgDistanceKm!!, 0.001)
    }

    @Test
    fun `totals that nothing recorded are absent rather than zero`() {
        val quiet = computeDashboard(
            listOf(activity(9, "2026-08-18T06:00:00Z", distanceMeters = null, durationSeconds = null)),
            after = null,
            sport = null,
            offsetSecondsAt = utc,
        )
        assertNull(quiet.summary.totalDistanceKm)
        assertNull(quiet.summary.totalDurationHours)
        assertNull(quiet.extras.totalAscentM)
    }

    @Test
    fun `the calendar counts days and not activities`() {
        val calendar = computeDashboard(log, after = "2026-08-01", sport = null, offsetSecondsAt = utc).calendar

        assertEquals(listOf("2026-08-17", "2026-08-18"), calendar.map { it.date })
        assertEquals(2, calendar.first { it.date == "2026-08-17" }.count)
        assertEquals(2, computeDashboard(log, "2026-08-01", null, utc).extras.activeDays)
    }

    @Test
    fun `weeks start on a Monday`() {
        // 2026-08-17 is a Monday and 2026-08-18 the Tuesday after it, so all
        // three August activities fall in one week — the same bucketing the
        // server does, which is what keeps the volume chart from disagreeing
        // with itself when the network comes back.
        val volume = computeDashboard(log, after = "2026-08-01", sport = null, offsetSecondsAt = utc).weeklyVolume

        assertEquals(1, volume.size)
        assertEquals("2026-08-17", volume.first().weekStart)
        assertEquals(3, volume.first().activityCount)
        assertEquals(52.0, volume.first().distanceKm!!, 0.001)
    }

    @Test
    fun `the sport filter narrows the calendar without narrowing the totals`() {
        // The cross-filter applies to the calendar and the volume chart only,
        // exactly as it does server-side — the headline totals above them stay
        // the window's, or picking a sport would silently redefine every figure
        // on the screen.
        val cycling = computeDashboard(log, after = "2026-08-01", sport = "cycling", offsetSecondsAt = utc)

        assertEquals(1, cycling.calendar.sumOf { it.count })
        assertEquals(1, cycling.weeklyVolume.single().activityCount)
        assertEquals(3, cycling.summary.activityCount)
    }

    @Test
    fun `the breakdown leads with the sport that was done most`() {
        val bySport = computeDashboard(log, after = null, sport = null, offsetSecondsAt = utc).bySport

        assertEquals("running", bySport.first().sport)
        assertEquals(2, bySport.first().activityCount)
        assertEquals(setOf("running", "cycling", "rock_climbing"), bySport.map { it.sport }.toSet())
    }

    @Test
    fun `the extras are the figures the server does not send`() {
        val extras = computeDashboard(log, after = "2026-08-01", sport = null, offsetSecondsAt = utc).extras

        assertEquals(633.0, extras.totalAscentM!!, 0.001)
        assertEquals(271, extras.totalCalories)
        assertEquals(40_000.0, extras.longestDistanceM!!, 0.001)
        assertEquals(7_200, extras.longestDurationSec)
        assertEquals(128, extras.avgHeartRate)
    }

    @Test
    fun `an activity with no start date cannot land in any window`() {
        // A provisional row imported from a watch with a broken clock. It must
        // not be counted into a window it may not belong to, and it must not
        // throw on the way past.
        val undated = log + activity(99, startedAt = "").copy(startedAt = null)

        assertEquals(4, computeDashboard(undated, after = null, sport = null, offsetSecondsAt = utc).summary.activityCount)
    }

    @Test
    fun `an evening run in California is on its own day, not the UTC day after`() {
        // 18:04 PDT is 01:04 UTC the next day. Read as the stored UTC day, the
        // Wednesday run was Thursday's square, a window starting Thursday held
        // it, and the Sunday run counted in the next week's volume.
        val la = ZoneOffsets.of("America/Los_Angeles")
        val runs = listOf(
            activity(1, "2026-10-01 01:04:12+00:00"),   // Wed 30 Sep, 18:04 PDT
            activity(2, "2026-10-05T01:04:00Z"),        // Sun 4 Oct, 18:04 PDT
        )
        val dash = computeDashboard(runs, after = "2026-09-30", sport = null, offsetSecondsAt = la)

        assertEquals(listOf("2026-09-30", "2026-10-04"), dash.calendar.map { it.date })
        assertEquals(2, dash.extras.activeDays)
        assertEquals(listOf("2026-09-28"), dash.weeklyVolume.map { it.weekStart })
        assertEquals(0, computeDashboard(runs, after = "2026-10-05", sport = null, offsetSecondsAt = la)
            .summary.activityCount)
    }
}
