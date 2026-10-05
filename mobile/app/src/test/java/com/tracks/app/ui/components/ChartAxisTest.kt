// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The numbers down the side of a chart.
 *
 * An axis whose labels are as arbitrary as the data is worse than no axis: it
 * invites arithmetic where a glance should do. So the ticks land on round
 * numbers, and the two ways that can go wrong — a flat series with no range to
 * divide, and a baseline that starts somewhere other than zero for a counted
 * quantity — are both covered here.
 */
class ChartAxisTest {

    @Test
    fun `steps round to hundreds rather than to the data`() {
        val scale = chartScale(0.0, 858.0, anchorZero = true)
        assertEquals(0.0, scale.min, 0.001)
        assertEquals(1000.0, scale.max, 0.001)
        assertEquals(listOf(0.0, 250.0, 500.0, 750.0, 1000.0), scale.ticks)
    }

    @Test
    fun `sleep hours land on even numbers`() {
        val scale = chartScale(0.0, 7.48, anchorZero = true)
        assertEquals(listOf(0.0, 2.0, 4.0, 6.0, 8.0), scale.ticks)
    }

    @Test
    fun `a narrow reading keeps its own baseline`() {
        // Resting heart rate over a week. Anchoring this at zero would spend
        // nine-tenths of the height on numbers nobody has and flatten the only
        // part worth reading.
        val scale = chartScale(55.0, 61.0)
        assertEquals(54.0, scale.min, 0.001)
        assertEquals(62.0, scale.max, 0.001)
        assertTrue(scale.ticks.size in 3..6)
    }

    @Test
    fun `a flat series still gets a scale`() {
        // One reading, or a week of identical ones: a zero-span axis would
        // divide by zero and render nothing at all.
        val scale = chartScale(46.0, 46.0)
        assertTrue(scale.max > scale.min)
        assertTrue(scale.ticks.isNotEmpty())
        assertTrue(scale.fraction(46.0) in 0f..1f)
    }

    @Test
    fun `fractions run bottom to top`() {
        val scale = chartScale(0.0, 100.0, anchorZero = true)
        assertEquals(0f, scale.fraction(scale.min), 0.001f)
        assertEquals(1f, scale.fraction(scale.max), 0.001f)
    }

    @Test
    fun `steps are the ones people count in`() {
        assertEquals(1.0, niceStep(0.9), 0.001)
        assertEquals(2.0, niceStep(1.7), 0.001)
        assertEquals(2.5, niceStep(2.3), 0.001)
        assertEquals(5.0, niceStep(4.0), 0.001)
        assertEquals(250.0, niceStep(214.5), 0.001)
        // Degenerate input must not produce a zero step and an endless loop.
        assertEquals(1.0, niceStep(0.0), 0.001)
        assertEquals(1.0, niceStep(Double.NaN), 0.001)
    }

    @Test
    fun `whole numbers unless the ticks are closer than one`() {
        assertEquals("8", axisFormat(chartScale(0.0, 7.48, anchorZero = true))(8.0))
        assertEquals("0.5", axisFormat(chartScale(0.0, 1.0, anchorZero = true))(0.5))
    }
}
