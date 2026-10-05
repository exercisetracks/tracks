// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A goal beaten should look like a goal beaten.
 *
 * The dial used to clamp, which drew 24,000 steps and 15,000 steps as the same
 * full ring on the screen whose one job is telling them apart. Past the top the
 * arc laps onto a second ring outside the first, and these are the boundaries
 * that decides — including the one that matters most, which is that landing
 * exactly on the maximum is a full first ring and *not* an empty second one.
 */
class GaugeOverflowTest {

    @Test
    fun `inside the scale there is no second ring`() {
        assertNull(overflowOf(8_000.0, 0.0, 15_000.0))
    }

    /** The join. A hair either side of it must not flicker a whole ring on. */
    @Test
    fun `exactly at the maximum stays on the first ring`() {
        assertNull(overflowOf(15_000.0, 0.0, 15_000.0))
    }

    @Test
    fun `past the maximum carries the excess`() {
        assertEquals(1_000.0, overflowOf(16_000.0, 0.0, 15_000.0)!!, 1e-9)
    }

    @Test
    fun `the excess is measured from the top, not from zero`() {
        // A scale that does not start at zero — resting heart rate's does not.
        assertEquals(5.0, overflowOf(105.0, 35.0, 100.0)!!, 1e-9)
    }

    @Test
    fun `a second full lap saturates rather than starting a third`() {
        val span = 15_000.0
        assertEquals(span, overflowOf(45_000.0, 0.0, span)!!, 1e-9)
        assertEquals(span, overflowOf(30_000.0, 0.0, span)!!, 1e-9)
    }

    @Test
    fun `no reading, no ring`() {
        assertNull(overflowOf(null, 0.0, 15_000.0))
    }

    /** A degenerate scale would otherwise divide by a zero span. */
    @Test
    fun `a scale with no extent cannot overflow`() {
        assertNull(overflowOf(50.0, 10.0, 10.0))
    }
}
