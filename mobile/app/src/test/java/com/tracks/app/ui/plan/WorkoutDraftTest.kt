// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.plan

import com.tracks.core.api.PlannedWorkout
import com.tracks.core.api.PlannedWorkoutUpdate
import com.tracks.core.api.WorkoutStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the editor actually writes.
 *
 * The rules worth pinning are the two that are invisible when wrong: a patch
 * that names fields nobody changed, which would stamp their edit times and beat
 * a real change made elsewhere; and a step kind offered here that the FIT
 * encoder cannot build, which would be a step the user wrote and the watch
 * never sees.
 */
class WorkoutDraftTest {

    private val existing = PlannedWorkout(
        id = 12,
        scheduledDate = "2026-09-02",
        sport = "running",
        workoutType = "interval",
        title = "Hill repeats",
        description = "Six by two minutes",
        durationMinutes = 50,
        steps = listOf(WorkoutStep(type = "run", durationMin = 30.0)),
    )

    @Test
    fun `a loaded workout round-trips into a draft and back to no change`() {
        val draft = WorkoutDraft.of(existing)
        assertTrue(draft.unchanged(existing))
        assertEquals(PlannedWorkoutUpdate(), draft.toUpdate(existing))
    }

    @Test
    fun `only the fields that changed are sent`() {
        val draft = WorkoutDraft.of(existing).copy(title = "Hill repeats v2")
        val update = draft.toUpdate(existing)
        assertEquals("Hill repeats v2", update.title)
        // Everything else must stay null, or the server stamps those fields as
        // edited now and a genuine change made elsewhere loses to this one.
        assertNull(update.scheduledDate)
        assertNull(update.description)
        assertNull(update.durationMinutes)
        assertNull(update.sport)
        assertNull(update.workoutType)
        assertNull(update.steps)
    }

    @Test
    fun `a moved workout sends its new date and nothing else`() {
        val update = WorkoutDraft.of(existing).copy(date = "2026-09-04").toUpdate(existing)
        assertEquals("2026-09-04", update.scheduledDate)
        assertNull(update.title)
    }

    @Test
    fun `edited steps are sent whole`() {
        val steps = listOf(
            WorkoutStep(type = "warmup", durationMin = 15.0),
            WorkoutStep(type = "interval_set", reps = 6, distanceM = 400.0, restSec = 60),
        )
        val update = WorkoutDraft.of(existing).copy(steps = steps).toUpdate(existing)
        assertEquals(steps, update.steps)
    }

    @Test
    fun `a workout needs a name and a day before it can be saved`() {
        assertFalse(WorkoutDraft.blank("2026-09-02").canSave)
        assertTrue(WorkoutDraft.blank("2026-09-02").copy(title = "Easy hour").canSave)
        assertFalse(WorkoutDraft.blank("2026-09-02").copy(title = "   ").canSave)
    }

    @Test
    fun `a new workout becomes a create carrying its structure`() {
        val draft = WorkoutDraft.blank("2026-09-02").copy(
            title = "  Threshold  ",
            sport = "running",
            workoutType = "tempo",
            durationMinutes = "45",
            steps = listOf(WorkoutStep(type = "run", durationMin = 45.0, pace = "threshold")),
        )
        val create = draft.toCreate()
        assertEquals("Threshold", create.title)
        assertEquals("2026-09-02", create.scheduledDate)
        assertEquals("tempo", create.workoutType)
        assertEquals(45, create.durationMinutes)
        assertEquals(1, create.steps.size)
    }

    @Test
    fun `a blank duration is absent rather than zero`() {
        assertNull(WorkoutDraft.blank("2026-09-02").copy(title = "x").toCreate().durationMinutes)
    }

    @Test
    fun `the workout type decides which steps may be written`() {
        assertEquals(StepFamily.ENDURANCE, WorkoutDraft.blank("2026-09-02").stepFamily)
        assertEquals(
            StepFamily.STRENGTH,
            WorkoutDraft.blank("2026-09-02").copy(workoutType = "strength").stepFamily,
        )
        assertEquals(
            StepFamily.MOBILITY,
            WorkoutDraft.blank("2026-09-02").copy(workoutType = "mobility").stepFamily,
        )
        assertEquals(
            StepFamily.NONE,
            WorkoutDraft.blank("2026-09-02").copy(workoutType = "rest").stepFamily,
        )
    }

    @Test
    fun `every offered step kind is one the encoder can actually build`() {
        // This list exists to be the intersection of "somebody can write it"
        // and "the watch will be told about it". A kind added here that the FIT
        // encoder skips is a step the user wrote and the watch never sees —
        // silently, which is the failure mode this project keeps meeting.
        val encoderKnows = setOf(
            "warmup", "cooldown", "walk", "run", "fartlek", "interval_set",
            "effort_set", "ride", "swim", "activity",
            "strength_exercise", "mobility_exercise",
        )
        StepKind.entries.forEach {
            assertTrue(it.type in encoderKnows, "${it.type} is offered but never encoded")
        }
    }

    @Test
    fun `every step kind has defaults its own editor can show`() {
        StepKind.entries.forEach { kind ->
            val step = newStep(kind)
            assertEquals(kind.type, step.type)
            assertEquals(kind, StepKind.of(step), "a new ${kind.label} must be recognised back")
        }
    }

    @Test
    fun `each family offers at least one kind, and rest offers none`() {
        assertTrue(StepKind.forFamily(StepFamily.ENDURANCE).isNotEmpty())
        assertTrue(StepKind.forFamily(StepFamily.STRENGTH).isNotEmpty())
        assertTrue(StepKind.forFamily(StepFamily.MOBILITY).isNotEmpty())
        assertEquals(emptyList(), StepKind.forFamily(StepFamily.NONE))
    }
}
