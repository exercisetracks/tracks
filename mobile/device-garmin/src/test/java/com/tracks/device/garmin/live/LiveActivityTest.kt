// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.garmin.live

import com.tracks.device.garmin.live.LiveActivity.Point
import com.tracks.device.garmin.live.LiveActivity.Status
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The live track the phone map draws while the watch records. Its two sources
 * overlap -- the track so far from an Explore session, and once-a-second
 * position reports -- and a wrong merge draws yesterday's ride joined to
 * today's, or loses the start of the activity.
 */
class LiveActivityTest {

    @BeforeTest
    fun setUp() {
        LiveActivity.now = { 1_000L }
        LiveActivity.reset()
    }

    @AfterTest
    fun tearDown() = LiveActivity.reset()

    private fun p(lat: Double, t: Long?) = Point(lat, 7.0, null, t)

    @Test
    fun `position reports extend the track and mark it recording`() {
        LiveActivity.onPositions(listOf(p(46.0, 10), p(46.1, 11)))
        LiveActivity.onPositions(listOf(p(46.1, 11), p(46.2, 12)))
        val track = LiveActivity.track.value
        assertEquals(listOf(46.0, 46.1, 46.2), track.points.map { it.lat }, "the repeat is dropped")
        assertTrue(track.live)
        assertTrue(LiveActivity.recording)
    }

    @Test
    fun `the track so far replaces what came before and keeps newer reports`() {
        LiveActivity.onPositions(listOf(p(46.5, 30), p(46.6, 31)))
        LiveActivity.onTrackSoFar(listOf(p(46.0, 10), p(46.5, 30)))
        assertEquals(listOf(46.0, 46.5, 46.6), LiveActivity.track.value.points.map { it.lat })
    }

    @Test
    fun `a new recording does not join onto the last one`() {
        LiveActivity.onPositions(listOf(p(46.0, 10)))
        LiveActivity.onStatus(Status.STOPPED)
        assertFalse(LiveActivity.track.value.live, "a stopped activity is not drawn as live")
        LiveActivity.onStatus(Status.RECORDING)
        assertTrue(LiveActivity.track.value.points.isEmpty())
    }

    @Test
    fun `the location dot takes the watch's position only while it is fresh`() {
        LiveActivity.onPositions(listOf(p(46.0, 10), p(46.1, 11)))
        val track = LiveActivity.track.value
        assertEquals(46.1, track.fix(1_000L)?.lat)
        assertEquals(46.1, track.fix(1_000L + LiveActivity.STALE_MS)?.lat)
        assertEquals(null, track.fix(1_001L + LiveActivity.STALE_MS), "a quiet watch hands the dot back to the phone")
        LiveActivity.onStatus(Status.STOPPED)
        assertEquals(null, LiveActivity.track.value.fix(1_000L), "a saved activity is not where the watch is now")
    }

    @Test
    fun `semicircles and the Garmin epoch convert`() {
        assertEquals(90.0, LiveActivity.degrees(1 shl 30), 1e-9)
        assertEquals(631_065_600L, LiveActivity.unixSeconds(0))
    }
}
