// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The map used to open on the defaults every time, losing every switch. */
class LayerChoicesTest {

    @Test
    fun `nothing saved opens on the defaults`() {
        assertEquals(LAYER_GROUPS.filterNot { it.onByDefault }.map { it.id }.toSet(), LayerChoices.hidden(emptySet()))
    }

    @Test
    fun `a switch flipped either way is kept`() {
        val off = LAYER_GROUPS.first { it.onByDefault }
        val on = LAYER_GROUPS.first { !it.onByDefault }
        var saved = LayerChoices.with(emptySet(), off.id, false)
        saved = LayerChoices.with(saved, on.id, true)
        val hidden = LayerChoices.hidden(saved)
        assertTrue(off.id in hidden)
        assertFalse(on.id in hidden)
        // Flipped back: one entry per group, the latest.
        saved = LayerChoices.with(saved, off.id, true)
        assertFalse(off.id in LayerChoices.hidden(saved))
        assertEquals(2, saved.size)
    }

    @Test
    fun `a retired group or a garbled entry changes nothing`() {
        assertEquals(LayerChoices.hidden(emptySet()), LayerChoices.hidden(setOf("retired=1", "garbled")))
    }
}
