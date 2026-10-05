// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.flexibility

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Turning a muscle-filtered selection into a flow.
 *
 * Simpler than the strength side on purpose: a flow carries only its
 * deviations, so a stretch straight out of the library arrives with none — held
 * for whatever the library prescribes until somebody says otherwise. Filling in
 * durations here would turn every inherited hold into a chosen one and quietly
 * detach the flow from the library it came from.
 */
class SelectionToFlowTest {

    @Test
    fun `the ticked order is kept`() {
        val stretches = flowStretchesFrom(listOf("Low Lunge", "Pigeon", "Child's Pose"))
        assertContentEquals(
            listOf("Low Lunge", "Pigeon", "Child's Pose"),
            stretches.map { it.exerciseName },
        )
        assertContentEquals(listOf(0, 1, 2), stretches.map { it.orderIndex })
    }

    @Test
    fun `nothing is prescribed that the library has not been asked about`() {
        val stretch = flowStretchesFrom(listOf("Low Lunge")).single()
        assertNull(stretch.durationSeconds)
        assertNull(stretch.sets)
        assertNull(stretch.restSeconds)
        assertNull(stretch.coachingNote)
    }

    @Test
    fun `nothing ticked is nothing to build`() {
        assertTrue(flowStretchesFrom(emptyList()).isEmpty())
    }
}
