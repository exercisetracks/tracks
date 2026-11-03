// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.race

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The same cases as the web's CourseProfile.test.js: the two are one algorithm. */
class CourseProfileTest {
    private val path = listOf(0.0 to 0.0, 0.0 to 0.01, 0.0 to 0.02)
    private val ele = listOf(100.0, 200.0, 100.0)
    private fun lap(n: Int, pace: Double, watts: Int?, hr: Int?) = RaceLap(
        lap = n, distanceM = 1000, targetSecPerKm = pace, targetPace = "", gradient = 0.0, gradeMultiplier = 1.0,
        gradeAdjSec = pace, gradeAdjPace = "", cumulativeKm = n.toDouble(), hrCeiling = hr, targetWatts = watts,
    )
    private val laps = listOf(lap(1, 300.0, 250, 160), lap(2, 360.0, 200, 170))

    /**
     * Laps cover 2 km and the course ~2.22 km: each target holds for its lap
     * and changes where the lap does. A line drifting between laps read as a
     * target changing along a climb.
     */
    @Test
    fun `each lap's target steps at its boundary on the course, whatever its own length`() {
        val pts = CourseProfile.points(path, ele, laps, CourseProfile.Metric.PACE)!!
        val half = pts.last().m / 2
        assertTrue(pts.filter { it.m < half }.all { it.target == 300.0 })
        assertTrue(pts.filter { it.m >= half }.all { it.target == 360.0 })
        assertEquals(2223.9, pts.last().m, 0.5)
    }

    @Test
    fun `a boundary between course points gets its own point, so the step is sharp`() {
        val pts = CourseProfile.points(listOf(0.0 to 0.0, 0.0 to 0.03), listOf(100.0, 130.0), laps, CourseProfile.Metric.PACE)!!
        assertEquals(3, pts.size)
        assertEquals(360.0, pts[1].target)
        assertEquals(115.0, pts[1].ele!!, 1e-6)
    }

    /** A GPX with no heights has no hills to show, but its targets still do. */
    @Test
    fun `a course without elevation is drawn from its laps`() {
        val pts = CourseProfile.points(path.take(2), listOf(null, null), laps, CourseProfile.Metric.PACE)!!
        assertTrue(pts.all { it.ele == null })
        assertEquals(300.0, pts.first().target)
    }

    /** The graph replaced the lap table, so it shows whether or not a course is loaded. */
    @Test
    fun `with no course the laps alone are drawn, HR ceiling and ground included`() {
        val pts = CourseProfile.points(emptyList(), emptyList(), laps, CourseProfile.Metric.PACE)!!
        assertTrue(pts.size > 50 && pts.all { it.ele == null && it.lat == null })
        assertEquals(300.0, pts.first().target)
        assertEquals(360.0, pts.last().target)
        assertEquals(2000.0, pts.last().m)
        assertEquals(160.0, pts.first().hr)
        assertEquals(2, pts.last().lap?.lap)
    }

    @Test
    fun `nothing is drawn with neither a course nor a lap`() {
        assertNull(CourseProfile.points(emptyList(), emptyList(), emptyList(), CourseProfile.Metric.PACE))
    }

    @Test
    fun `each sport draws its own coaching number`() {
        assertEquals(CourseProfile.Metric.PACE, CourseProfile.metric("running", laps))
        assertEquals(CourseProfile.Metric.POWER, CourseProfile.metric("cycling", laps))
        assertEquals(CourseProfile.Metric.POWER, CourseProfile.metric("triathlon", laps))
        assertEquals(CourseProfile.Metric.HR, CourseProfile.metric("mountain_biking", laps))
        assertNull(CourseProfile.metric("swimming", laps))
    }

    /** A cycling plan without an FTP has laps with no watts. */
    @Test
    fun `a sport missing its own number falls back to pace`() {
        assertEquals(CourseProfile.Metric.PACE, CourseProfile.metric("cycling", listOf(lap(1, 120.0, null, null))))
    }
}
