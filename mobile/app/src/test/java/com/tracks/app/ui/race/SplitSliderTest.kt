// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.race

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SplitSliderTest {

    /**
     * A positive spread slows the first half (the server's `_split_ramp`),
     * which is a negative split. The phone's label said the opposite.
     */
    @Test
    fun `a positive spread is described as a negative split`() {
        assertTrue(splitDescription(0.5, null).startsWith("Negative split"))
        assertTrue(splitDescription(-0.5, null).startsWith("Positive split"))
    }

    @Test
    fun `the full negative split on a 30 minute race is 72 seconds between halves`() {
        // 0.04 · 1.0 · 1800 s
        assertEquals("Negative split — second half 1:12 (8%) faster", splitDescription(1.0, 1800.0))
    }

    @Test
    fun `the slider snaps to tenths`() {
        assertEquals(0.3, snapSplit(0.27f))
        assertEquals(-1.0, snapSplit(-0.98f))
    }
}
