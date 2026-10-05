// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The front row's rules, which are the only part of the overview grid that can
 * be wrong without being visible: a shuffle that quietly returns the same three
 * every time, or one that promotes three dashes over three numbers, both look
 * like a working screen.
 */
class OverviewStatsTest {

    private fun stats(populated: Int, blank: Int): List<Stat> =
        List(populated) { Stat("full$it", "$it") } + List(blank) { Stat("empty$it", null) }

    @Test
    fun `picks three`() {
        assertEquals(3, pickFront(stats(12, 0), seed = 1).size)
    }

    @Test
    fun `returns indices in canonical order`() {
        repeat(20) { seed ->
            val picked = pickFront(stats(12, 0), seed.toLong())
            assertEquals(picked.sorted(), picked, "seed $seed drew out of order")
        }
    }

    @Test
    fun `different seeds eventually pick different rows`() {
        val rows = (0L until 50L).map { pickFront(stats(12, 0), it) }.toSet()
        assertTrue(rows.size > 1, "every seed drew the same three: $rows")
    }

    @Test
    fun `the same seed always picks the same row`() {
        val all = stats(12, 0)
        assertEquals(pickFront(all, seed = 7), pickFront(all, seed = 7))
    }

    @Test
    fun `prefers stats that have a value`() {
        // Four with data, eight without: no seed should ever promote a blank.
        val all = stats(populated = 4, blank = 8)
        repeat(60) { seed ->
            val picked = pickFront(all, seed.toLong())
            assertTrue(
                picked.all { all[it].value != null },
                "seed $seed promoted a blank: ${picked.map { all[it].label }}",
            )
        }
    }

    @Test
    fun `falls back to blanks when there are not enough figures`() {
        // A window with one activity and nothing else recorded still fills the
        // row rather than drawing one number and two gaps.
        val picked = pickFront(stats(populated = 1, blank = 11), seed = 3)
        assertEquals(3, picked.size)
        assertTrue(picked.contains(0), "the one real figure was left out")
    }

    @Test
    fun `an empty window asks for nothing`() {
        assertEquals(emptyList(), pickFront(emptyList(), seed = 1))
    }

    @Test
    fun `a window with fewer stats than the row is width takes all of them`() {
        assertEquals(listOf(0, 1), pickFront(stats(2, 0), seed = 11))
    }
}
