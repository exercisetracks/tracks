// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.strength

import com.tracks.core.api.Exercise
import com.tracks.core.api.StepGroup
import com.tracks.core.api.UserWorkoutExercise
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionBlocksTest {

    private val library = listOf("Squat", "Row", "Press").associateWith { Exercise(name = it) }
    private val circuit = StepGroup("g", "repeat", rounds = 3, restSeconds = 75)

    private fun plan(vararg rows: UserWorkoutExercise) =
        SessionLogic.plan(rows.toList(), library) { null }

    private fun done(s: SessionState, i: Int, set: Int) = s.copy(
        exercises = s.exercises.mapIndexed { j, e ->
            if (j != i) e else e.copy(sets = e.sets.mapIndexed { k, x -> if (k == set) x.copy(done = true) else x })
        },
    )

    /** Inside a circuit the rounds are the sets; a member's own target is ignored. */
    @Test
    fun `a circuit member gets one set per round`() {
        val s = plan(
            UserWorkoutExercise(exerciseName = "Squat", targetSets = 5, groupUid = "g", groupKind = "repeat", groupRounds = 3, groupRestSeconds = 75),
        )
        assertEquals(3, s.single().sets.size)
    }

    /** A rest block is the rest after the exercise before it, not a row of its own. */
    @Test
    fun `a rest block becomes the rest after the exercise before it`() {
        val s = plan(
            UserWorkoutExercise(exerciseName = "Squat"),
            UserWorkoutExercise(itemKind = "rest", restSeconds = 120),
            UserWorkoutExercise(exerciseName = "Row"),
        )
        assertEquals(listOf(120, 0), s.map { it.restAfterSeconds })
    }

    /** In a circuit the next set is the next exercise, with no rest in between. */
    @Test
    fun `ticking a circuit set moves to the next member without resting`() {
        val session = SessionState(exercises = listOf(
            SessionExercise(Exercise(name = "Squat"), List(3) { SessionSet(0.0, 5) }, group = circuit),
            SessionExercise(Exercise(name = "Row"), List(3) { SessionSet(0.0, 5) }, group = circuit),
        ))
        assertEquals(SessionLogic.AfterSet(moveTo = 1, restSeconds = 0), SessionLogic.afterSetDone(done(session, 0, 0), 0, 0))
    }

    /** The group's rest comes after the last member of a round, and the round starts over. */
    @Test
    fun `the end of a round rests and returns to the first member`() {
        var session = SessionState(exercises = listOf(
            SessionExercise(Exercise(name = "Squat"), List(3) { SessionSet(0.0, 5) }, group = circuit),
            SessionExercise(Exercise(name = "Row"), List(3) { SessionSet(0.0, 5) }, group = circuit),
        ))
        session = done(done(session, 0, 0), 1, 0)
        assertEquals(SessionLogic.AfterSet(moveTo = 0, restSeconds = 75), SessionLogic.afterSetDone(session, 1, 0))
    }

    @Test
    fun `a lone exercise rests between sets and then for the rest block after it`() {
        val ex = SessionExercise(Exercise(name = "Press"), List(2) { SessionSet(0.0, 5) }, restSeconds = 90, restAfterSeconds = 180)
        val session = SessionState(exercises = listOf(ex))
        assertEquals(90, SessionLogic.afterSetDone(session, 0, 0).restSeconds)
        assertEquals(180, SessionLogic.afterSetDone(session, 0, 1).restSeconds)
    }
}
