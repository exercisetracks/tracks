// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.strengthplan

import com.tracks.core.strengthplan.QuickSession.Intensity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sessions built on the spot from a length, an intensity and muscles to
 * favour: the length is honoured, the chosen muscles get about two picks in
 * five without taking over, and no whole-body stretch follows another.
 */
class QuickSessionTest {

    private val stretches = StretchPlanning.buildCandidates(
        PlanLibrary.stretches, emptyList(), { _, _ -> null }, emptySet(), emptySet(),
    )

    @Test
    fun a_flow_lasts_about_as_long_as_was_asked() {
        for (minutes in listOf(10, 15, 30, 45, 60)) {
            for (intensity in Intensity.entries) {
                val steps = QuickSession.flexibility(stretches, minutes, intensity, emptyList(), "seed")
                val seconds = QuickSession.flexibilitySeconds(steps)
                assertTrue(seconds in (minutes * 60 * 0.8).toInt()..(minutes * 60 * 1.2).toInt(),
                    "$minutes min at $intensity came out at ${seconds / 60.0} min")
            }
        }
    }

    @Test
    fun the_chosen_muscles_get_their_share_not_the_whole_session() {
        val targets = QuickSession.targets(Flexibility.FULL_BODY_MOBILITY_MUSCLES, listOf("calves"), 10)
        assertEquals(4, targets.count { it == "calves" })
        assertTrue(targets.distinct().size >= 5, "the rest stays varied: $targets")
    }

    @Test
    fun a_long_flow_never_puts_two_whole_body_stretches_together() {
        val steps = QuickSession.flexibility(stretches, 60, Intensity.High, listOf("hamstrings"), "seed")
        fun whole(s: Map<String, Any?>) = s["each_side"] != true && s["side"] == null &&
            ((s["muscles"] as List<*>).size >= Flexibility.WHOLE_BODY_MIN_MUSCLES)
        steps.zipWithNext().forEach { (a, b) ->
            assertTrue(!(whole(a) && whole(b) && a["name"] == b["name"]), "${a["name"]} twice in a row")
        }
    }

    @Test
    fun a_strength_session_fits_its_length_with_the_intensitys_dose() {
        val exercises = PlanLibrary.exercises.map { (name, ex) ->
            @Suppress("UNCHECKED_CAST")
            QuickSession.QuickExercise(name, (ex["primary_muscles"] as List<String>))
        }
        val hard = QuickSession.strength(exercises, 45, Intensity.High, listOf("chest"), "seed")
            .filter { it["type"] == "strength_exercise" }
        assertTrue(hard.size >= 3)
        assertTrue(hard.all { it["sets"] == 4L && it["reps"] == 6L })
        assertEquals(hard.size, hard.map { it["name"] }.toSet().size, "no lift twice")
        val light = QuickSession.strength(exercises, 45, Intensity.Low, emptyList(), "seed")
            .filter { it["type"] == "strength_exercise" }
        assertTrue(light.size > hard.size, "lighter sets, more exercises in the same time")
    }

    private fun libraryExercises() = PlanLibrary.exercises.map { (name, ex) ->
        @Suppress("UNCHECKED_CAST")
        QuickSession.QuickExercise(
            name, ex["primary_muscles"] as List<String>, isCompound = ex["is_compound"] != false,
            pattern = ex["movement_pattern"] as String? ?: "push",
            equipment = (ex["equipment"] as List<String>).ifEmpty { listOf("bodyweight") },
        )
    }

