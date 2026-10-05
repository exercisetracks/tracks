// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
