// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.body

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Naming a workout after the muscles it was filtered from.
 *
 * A suggestion, not a decision — so the only rules worth holding are that it is
 * stable (the same two muscles always give the same name, whatever order the
 * set happens to iterate in) and that it stops being a list once it would stop
 * reading like a name.
 */
class SuggestedSessionNameTest {

    @Test
    fun `no filter suggests nothing rather than inventing something`() {
        assertEquals("", suggestedSessionName(emptySet()))
    }

    @Test
    fun `one muscle is its own name`() {
        assertEquals("Chest", suggestedSessionName(setOf("chest")))
    }

    @Test
    fun `two are joined`() {
        assertEquals("Chest & Triceps", suggestedSessionName(setOf("chest", "triceps")))
    }

    @Test
    fun `the order the set iterates in cannot change the name`() {
        assertEquals(
            suggestedSessionName(setOf("triceps", "chest")),
            suggestedSessionName(setOf("chest", "triceps")),
        )
    }

    @Test
    fun `three or more stops listing and starts counting`() {
        val name = suggestedSessionName(setOf("chest", "triceps", "lats", "quads"))
        assertTrue(name.endsWith("& 2 more"), name)
        assertEquals(2, name.count { it == ',' } + 1)
    }

    @Test
    fun `an unknown muscle key still yields a usable name`() {
        // muscleLabel falls back to the key itself, which is better than blank.
        assertEquals("something_new", suggestedSessionName(setOf("something_new")))
    }
}