    /**
     * Ten reps for everything was the old moderate. Now eight for a compound
     * lift and twelve for a single-joint one, each with a weight to use (a
     * guess off body weight the first time) and rest by the movement.
     */
    @Test
    fun a_moderate_session_is_eight_and_twelve_with_weights_and_varied_rest() {
        val lifter = QuickSession.Lifter(library = PlanLibrary.exercises, bodyWeightKg = 80.0, sex = "male")
        val lifts = QuickSession.strength(libraryExercises(), 60, Intensity.Medium, emptyList(), "seed", lifter = lifter)
            .filter { it["type"] == "strength_exercise" }
        val compound = PlanLibrary.exercises.filterValues { it["is_compound"] != false }.keys
        lifts.forEach { assertEquals(if (it["name"] in compound) 8L else 12L, it["reps"], "${it["name"]}") }
        assertTrue(lifts.any { (it["weight_kg"] as Double) > 0 && it["weight_estimated"] == true }, "weights: $lifts")
        val rests = lifts.map { (it["rest_seconds"] as Long) }
        assertTrue(rests.all { it in 60L..180L } && rests.distinct().size > 1, "rests $rests")
    }

    /**
     * The soul-crushing version: every set of one lift before the next. Lifts
     * that share kit (or need none) and no muscle now go in turn, a rest after
     * each; two machines never pair, so nobody crosses the gym mid-round.
     */
    @Test
    fun compatible_lifts_become_supersets_and_two_machines_never_pair() {
        val library = PlanLibrary.exercises
        val steps = QuickSession.strength(libraryExercises(), 60, Intensity.Medium, emptyList(), "seed",
            lifter = QuickSession.Lifter(library = library))
        val groups = steps.filter { it["group"] != null }.groupBy { (it["group"] as Map<*, *>)["uid"] }
        assertTrue(groups.isNotEmpty(), "no supersets in $steps")
        for ((_, members) in groups) {
            val lifts = members.filter { it["type"] == "strength_exercise" }
            assertTrue(lifts.size in 2..3)
            assertEquals(lifts.size - 1, members.count { it["type"] == "rest" }, "a rest between each")
            @Suppress("UNCHECKED_CAST")
            val stations = lifts.count { l ->
                val kit = (library.getValue(l["name"] as String)["equipment"] as List<String>).toSet() - "bodyweight"
                kit.isNotEmpty() && kit.none { it in setOf("dumbbell", "barbell", "kettlebell", "band") }
            }
            assertTrue(stations <= 1, "two stations in one superset: $lifts")
            val muscles = lifts.flatMap { it["primary_muscles"] as List<*> }
            assertEquals(muscles.size, muscles.toSet().size, "a muscle twice in one superset")
        }
    }

    /**
     * A planned post-run flow resized to ten minutes stays a post-run flow:
     * built around its own muscles, not a generic full-body one.
     */
    @Test
    fun a_resized_flow_keeps_to_its_own_muscles() {
        val legs = listOf("hamstrings", "calves", "quads", "hip_flexors")
        val steps = QuickSession.flexibility(stretches, 10, Intensity.Medium, emptyList(), "seed", base = legs)
        val onTarget = steps.count { s -> (s["muscles"] as List<*>).any { it in legs } }
        assertTrue(onTarget >= steps.size * 0.75, "most stretches work the flow's own muscles: $onTarget of ${steps.size}")
    }

    /**
     * Every stretch used to play the same length. Now each is held for what
     * it is (a pigeon outlasts a chair pose), the moderate level averages
     * about a minute, and the levels scale those holds rather than replace them.
     */
    @Test
    fun holds_differ_by_stretch_and_moderate_averages_about_a_minute() {
        val holds = stretches.map(Flexibility::holdSeconds)
        assertTrue(holds.average() in 55.0..65.0, "moderate average ${holds.average()}")
        val pigeon = stretches.first { it.name == "Pigeon Pose" }
        val chair = stretches.first { it.name == "Chair Pose" }
        assertTrue(Flexibility.holdSeconds(pigeon) > Flexibility.holdSeconds(chair))
        val steps = QuickSession.flexibility(stretches, 30, Intensity.Medium, emptyList(), "seed")
        assertTrue(steps.map { it["duration_seconds"] }.distinct().size > 2, "holds: ${steps.map { it["duration_seconds"] }}")
        assertEquals(15, QuickSession.scaledHold(20, 0.6))
        assertEquals(115, QuickSession.scaledHold(90, 1.25))
    }
}
