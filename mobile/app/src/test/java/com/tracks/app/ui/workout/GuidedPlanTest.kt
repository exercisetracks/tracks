// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.workout

import com.tracks.core.api.PlannedWorkout
import com.tracks.core.api.WorkoutStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning a plan into a sequence somebody can follow.
 *
 * The expansion is where a guided workout is either a watch or a list of things
 * to read: "6 × 400 m" has to become twelve steps with the recoveries in the
 * right places, and getting the count wrong is not something the screen can
 * recover from — it will confidently announce interval four of five.
 *
 * The step fixtures mirror what the generator actually writes — see
 * `backend/app/calculators/plan/running.py` and `strength_plan/generator.py`.
 * They are built rather than decoded from JSON on purpose: `:app` deliberately
 * has no kotlinx-serialization on its classpath, and the wire names those
 * fixtures would exercise belong to `:core`'s own tests anyway.
 */
class GuidedPlanTest {

    private fun workout(
        vararg steps: WorkoutStep,
        sport: String = "running",
        durationMinutes: Int? = null,
        title: String = "Session",
        description: String? = null,
    ): PlannedWorkout = PlannedWorkout(
        id = 1,
        scheduledDate = "2026-08-18",
        sport = sport,
        title = title,
        description = description,
        durationMinutes = durationMinutes,
        steps = steps.toList(),
    )

    @Test
    fun `an interval session unrolls into every rep with recoveries between them`() {
        val plan = workout(
            WorkoutStep(type = "warmup", durationMin = 15.0, pace = "easy", note = "Easy jog"),
            WorkoutStep(
                type = "interval_set", reps = 6, distanceM = 400.0, restSec = 90,
                pace = "interval", note = "6 x 400m",
            ),
            WorkoutStep(type = "cooldown", durationMin = 10.0, pace = "easy", note = "Easy jog"),
        )

        val steps = guidedSteps(plan)

        // Warm-up, six reps with five recoveries between them, cool-down. The
        // sixth rep is followed by the cool-down, not by a recovery nobody does.
        assertEquals(13, steps.size)
        assertEquals(StepKind.Warmup, steps.first().kind)
        assertEquals(StepKind.Cooldown, steps.last().kind)
        assertEquals(6, steps.count { it.title == "Interval" })
        assertEquals(5, steps.count { it.kind == StepKind.Rest })
        assertEquals("1 of 6", steps[1].position)
        assertEquals("6 of 6", steps[11].position)
    }

    @Test
    fun `a distance rep is measured in metres and not in seconds`() {
        // The difference decides what the run pane counts down, and a 400 m rep
        // shown as a clock is a phone telling someone to keep going for four
        // minutes when the rep ends in eighty metres.
        val plan = workout(
            WorkoutStep(type = "interval_set", reps = 2, distanceM = 400.0, restSec = 60)
        )

        val rep = guidedSteps(plan).first()

        assertEquals(400.0, rep.metres!!, 0.0)
        assertNull(rep.seconds)
        assertTrue(rep.detail!!.contains("400 m"))
    }

    @Test
    fun `an effort set counts down the minutes each rep is held for`() {
        val plan = workout(
            WorkoutStep(
                type = "effort_set", reps = 3, durationMinEach = 8.0, restMin = 4.0,
                intensity = "threshold",
            ),
            sport = "mountain_biking",
        )

        val steps = guidedSteps(plan)

        assertEquals(5, steps.size)
        assertEquals(8 * 60, steps[0].seconds)
        assertEquals(4 * 60, steps[1].seconds)
        assertEquals(StepKind.Rest, steps[1].kind)
        assertTrue(steps[0].detail!!.contains("Threshold"))
    }

    @Test
    fun `a strength exercise becomes one step per set carrying what to log`() {
        val plan = workout(
            WorkoutStep(
                type = "strength_exercise", name = "Back Squat", sets = 3, reps = 5,
                weightKg = 100.0, targetRpe = 8.0, restSeconds = 120,
                cues = listOf("Brace before you descend"), phase = "main",
            ),
            sport = "strength_training",
        )

        val steps = guidedSteps(plan)

        assertEquals("three sets and the two rests between them", 5, steps.size)
        val set = steps.first()
        assertEquals("Back Squat", set.title)
        assertEquals("Set 1 of 3", set.position)
        // No countdown on a working set: a set ends when the bar is racked.
        assertNull(set.seconds)
        assertTrue(set.isOpen)
        assertEquals(120, steps[1].seconds)

        // The prescription travels with the step, which is what lets a finished
        // session be logged with real numbers rather than a note.
        assertNotNull(set.exercise)
        assertEquals("Back Squat", set.exercise!!.name)
        assertEquals(5, set.exercise!!.reps)
        assertEquals(100.0, set.exercise!!.weightKg!!, 0.0)
    }

