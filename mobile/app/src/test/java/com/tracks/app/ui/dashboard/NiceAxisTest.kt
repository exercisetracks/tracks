// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

/** The dashboard's axes end close to the data, at round numbers. */
class NiceAxisTest {

    @Test
    fun `a peak of 41 tops out at 50, not 60`() {
        // Aiming at exactly five gridlines asked for a step of 10.25, which
        // rounded up to 20 and left a third of the fitness chart empty.
        val axis = niceAxis(0.0, 41.0)

        assertEquals(0.0, axis.min, 0.0)
        assertEquals(50.0, axis.max, 0.0)
    }

    @Test
    fun `the form landmarks fit without a spare band either side`() {
        val axis = niceAxis(-30.0, 25.0)

        assertEquals(-30.0, axis.min, 0.0)
        assertEquals(30.0, axis.max, 0.0)
    }

    @Test
    fun `a dual axis still gets the gridline count it asks for`() {
        val axis = niceAxis(0.0, 8.0, atLeastTicks = 7)

        assertEquals(7, axis.ticks)
    }
}
