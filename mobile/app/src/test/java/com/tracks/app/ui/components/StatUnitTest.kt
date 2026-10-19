// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class StatUnitTest {

    /** "42.7 km/h" wrapped in a third of the screen; the unit is drawn small, so it must split off. */
    @Test
    fun `a figure and its unit come apart`() {
        assertEquals("42.7" to "km/h", splitUnit("42.7 km/h"))
        assertEquals("5:23" to "/km", splitUnit("5:23 /km"))
        assertEquals("1,234" to "m", splitUnit("1,234 m"))
        assertEquals("65" to "%", splitUnit("65%"))
    }

    /** Words and multi-part durations are values, not a figure with a unit. */
    @Test
    fun `values that are not a figure and a unit stay whole`() {
        assertEquals("Strong" to null, splitUnit("Strong"))
        assertEquals("1h 02m" to null, splitUnit("1h 02m"))
        assertEquals("1:02:33" to null, splitUnit("1:02:33"))
    }
}
