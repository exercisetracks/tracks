// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.strength

import com.tracks.core.api.Exercise
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Turning a muscle-filtered selection into a workout.
 *
 * The order is the contract: what you ticked, in the order you ticked it, is
 * the order it is shown and the order it will be done. And the prescription has
 * to come from the same place whether an exercise arrived through the picker
 * inside the sheet or through the selection behind it — two paths that quietly
 * prescribed differently would be very hard to notice.
 */
class SelectionToWorkoutTest {

    private val library = listOf(
        Exercise(name = "Bench Press", defaultSets = 4, defaultReps = 6),
        Exercise(name = "Barbell Row", defaultSets = 3, defaultReps = 10),
        Exercise(name = "Own Move", isCustom = true),
    )

    @Test
    fun `the ticked order is kept`() {
        val entries = workoutEntriesFrom(listOf("Barbell Row", "Bench Press"), library)
        assertContentEquals(listOf("Barbell Row", "Bench Press"), entries.map { it.exerciseName })
        assertContentEquals(listOf(0, 1), entries.map { it.orderIndex })
    }

    @Test
    fun `the library's own defaults come across`() {
        val entry = workoutEntriesFrom(listOf("Bench Press"), library).single()
        assertEquals(4, entry.targetSets)
        assertEquals(6, entry.targetReps)
    }

    @Test
    fun `an exercise with no defaults gets sensible ones`() {
        val entry = workoutEntriesFrom(listOf("Own Move"), library).single()
        assertEquals(3, entry.targetSets)
        assertEquals(8, entry.targetReps)
    }

    @Test
    fun `a custom exercise is marked as one, so the name resolves later`() {
        assertEquals("custom", workoutEntriesFrom(listOf("Own Move"), library).single().exerciseSource)
        assertEquals("library", workoutEntriesFrom(listOf("Bench Press"), library).single().exerciseSource)
    }

    @Test
    fun `a name the library no longer has is dropped, not carried as a dead line`() {
        val entries = workoutEntriesFrom(listOf("Bench Press", "Deleted Lift"), library)
        assertContentEquals(listOf("Bench Press"), entries.map { it.exerciseName })
        // And the numbering closes up rather than leaving a hole.
        assertContentEquals(listOf(0), entries.map { it.orderIndex })
    }

    @Test
    fun `both routes into a workout prescribe the same thing`() {
        val viaSelection = workoutEntriesFrom(listOf("Bench Press"), library).single()
        val viaPicker = workoutEntry(library.first { it.name == "Bench Press" }, 0)
        assertEquals(viaPicker, viaSelection)
    }

    @Test
    fun `nothing ticked is nothing to build`() {
        assertTrue(workoutEntriesFrom(emptyList(), library).isEmpty())
    }
}
