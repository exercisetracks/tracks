// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import org.junit.Assert.assertEquals
import org.junit.Test

/** A–Z in the library reads numbers as numbers. */
class NaturalOrderTest {

    @Test
    fun `numbered names count up rather than sort as text`() {
        // Sorted as text this came out 1, 10, 11, …, 19, 2, 20 — reported 2026-10-13.
        val names = (1..21).map { "Trail $it" }.shuffled(java.util.Random(7))
        assertEquals((1..21).map { "Trail $it" }, names.sortedWith(NaturalOrder))
    }

    @Test
    fun `case is ignored and leading zeros do not change a number`() {
        assertEquals(listOf("alpha", "Beta", "loop 02", "Loop 3"), listOf("Loop 3", "Beta", "loop 02", "alpha").sortedWith(NaturalOrder))
    }
}
