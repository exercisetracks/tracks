// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.run

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The distance a run reports, which is the number people trust least and check
 * most.
 *
 * Every case here is a way a phone's GPS makes a run look longer than it was.
 */
class RunTrackTest {

    /** Metres north of a starting point, as a fix. */
    private fun northOf(metres: Double, elapsedMs: Long, accuracy: Double = 5.0) = RunFix(
        timestampMs = 1_700_000_000_000L + elapsedMs,
        elapsedMs = elapsedMs,
        lat = START_LAT + metres / 111_320.0,
        lng = START_LNG,
        accuracyM = accuracy,
    )

    @Test
    fun `measures a straight run`() {
        val track = RunTrack()
        for (second in 0..100) {
            track.add(northOf(second * 3.0, second * 1000L))
        }
        // 100 steps of 3 m. Haversine on a 300 m north-south line is exact to
        // well under a metre, so this is a tight bound on purpose.
        assertTrue(abs(track.distanceM - 300.0) < 1.0, "was ${track.distanceM}")
    }

    @Test
    fun `a walk that does not count leaves the distance and splits alone`() {
        // The report: a guided run's 5-minute walking warm-up was in its total.
        val track = RunTrack()
        track.counting = false
        for (second in 0..100) track.add(northOf(second * 5.0, second * 1000L))   // 500 m walk
        track.counting = true
        for (second in 101..400) track.add(northOf(500 + (second - 100) * 5.0, second * 1000L))  // 1500 m run
        assertTrue(abs(track.distanceM - 1500.0) < 2.0, "was ${track.distanceM}")
        // The first kilometre split lands 1000 m into the run, not 500 m.
        val (km, at) = track.splits().single()
        assertEquals(1, km)
        assertTrue(abs(at - 300_000L) < 1500, "split at $at")
        val cumulative = track.cumulativeM()
        assertEquals(0.0, cumulative[100])
        assertTrue(abs(cumulative.last() - track.distanceM) < 1e-6)
    }

    @Test
    fun `drops fixes the phone admits it is unsure about`() {
        val track = RunTrack()
        track.add(northOf(0.0, 0))
        assertFalse(
            track.add(northOf(50.0, 1000, accuracy = RunTrack.MAX_ACCURACY_M + 1)),
            "a fix worse than the accuracy limit must not be kept",
        )
        assertEquals(0.0, track.distanceM)
    }

    @Test
    fun `standing still adds no distance`() {
        val track = RunTrack()
        track.add(northOf(0.0, 0))
        // Two metres of wander a second, with good accuracy — a phone under a
        // tree. Unfiltered this is 120 m a minute of imaginary running.
        for (second in 1..60) {
            val jitter = if (second % 2 == 0) 2.0 else 0.0
            track.add(northOf(jitter, second * 1000L))
        }
        assertEquals(0.0, track.distanceM)
    }

    @Test
    fun `altitude dither is not climbing`() {
        val track = RunTrack()
        for (second in 0..60) {
            val wobble = if (second % 2 == 0) 1.5 else 0.0
            track.add(northOf(second * 5.0, second * 1000L).copy(altitudeM = 100.0 + wobble))
        }
        assertEquals(0.0, track.ascentM, "a metre and a half of dither is not a hill")
    }

    @Test
    fun `a real climb counts once`() {
        val track = RunTrack()
        for (second in 0..20) {
            track.add(northOf(second * 5.0, second * 1000L).copy(altitudeM = 100.0 + second * 2.0))
        }
        assertTrue(abs(track.ascentM - 40.0) < 1.0, "was ${track.ascentM}")
    }

    /** The run screen's climb dial sets the two side by side, so they must be filtered alike. */
    @Test
    fun `a hill up and back down counts both ways, equally`() {
        val track = RunTrack()
        for (second in 0..40) {
            val height = if (second <= 20) second * 2.0 else (40 - second) * 2.0
            track.add(northOf(second * 5.0, second * 1000L).copy(altitudeM = 100.0 + height))
        }
        assertTrue(abs(track.ascentM - 40.0) < 1.0, "up was ${track.ascentM}")
        assertTrue(abs(track.descentM - 40.0) < 1.0, "down was ${track.descentM}")
    }

    @Test
    fun `altitude dither is not descending either`() {
        val track = RunTrack()
        for (second in 0..60) {
            val wobble = if (second % 2 == 0) 1.5 else 0.0
            track.add(northOf(second * 5.0, second * 1000L).copy(altitudeM = 100.0 - wobble))
        }
        assertEquals(0.0, track.descentM)
    }

    @Test
    fun `splits land on the kilometre, not on the fix after it`() {
        val track = RunTrack()
        // 4 m/s, sampled every second: a fix lands 4 m past each kilometre.
        for (second in 0..600) {
            track.add(northOf(second * 4.0, second * 1000L))
        }
        val splits = track.splits()
        assertEquals(2, splits.size)
        assertEquals(1, splits[0].first)
        // 1000 m at 4 m/s is 250 s. Reporting the fix *after* the line would
        // give 251 s, and the error would compound down the list.
        assertTrue(abs(splits[0].second - 250_000L) < 1500L, "was ${splits[0].second}")
        assertTrue(abs(splits[1].second - 500_000L) < 1500L, "was ${splits[1].second}")
    }

    @Test
    fun `current speed follows the recent window, not the whole run`() {
        val track = RunTrack()
        // A slow first minute, then a fast last twenty seconds.
        var metres = 0.0
        for (second in 0..60) {
            track.add(northOf(metres, second * 1000L))
            metres += 2.0
        }
        for (second in 61..85) {
            track.add(northOf(metres, second * 1000L))
            metres += 5.0
        }
        val speed = track.currentSpeedMps()
        assertTrue(speed > 4.0, "recent pace should dominate, was $speed")
    }

    @Test
    fun `an empty track has no speed and no distance`() {
        val track = RunTrack()
        assertEquals(0.0, track.currentSpeedMps())
        assertEquals(0.0, track.distanceM)
        assertTrue(track.splits().isEmpty())
    }

    private companion object {
        const val START_LAT = 39.7392
        const val START_LNG = -149.9903
    }
}
