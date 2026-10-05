// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.strength

import com.tracks.core.api.Exercise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The guided session's decisions, and its survival of the process being killed. */
@RunWith(RobolectricTestRunner::class)
// A bare Application: the real one starts MapLibre's native code, which the JVM cannot load.
@Config(application = android.app.Application::class)
class SessionLogicTest {

    private val squat = Exercise("Back Squat", primaryMuscles = listOf("quads", "glutes"),
        equipment = listOf("barbell"), movementPattern = "squat")
    private val goblet = Exercise("Goblet Squat", primaryMuscles = listOf("quads"),
        equipment = listOf("dumbbell"), movementPattern = "squat")
    private val lunge = Exercise("Lunge", primaryMuscles = listOf("quads", "glutes"), movementPattern = "lunge")
    private val press = Exercise("Bench Press", primaryMuscles = listOf("chest"), equipment = listOf("barbell"),
        movementPattern = "push")
    private val library = listOf(squat, goblet, lunge, press)

    private fun ex(e: Exercise, vararg done: Boolean) =
        SessionExercise(e, done.map { SessionSet(60.0, 5, it) })

    @Test
    fun next_skips_finished_exercises_and_wraps() {
        val list = listOf(ex(squat, false), ex(press, true), ex(lunge, false))
        assertEquals(2, SessionLogic.nextUnfinished(list, 0))
        assertEquals(0, SessionLogic.nextUnfinished(list, 2))
    }

    @Test
    fun next_is_null_when_everything_else_is_done() {
        assertNull(SessionLogic.nextUnfinished(listOf(ex(squat, false), ex(press, true)), 0))
    }

    @Test
    fun substitutes_prefer_the_same_pattern_then_the_same_muscles() {
        val got = SessionLogic.substitutes(squat, library, setOf("Back Squat"), emptySet(), onlyMine = false)
        assertEquals(listOf("Goblet Squat", "Lunge"), got.map { it.name })
    }

    @Test
    fun substitutes_respect_owned_equipment_but_bodyweight_always_counts() {
        val got = SessionLogic.substitutes(squat, library, emptySet(), setOf("barbell"), onlyMine = true)
        assertEquals(listOf("Lunge"), got.map { it.name })
    }

    @Test
    fun a_substitute_is_never_already_in_the_session() {
        val got = SessionLogic.substitutes(squat, library, setOf("Back Squat", "Goblet Squat"), emptySet(), false)
        assertEquals(listOf("Lunge"), got.map { it.name })
    }

    @Test
    fun the_summary_counts_only_sets_that_happened() {
        val s = SessionState(
            exercises = listOf(ex(squat, true, true, false), ex(press, false)),
            startedAtMillis = 1_000L,
        )
        val sum = SessionLogic.summary(s, 1_000L + 31 * 60_000L)
        assertEquals(2, sum.setsDone)
        assertEquals(1, sum.exercisesDone)
        assertEquals(600.0, sum.volumeKg, 0.0)
        assertEquals(31, sum.minutes)
    }

    @Test
    fun rest_remaining_rounds_up_and_ends_at_the_deadline() {
        assertEquals(2, SessionLogic.restRemaining(10_000, 8_500))
        assertNull(SessionLogic.restRemaining(10_000, 10_000))
        assertNull(SessionLogic.restRemaining(null, 0))
    }

    /** The process is reclaimed mid-workout: the session, rest deadline included, comes back whole. */
    @Test
    fun a_session_survives_being_written_down_and_read_back() {
        val s = SessionState(
            exercises = listOf(ex(squat, true, false), ex(press, false)),
            current = 1, startedAtMillis = 42L, restEndsAt = 99_000L,
        )
        val back = SessionLogic.decode(SessionLogic.encode(s), library)!!
        assertEquals(s.exercises, back.exercises)
        assertEquals(1, back.current)
        assertEquals(42L, back.startedAtMillis)
        assertEquals(99_000L, back.restEndsAt)
    }

    /** A custom exercise deleted while the session sat on disk is dropped, not a crash. */
    @Test
    fun an_exercise_gone_from_the_library_is_dropped_on_restore() {
        val s = SessionState(exercises = listOf(ex(squat, true), ex(Exercise("Gone"), false)), current = 1)
        val back = SessionLogic.decode(SessionLogic.encode(s), library)!!
        assertEquals(listOf("Back Squat"), back.exercises.map { it.exercise.name })
        assertEquals(0, back.current)
    }

    @Test
    fun unreadable_text_restores_nothing() {
        assertNull(SessionLogic.decode("not json", library))
    }
}
