// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ComplementTest {

    private fun hex(c: Color) = "%06X".format(c.toArgb() and 0xFFFFFF)
    private fun color(hex: String) = Color(0xFF000000 or hex.toLong(16))

    /**
     * The same vectors as frontend/src/lib/complement.test.js, so the phone
     * and the web draw the duration line in one colour for one accent.
     */
    @Test
    fun `the complement matches the web for every accent in both themes`() {
        val vectors = listOf(
            Triple("059669", false, "CF0745"), Triple("34D399", true, "E2799F"),
            Triple("2563EB", false, "C48C12"), Triple("60A5FA", true, "FAB561"),
            Triple("7C3AED", false, "83C412"), Triple("A78BFA", true, "D2F863"),
            Triple("E11D48", false, "18BE99"), Triple("FB7185", true, "60FBE4"),
            Triple("D97706", false, "0664D0"), Triple("FBBF24", true, "5F8BFC"),
        )
        for ((accent, dark, expected) in vectors) {
            assertEquals(expected, hex(complementOf(color(accent), dark)), "accent $accent dark=$dark")
        }
    }

    /** The bug this exists for: bars and line were both the accent. */
    @Test
    fun `every accent's complement differs from the accent`() {
        for (a in Accent.entries) for (dark in listOf(false, true)) {
            assertNotEquals(hex(a.primary(dark)), hex(complementOf(a.primary(dark), dark)), "${a.name} dark=$dark")
        }
    }
}
