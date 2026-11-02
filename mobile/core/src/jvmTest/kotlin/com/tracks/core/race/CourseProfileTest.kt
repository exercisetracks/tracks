// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.race

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The same cases as the web's CourseProfile.test.js: the two are one algorithm. */
class CourseProfileTest {
    private val path = listOf(0.0 to 0.0, 0.0 to 0.01, 0.0 to 0.02)
    private val ele = listOf(100.0, 200.0, 100.0)
    private fun lap(n: Int, pace: Double, watts: Int?, hr: Int?) = RaceLap(
        lap = n, distanceM = 1000, targetSecPerKm = pace, targetPace = "", gradient = 0.0, gradeMultiplier = 1.0,
        gradeAdjSec = pace, gradeAdjPace = "", cumulativeKm = n.toDouble(), hrCeiling = hr, targetWatts = watts,
    )
    private val laps = listOf(lap(1, 300.0, 250, 160), lap(2, 360.0, 200, 170))

    /** Laps cover 2 km and the course ~2.22 km: each lap's target sits at its middle on the course. */
    @Test
    fun `the plan's laps are laid along the course, whatever its own length`() {
        val pts = CourseProfile.points(path, ele, laps, CourseProfile.Metric.PACE)!!
        assertEquals(listOf(100.0, 200.0, 100.0), pts.map { it.ele })
        assertEquals(300.0, pts[0].target)
        assertEquals(330.0, pts[1].target!!, 1e-6)
        assertEquals(360.0, pts[2].target)
        assertEquals(2223.9, pts[2].m, 0.5)
    }

    @Test
    fun `a course without elevation has no plot`() {
        assertNull(CourseProfile.points(path.take(2), listOf(null, null), laps, CourseProfile.Metric.PACE))
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
