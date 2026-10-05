// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Nights sit where they happened.
 *
 * The bug this exists to prevent is a quiet one: the sleep chart used to place
 * one bar per *reading*, evenly spaced, so six nights spread over three weeks
 * were drawn as six consecutive bars. That is a chart claiming somebody slept
 * every night, on the page they opened to find out whether they did.
 */
class DayAxisTest {

    private val today: LocalDate = LocalDate.now()

    @Test
    fun `a gap in the middle leaves a gap on the axis`() {
        // Four days apart at the ends, with the middle night on day one.
        val axis = DayAxis.of(
            listOf(
                today.minusDays(4).toString(),
                today.minusDays(3).toString(),
                today.toString(),
            ),
        )
        assertNotNull(axis)
        val first = axis!!.fraction(today.minusDays(4))!!
        val second = axis.fraction(today.minusDays(3))!!
        val last = axis.fraction(today)!!

        assertEquals(0f, first, 0.001f)
        assertEquals(1f, last, 0.001f)
        // A quarter of the way along, not a third — which is where an
        // index-spaced chart would have put it.
        assertEquals(0.25f, second, 0.001f)
    }

    @Test
    fun `the right edge is today even when the last reading is older`() {
        val axis = DayAxis.of(
            listOf(today.minusDays(10).toString(), today.minusDays(5).toString()),
        )!!
        // Half the axis is the run of readings; the rest is the silence since.
        assertEquals(0.5f, axis.fraction(today.minusDays(5))!!, 0.001f)
        assertEquals(1f, axis.fraction(today)!!, 0.001f)
    }

    @Test
    fun `a single reading still has a width`() {
        // Zero span would divide by zero; the axis floors at one day.
        val axis = DayAxis.of(listOf(today.toString()))!!
        assertEquals(1, axis.days)
        assertEquals(0f, axis.fraction(today)!!, 0.001f)
    }

    @Test
    fun `a date outside the span has no place on it`() {
        val axis = DayAxis.of(listOf(today.minusDays(3).toString()))!!
        assertNull(axis.fraction(today.minusDays(4)))
        assertNull(axis.fraction(today.plusDays(1)))
    }

    @Test
    fun `unreadable dates are dropped rather than landing at zero`() {
        assertNull(DayAxis.parseDay("not a date"))
        assertNull(DayAxis.of(listOf("nonsense", "also nonsense")))
        // One good date among bad ones still gives an axis.
        val axis = DayAxis.of(listOf("nonsense", today.minusDays(2).toString()))
        assertNotNull(axis)
        assertTrue(axis!!.days >= 1)
    }

    // ── Bar geometry ─────────────────────────────────────────────────────────

    /** A phone-width card, in pixels at 1x. */
    private val width = 328f
    private val minBar = 2f
    private val maxBar = 24f

    @Test
    fun `bars never overlap their neighbours`() {
        // The bug this pins down: bar *width* used to come from the number of
        // readings while bar *position* came from the dates. Eight nights over
        // ten weeks were sized as if they were eight evenly spaced bars — 22
        // points each — and drawn four points apart, so every run of
        // consecutive nights merged into one blob and the chart showed three
        // shapes instead of eight.
        val nights = listOf(
            today.minusDays(72), today.minusDays(71),
            today.minusDays(56), today.minusDays(55), today.minusDays(54),
            today.minusDays(2), today.minusDays(1), today,
        )
        val axis = DayAxis.of(nights.map { it.toString() })!!
        val bar = axis.barWidth(width, minBar, maxBar)

        nights.zipWithNext { a, b ->
            val gap = axis.centre(axis.fraction(b)!!, width, bar) -
                axis.centre(axis.fraction(a)!!, width, bar)
            // JUnit4 takes the message first.
            assertTrue(
                "$a and $b are ${gap}px apart with ${bar}px bars — they overlap",
                gap >= bar,
            )
        }
    }

    @Test
    fun `a bar is never wider than the day it stands for`() {
        val axis = DayAxis.of(listOf(today.minusDays(30).toString(), today.toString()))!!
        val slot = width / axis.days
        assertTrue(axis.barWidth(width, minBar, maxBar) <= slot)
    }

    @Test
    fun `a short window still gets a readable bar`() {
        // Three nights over two days: the slot is enormous and the cap is what
        // stops one night filling a third of the chart.
        val axis = DayAxis.of(listOf(today.minusDays(2).toString(), today.toString()))!!
        assertEquals(maxBar, axis.barWidth(width, minBar, maxBar), 0.01f)
    }

    @Test
    fun `end bars sit inside the canvas`() {
        val axis = DayAxis.of(listOf(today.minusDays(10).toString(), today.toString()))!!
        val bar = axis.barWidth(width, minBar, maxBar)
        val first = axis.centre(0f, width, bar)
        val last = axis.centre(1f, width, bar)
        assertTrue(first - bar / 2 >= 0f)
        assertTrue(last + bar / 2 <= width)
    }

    // ── The window, not the data ─────────────────────────────────────────────

    @Test
    fun `three nights in a month sit against the right edge`() {
        // The complaint this fixes: on the 30-day window, three nights of data
        // were drawn filling the whole month. The axis ran from the earliest
        // *reading*, so the gap the window exists to show was scaled out of the
        // picture entirely.
        val nights = listOf(today.minusDays(2), today.minusDays(1), today)
        val axis = DayAxis.of(nights.map { it.toString() }, from = today.minusDays(30))!!

        assertEquals(30, axis.days)
        // All three in the last tenth of the chart, not spread across it.
        nights.forEach { night ->
            assertTrue(
                "$night should sit near the right edge",
                axis.fraction(night)!! > 0.9f,
            )
        }
    }

    @Test
    fun `without a window the data is the window`() {
        // The lifetime view has no start date to honour.
        val axis = DayAxis.of(listOf(today.minusDays(5).toString(), today.toString()))!!
        assertEquals(5, axis.days)
        assertEquals(0f, axis.fraction(today.minusDays(5))!!, 0.001f)
    }

    @Test
    fun `a window later than the readings does not clip them off`() {
        // Should not arise — the view model filters days to the window — but a
        // reading pushed off the left edge would simply vanish, so the axis
        // widens rather than dropping it.
        val axis = DayAxis.of(listOf(today.minusDays(40).toString()), from = today.minusDays(7))!!
        assertEquals(40, axis.days)
        assertNotNull(axis.fraction(today.minusDays(40)))
    }
}
