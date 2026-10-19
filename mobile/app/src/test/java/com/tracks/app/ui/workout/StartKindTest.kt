// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StartKindTest {

    /** Flexibility is yoga: a yoga activity counts as flexibility, and yoga is not offered apart. */
    @Test
    fun `yoga counts as flexibility and is not a kind of its own`() {
        assertEquals(StartKind.Flexibility, StartKind.of("yoga", null))
        assertEquals(StartKind.Flexibility, StartKind.of("training", "yoga"))
        assertFalse(StartKind.entries.any { it.label == "Yoga" })
    }

    @Test
    fun `history maps onto the kinds that can be started`() {
        assertEquals(StartKind.Mtb, StartKind.of("cycling", "mountain"))
        assertEquals(StartKind.Ride, StartKind.of("cycling", "road"))
        assertEquals(StartKind.Strength, StartKind.of("training", "strength_training"))
    }

    /** The most-done kind first, so it is the one preselected. */
    @Test
    fun `frequent puts the most done first`() {
        val counts = mapOf(StartKind.Ride to 3, StartKind.Run to 12, StartKind.Strength to 5, StartKind.Hike to 0)
        assertEquals(listOf(StartKind.Run, StartKind.Strength, StartKind.Ride), StartKind.frequent(counts))
    }
}
