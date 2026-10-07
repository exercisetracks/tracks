// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.race

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaceGuideTest {
    private val wall = List(20) { Segment(100.0, 0.0) } + List(3) { Segment(100.0, 0.09) } + List(27) { Segment(100.0, 0.0) }

    @Test
    fun the_hill_is_announced_as_a_hill_and_its_top_as_the_top() {
        val (laps, _) = RacePredictor.lapPaces(1500.0, 5000.0, segments = wall, terrain = true)
        val legs = RaceGuide.legs(laps, imperial = false)
        val hill = legs.indexOfFirst { it.kind == "steep_up" }
        assertTrue(legs[hill].spoken.startsWith("Steep climb ahead"), legs[hill].spoken)
        assertTrue(legs[hill + 1].spoken.startsWith("Top of the climb"), legs[hill + 1].spoken)
        assertTrue(legs.first().spoken.startsWith("Race start"))
        assertEquals(5000.0, legs.last().courseEndM, 0.5)
    }

    @Test
    fun progress_follows_the_course_and_never_runs_backwards() {
        // A straight 1 km line north, scaled to a 1 km plan.
        val path = (0..10).map { 47.0 + it * 0.0009 to 8.0 }
        val p = RaceGuide.CourseProgress(path, planDistanceM = 1000.0)
        val half = p.update(47.0045, 8.0001)!!
        assertEquals(500.0, half, 15.0)
        assertEquals(half, p.update(47.0040, 8.0)!!, 0.001)   // a fix that jitters back
        assertNull(p.update(47.0045, 8.01))                     // ~750 m off the line
    }
}
