// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import com.tracks.app.ui.map.AreaOutline.box
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

/** The same cases as the web's AreaOutline.test.js, so the two draw alike. */
class AreaOutlineTest {
    private fun length(p: List<AreaPolygon>) =
        AreaOutline.union(p).sumOf { (a, b) -> abs(b.first - a.first) + abs(b.second - a.second) }

    @Test fun one_box_is_its_own_outline() = assertEquals(6.0, length(listOf(box(0.0, 0.0, 2.0, 1.0))), 1e-9)
    @Test fun the_lines_inside_an_overlap_are_gone() =
        assertEquals(12.0, length(listOf(box(0.0, 0.0, 2.0, 2.0), box(1.0, 1.0, 3.0, 3.0))), 1e-9)
    @Test fun a_box_inside_another_adds_no_line() =
        assertEquals(16.0, length(listOf(box(0.0, 0.0, 4.0, 4.0), box(1.0, 1.0, 2.0, 2.0))), 1e-9)
    @Test fun boxes_that_only_touch_share_no_seam() =
        assertEquals(6.0, length(listOf(box(0.0, 0.0, 1.0, 1.0), box(1.0, 0.0, 2.0, 1.0))), 1e-9)
    @Test fun separate_boxes_keep_separate_outlines() =
        assertEquals(8.0, length(listOf(box(0.0, 0.0, 1.0, 1.0), box(3.0, 0.0, 4.0, 1.0))), 1e-9)
}
