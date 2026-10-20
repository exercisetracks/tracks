// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import com.tracks.core.api.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The activity map's colour pills, against the web's `ActivityRoute.jsx`.
 *
 * The two apps are meant to colour the same ride the same way; these pin the
 * parts that decide that — which pills appear, the percentile scaling, and the
 * gradient clamp.
 */
class RouteColouringTest {

    private fun pt(i: Int, hr: Int? = null, speed: Double? = null, alt: Double? = null, grit: Double? = null) =
        TrackPoint(lat = 51.0 + i * 0.0005, lng = -114.0, heartRate = hr, speed = speed, altitude = alt, grit = grit)

    @Test
    fun `only the channels a recording has get a pill`() {
        val road = (0..10).map { pt(it, hr = 140, speed = 8.0, alt = 1000.0) }
        assertEquals(
            listOf(RouteColour.HeartRate, RouteColour.Speed, RouteColour.Gradient),
            availableRouteColours(road),
        )
        // Grit is a mountain-bike channel: it appears only when it was recorded.
        val mtb = (0..10).map { pt(it, hr = 140, grit = 2.0) }
        assertTrue(RouteColour.Grit in availableRouteColours(mtb))
    }

    @Test
    fun `a route with no gps has no pills`() {
        val treadmill = (0..10).map { TrackPoint(heartRate = 150, speed = 3.0) }
        assertEquals(emptyList<RouteColour>(), availableRouteColours(treadmill))
        assertEquals(emptyList<ColouredSegment>(), colourRoute(treadmill, RouteColour.HeartRate))
    }

    @Test
    fun `one spike does not flatten the rest of the ramp`() {
        // Ninety-nine points ramping 100..198 bpm and one dropout-glitch at 250.
        // Scaled to the extremes, the real range would crowd the bottom; scaled
        // to the 5th-95th percentiles, its top still reaches the hot end.
        val track = (0 until 100).map { pt(it, hr = if (it == 50) 250 else 100 + it) }
        val segments = colourRoute(track, RouteColour.HeartRate)
        assertEquals(track.size - 1, segments.size)
        assertEquals(RouteColour.HeartRate.ramp.first(), segments.first().argb)
        assertEquals(RouteColour.HeartRate.ramp.last(), segments.last().argb)
    }

    @Test
    fun `a flat route is the flat colour, not a stretched ramp`() {
        val flat = (0..10).map { pt(it, alt = 1000.0 + (it % 2) * 0.2) }
        val white = 0xFFFFFFFF.toInt()
        colourRoute(flat, RouteColour.Gradient).forEach {
            // Within a shade of the ramp's white middle.
            assertTrue((it.argb and 0xFF) > 0xF0 && ((it.argb shr 16) and 0xFF) > 0xE0)
        }
        assertEquals(white, colourAt(RouteColour.Gradient.stops, 0.5))
    }
}