    @Test
    fun `a one-sided stretch is two holds and not one held twice as long`() {
        val plan = workout(
            WorkoutStep(
                type = "mobility_exercise", name = "Pigeon Pose", durationSeconds = 45,
                sets = 2, eachSide = true, description = "Hips",
                cues = listOf("Square the hips"), breathCue = "Exhale into it",
            ),
            sport = "flexibility_training",
        )

        val steps = guidedSteps(plan)
        val holds = steps.filter { it.kind != StepKind.Rest }

        assertEquals("two sets, both sides", 4, holds.size)
        assertEquals(listOf("Left", "Right", "Left", "Right"), holds.map { it.position?.take(5)?.trim() })
        assertEquals(45, holds.first().seconds)
        assertTrue(holds.first().cues.contains("Exhale into it"))
        // Nothing to transition into after the last hold.
        assertTrue(steps.last().kind != StepKind.Rest)
    }

    @Test
    fun `a workout the generator only described still becomes something to follow`() {
        // Most non-running sports come through as prose and a duration. An
        // empty step list must not mean an empty screen with a Finish button.
        val plan = workout(
            sport = "mountain_biking",
            title = "Endurance ride",
            description = "Zone 2, stay off the big gear.",
            durationMinutes = 90,
        )

        val steps = guidedSteps(plan)

        assertEquals(1, steps.size)
        assertEquals("Endurance ride", steps.first().title)
        assertEquals(90 * 60, steps.first().seconds)
        assertEquals("Zone 2, stay off the big gear.", steps.first().note)
    }

    @Test
    fun `a step type nobody has heard of still runs`() {
        // The generator gains step types faster than this client is rebuilt, and
        // silently skipping the middle third of a workout is worse than showing
        // a step whose name is all we know about it.
        val plan = workout(
            WorkoutStep(type = "hill_repeats_new", durationMin = 20.0, note = "Up the fire road")
        )

        val steps = guidedSteps(plan)

        assertEquals(1, steps.size)
        assertEquals("Hill repeats new", steps.first().title)
        assertEquals(20 * 60, steps.first().seconds)
    }

    @Test
    fun `a malformed plan cannot expand into an unbounded list`() {
        // Reps arrive over the network. The failure mode without the cap is an
        // out-of-memory on a screen somebody opened at the gym.
        val plan = workout(
            WorkoutStep(type = "interval_set", reps = 100000, distanceM = 100.0, restSec = 30)
        )

        assertTrue("the expansion must be capped", guidedSteps(plan).size < 100)
    }

    @Test
    fun `a rest is announced as a rest with its length said in words`() {
        val plan = workout(WorkoutStep(type = "interval_set", reps = 2, distanceM = 400.0, restSec = 30, pace = "interval"))
        val rest = guidedSteps(plan).single { it.kind == StepKind.Rest }
        // It was "Recovery. 30s", which a TTS engine reads as "thirty s".
        assertEquals("30 second rest", spokenStep(rest))
        assertEquals("1 minute 30 second rest", spokenStep(rest.copy(seconds = 90)))
        assertEquals("2 minute rest", spokenStep(rest.copy(seconds = 120)))
    }

    @Test
    fun `a work step is announced with its units spelled out`() {
        val plan = workout(WorkoutStep(type = "interval_set", reps = 2, distanceM = 400.0, restSec = 30, pace = "interval"))
        val rep = guidedSteps(plan).first()
        assertEquals("Interval. 1 of 2. 400 metres, Interval", spokenStep(rep))
        assertEquals("8 minutes, Easy", spokenUnits("8 min · Easy"))
        assertEquals("5 minutes 30 seconds", spokenUnits("5:30"))
        assertEquals("45 seconds hold", spokenUnits("45s hold"))
    }

    @Test
    fun `the plan's walking warm-up and cool-down do not count toward the run's distance`() {
        val steps = guidedSteps(workout(
            WorkoutStep(type = "walk", durationMin = 5.0, note = "Brisk 5-min walk to warm up"),
            WorkoutStep(type = "run", durationMin = 30.0, pace = "easy"),
            WorkoutStep(type = "walk", durationMin = 5.0, note = "5-min walk to cool down"),
        ))
        assertEquals(listOf(false, true, false), steps.map { it.countsDistance })
    }
}
