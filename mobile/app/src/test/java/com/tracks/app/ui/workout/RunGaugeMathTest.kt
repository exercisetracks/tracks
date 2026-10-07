// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The scales the run screen's dials are drawn against. */
class RunGaugeMathTest {

    @Test
    fun `the distance dial runs to the plan, or to the next 5 km past the run`() {
        assertEquals(8000.0, distanceScaleM(8000.0, 3000.0), 0.0)
        assertEquals(5000.0, distanceScaleM(null, 0.0), 0.0)
        // A 12 km run with no plan is not pinned at the end of a 5 km arc.
        assertEquals(15000.0, distanceScaleM(null, 12000.0), 0.0)
        assertEquals(10000.0, distanceScaleM(null, 5000.0), 0.0)
    }

    @Test
    fun `a section fills by distance when it has one, by time otherwise`() {
        val rep = GuidedStep(title = "Interval", metres = 400.0, seconds = 90)
        assertEquals(0.25, stepDone(rep, coveredM = 100.0, remainingSec = 10)!!, 1e-9)
        val block = GuidedStep(title = "Warm-up", seconds = 600)
        assertEquals(0.5, stepDone(block, coveredM = 0.0, remainingSec = 300)!!, 1e-9)
        assertNull(stepDone(GuidedStep(title = "Open"), 0.0, 0))
    }

    @Test
    fun `pace is judged against the run's own average and not before there is one`() {
        assertNull(paceScale(0.0))
        val (lo, hi) = paceScale(4.0)!!
        assertEquals(3.0, lo, 1e-9)
        assertEquals(5.0, hi, 1e-9)
    }

    // ── Distance segments ────────────────────────────────────────────────────

    private val warmup = GuidedStep(title = "Warm-up", seconds = 360, kind = StepKind.Warmup, paceZone = "easy")
    private val rep = GuidedStep(title = "Interval", metres = 400.0, paceZone = "interval")
    private val rest = GuidedStep(title = "Recovery", seconds = 90, kind = StepKind.Rest)
    private val walk = GuidedStep(title = "Walk", seconds = 300, countsDistance = false)
    private val paces = mapOf("easy" to 360.0, "interval" to 240.0, "recovery" to 450.0)

    @Test
    fun `a session not yet started is laid out from its targets and zone paces`() {
        val segments = distanceSegments(listOf(warmup, rep, rest, rep), 0, emptyList(), 0.0, null, paces, 0.0)
        // 360 s at 6:00/km, then 400 m, then 90 s at the recovery zone, then 400 m.
        listOf(1000.0, 400.0, 200.0, 400.0).zip(segments).forEach { (want, segment) ->
            assertEquals(want, segment.toM - segment.fromM, 1e-6)
        }
        assertEquals(0.0, segments.first().fromM, 0.0)
        assertEquals(2000.0, segments.last().toM, 1e-9)
    }

    /** A walk is on the map but not in the distance; drawing it would leave a gap the fill never crosses. */
    @Test
    fun `a walk and an open step take no stretch of the dial`() {
        val open = GuidedStep(title = "Strides")
        val segments = distanceSegments(listOf(walk, rep, open), 0, emptyList(), 0.0, null, paces, 0.0)
        assertEquals(listOf(rep), segments.map { it.step })
    }

    @Test
    fun `sections already run keep their real length, and the current one grows past its estimate`() {
        // The warm-up was estimated at 1000 m but ran 1200; the rep is at 450 m of 400.
        val segments = distanceSegments(listOf(warmup, rep, rest), 1, listOf(0.0, 1200.0), 1650.0, null, paces, 0.0)
        assertEquals(1200.0, segments[0].toM, 1e-9)
        assertEquals(1650.0, segments[1].toM, 1e-9)
    }

    /** So the arc ends where the caption under it says "of 3.00 km". */
    @Test
    fun `the sections ahead are fitted to the plan's distance`() {
        val segments = distanceSegments(listOf(warmup, rep, rest, rep), 0, listOf(0.0), 0.0, 3000.0, paces, 0.0)
        assertEquals(3000.0, segments.last().toM, 1e-6)
        // The current section is not stretched — only the estimates ahead.
        assertEquals(1000.0, segments[0].toM, 1e-6)
    }

    @Test
    fun `with no VDOT, timed sections are taken at the runner's own average`() {
        val segments = distanceSegments(listOf(warmup), 0, emptyList(), 0.0, null, null, 3.0)
        assertEquals(1080.0, segments.single().toM, 1e-9)
    }

    // ── Pace target ──────────────────────────────────────────────────────────

    /** The phone must call "on pace" exactly where the watch's own workout does. */
    @Test
    fun `the target is the zone's pace with the watch's ten-second window`() {
        val target = paceTarget("threshold", mapOf("threshold" to 270.0), averageMps = 3.0)!!
        assertEquals(270.0, target.secPerKm, 0.0)
        assertEquals(WATCH_TOLERANCE_SEC, target.toleranceSec, 0.0)
        assertEquals("threshold", target.zone)
    }

    @Test
    fun `with no zone the run's average stands in, and before that there is nothing`() {
        val target = paceTarget(null, mapOf("easy" to 360.0), averageMps = 4.0)!!
        assertEquals(250.0, target.secPerKm, 1e-9)
        assertNull(target.zone)
        assertNull(paceTarget(null, null, averageMps = 0.0))
    }

    @Test
    fun `faster than target is positive, slower negative, symmetric in pace`() {
        val target = PaceTarget(300.0, 10.0, "easy")
        assertEquals(10.0, paceOffset(target, 1000.0 / 290.0)!!, 1e-9)
        assertEquals(-10.0, paceOffset(target, 1000.0 / 310.0)!!, 1e-9)
        assertNull(paceOffset(target, 0.0))
        assertEquals(30.0, paceReach(target), 0.0)
    }

    // ── Climb scale ──────────────────────────────────────────────────────────

    @Test
    fun `a flat start does not fill the climb dial with noise`() {
        assertEquals(20.0, climbScaleM(0.0, 0.0), 0.0)
        assertEquals(20.0, climbScaleM(4.0, 3.0), 0.0)
    }

    @Test
    fun `the larger of up and down sets one scale for both, on a round number`() {
        assertEquals(150.0, climbScaleM(100.0, 40.0), 0.0)
        assertEquals(150.0, climbScaleM(40.0, 100.0), 0.0)
        assertEquals(400.0, climbScaleM(310.0, 10.0), 0.0)
    }

    /** The scale stepping on round numbers must still never let the larger arc run off the end. */
    @Test
    fun `the larger arc always fills between half and four fifths of its side`() {
        var metres = 17.0
        while (metres < 5000) {
            val fill = metres / climbScaleM(metres, metres / 3)
            assertTrue("$metres m filled $fill", fill in 0.5..0.8 + 1e-9)
            metres *= 1.13
        }
    }
}
