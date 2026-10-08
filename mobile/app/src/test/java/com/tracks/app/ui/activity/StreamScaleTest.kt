// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The y axis of an activity's stream charts: centred on the data, zoomed in on
 * it, but not so far that a gentle effort looks like a hard one.
 */
class StreamScaleTest {

    @Test
    fun `a flat ride is not drawn against a zero floor`() {
        // 108–116 m used to sit on a 0–120 axis as a flat line along the top.
        val scale = streamScale(listOf(108.0, 112.0, 116.0), minSpan = 30.0, floor = null)

        assertTrue("floor ${scale.min} is far below the data", scale.min >= 80.0)
        assertTrue(scale.max <= 140.0)
    }

    @Test
    fun `a gentle effort does not fill the chart`() {
        // Eight metres of riverbank must not look like a mountain pass.
        val scale = streamScale(listOf(108.0, 116.0), minSpan = 30.0, floor = null)

        assertTrue((116.0 - 108.0) / scale.span <= 0.5)
    }

    @Test
    fun `the data sits in the middle of the frame`() {
        val scale = streamScale(listOf(122.0, 133.0), minSpan = 30.0)
        val below = 122.0 - scale.min
        val above = scale.max - 133.0

        assertTrue("below $below above $above", below > 0 && above > 0)
        assertTrue(kotlin.math.abs(below - above) <= scale.span / 4)
    }

    @Test
    fun `a big range fills most of the chart`() {
        val scale = streamScale(listOf(480.0, 1160.0), minSpan = 30.0, floor = null)

        assertTrue((1160.0 - 480.0) / scale.span >= 0.6)
    }

    @Test
    fun `a channel that cannot go negative is not labelled below zero`() {
        // A walk at 0.5–1.5 m/s centred in a 2 m/s window would start at −0.0;
        // a slower one would start below it.
        val scale = streamScale(listOf(0.2, 0.6), minSpan = 2.0)

        assertEquals(0.0, scale.min, 1e-9)
        assertTrue(scale.max >= 0.6)
    }

    @Test
    fun `elevation may go below sea level`() {
        val scale = streamScale(listOf(-20.0, -5.0), minSpan = 30.0, floor = null)

        assertTrue(scale.min < -20.0)
    }

    @Test
    fun `every gridline is labelled at a round number`() {
        val scale = streamScale(listOf(122.0, 133.0), minSpan = 30.0)

        assertTrue(scale.ticks.size >= 3)
        scale.ticks.forEach { assertEquals(it, Math.round(it).toDouble(), 1e-9) }
        assertEquals(scale.min, scale.ticks.first(), 1e-9)
        assertEquals(scale.max, scale.ticks.last(), 1e-9)
    }

    @Test
    fun `a rolling run's elevation does not fill the chart`() {
        // 25 m of relief used to span the whole chart under the 30 m minimum,
        // so every outing read as a mountain stage. The web pads 100 m either
        // side, and the phone now does the same.
        val scale = streamScale(listOf(200.0, 225.0), minSpan = 0.0, floor = null, margin = ELEVATION_MARGIN_M)

        assertTrue("min ${scale.min}", scale.min <= 100.0)
        assertTrue("max ${scale.max}", scale.max >= 325.0)
        assertTrue((225.0 - 200.0) / scale.span <= 0.15)
    }

    @Test
    fun `a big climb keeps the same margin rather than a share of the height`() {
        val scale = streamScale(listOf(480.0, 1160.0), minSpan = 0.0, floor = null, margin = ELEVATION_MARGIN_M)

        assertTrue("min ${scale.min}", scale.min <= 380.0)
        assertTrue("max ${scale.max}", scale.max >= 1260.0)
        // Rounded out to round gridlines (200–1400 here), which costs some
        // height, but the climb still takes most of the chart.
        assertTrue((1160.0 - 480.0) / scale.span > 0.5)
    }
}
