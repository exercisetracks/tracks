// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.strengthplan

import com.tracks.core.parse.PyRandom
import kotlin.math.roundToInt

/**
 * A strength or flexibility session built on the spot, from a length, an
 * intensity and, optionally, muscles to give extra attention -- the "Start
 * activity" button's sessions, and a planned flow resized before it starts.
 *
 * Built from the same libraries and the same selection the plan uses
 * ([Flexibility.selectStretchFlow]), so a quick 60-minute flow is made of the
 * same stretches a planned one would be, in the same order of positions, and
 * never two whole-body stretches in a row ([Flexibility.spaceWholeBody]).
 *
 * ## Emphasis is attention, not exclusion
 *
 * Chosen muscles take about two in every five picks ([EMPHASIS_SHARE]); the
 * rest stays a balanced full-body session. A session made only of the chosen
 * muscles would be lopsided in exactly the way a flow is meant to undo.
 *
 * Steps are planned-workout steps (`mobility_exercise`, `strength_exercise`),
 * which the guided runner already plays and the watch encoder already writes.
 */
object QuickSession {

    /** Five steps, the dashboard's slider: very gentle/light ... very deep/hard. */
    enum class Intensity { VeryLow, Low, Medium, High, VeryHigh }

    /** A lift from the library, as much of it as choosing needs. */
    data class QuickExercise(
        val name: String,
        val primaryMuscles: List<String>,
        val isCompound: Boolean = true,
        val difficulty: Int = 1,
        val pattern: String = "push",
        val equipment: List<String> = listOf("bodyweight"),
    )

    /**
     * Who is lifting, for the weights: the library (equipment, for
     * supersets), their per-lift records, and body weight and sex for a first
     * guess at a lift never logged. Empty, every weight is a fixed default.
     */
    data class Lifter(
        val library: Map<String, Map<String, Any?>> = emptyMap(),
        val records: Map<String, Map<String, Any?>> = emptyMap(),
        val bodyWeightKg: Double? = null,
        val sex: String? = null,
        val units: String = "metric",
    )

    /** Two in every five picks go to the chosen muscles. */
    const val EMPHASIS_SHARE = 0.4

    /** Between holds: getting out of one stretch and into the next (the runner's transition). */
    const val TRANSITION_S = 10

    /**
     * How much longer than its moderate hold ([Flexibility.holdSeconds]) each
     * stretch is held, by intensity. A scale rather than one hold for all, so
     * a pigeon still outlasts a chair pose at every level; moderate averages
     * about a minute across the library.
     */
    private val HOLD_SCALE = mapOf(
        Intensity.VeryLow to 0.6, Intensity.Low to 0.8, Intensity.Medium to 1.0, Intensity.High to 1.25, Intensity.VeryHigh to 1.5,
    )

    /** A stretch's hold at [scale]: whole five seconds, never under 15. */
    internal fun scaledHold(base: Int, scale: Double): Int =
        maxOf(MIN_HOLD_S, minOf(MAX_HOLD_S, (base * scale / 5).roundToInt() * 5))
    private val STRETCH_SETS = mapOf(
        Intensity.VeryLow to 1, Intensity.Low to 1, Intensity.Medium to 1, Intensity.High to 2, Intensity.VeryHigh to 1,
    )

    /**
     * Sets, reps for a compound and a single-joint lift, the %1RM each rep
     * count is done at, and the effort, by intensity. Moderate is eight and
     * twelve: a compound lift is heavier per rep, a single-joint one wants
     * more of them for the same work. The rest comes from the movement
     * ([Periodization.restSeconds]), one to three minutes.
     */
    private data class Dose(val sets: Int, val reps: Int, val isolationReps: Int, val rpe: Double)
    private val DOSE = mapOf(
        Intensity.VeryLow to Dose(2, 15, 15, 5.0),
        Intensity.Low to Dose(2, 12, 15, 6.0),
        Intensity.Medium to Dose(3, 8, 12, 7.0),
        Intensity.High to Dose(4, 6, 10, 8.0),
        Intensity.VeryHigh to Dose(5, 4, 8, 9.0),
    )

    /** The usual %1RM for a set of [reps] taken near, not to, failure. */
    private fun pctFor(reps: Int): Double = when {
        reps <= 4 -> 0.87
        reps <= 6 -> 0.80
        reps <= 8 -> 0.72
        reps <= 10 -> 0.70
        reps <= 12 -> 0.65
        else -> 0.60
    }

    /** Rest is per movement now; this is its rough average, for sizing a session. */
    private const val AVERAGE_REST_S = 100

    /** About how long one working set takes, and setting up for an exercise. */
    private const val SET_WORK_S = 45
    private const val EXERCISE_SETUP_S = 60

    /** A balanced full-body strength session, in the order a session would go. */
    val STRENGTH_MUSCLES = listOf(
        "quads", "chest", "upper_back", "hamstrings", "shoulders", "glutes",
        "lats", "abs", "triceps", "biceps", "calves", "lower_back",
    )

    /**
     * The muscles to fill, [count] long: the [base] list in turn, with the
     * [emphasis] muscles taking about [EMPHASIS_SHARE] of the places, spread
     * through the list rather than bunched at the start.
     */
    fun targets(base: List<String>, emphasis: List<String>, count: Int): List<String> {
        if (emphasis.isEmpty()) return List(count) { base[it % base.size] }
        val out = ArrayList<String>(count)
        var b = 0
        var e = 0
        var emphasised = 0
        for (i in 0 until count) {
            val due = (i + 1) * EMPHASIS_SHARE - emphasised >= 0.5
            if (due) {
                out += emphasis[e++ % emphasis.size]
                emphasised++
            } else {
                // Skip the chosen muscles in the balanced half: they have their share.
                var guard = 0
                while (base[b % base.size] in emphasis && guard++ < base.size) b++
                out += base[b++ % base.size]
            }
        }
        return out
    }

    /** A flexibility flow about [minutes] long. */
    fun flexibility(
        candidates: List<StretchCandidate>,
        minutes: Int,
        intensity: Intensity,
        emphasis: List<String>,
        seed: String,
        equipmentFree: Boolean = false,
        /** The muscles to balance across; full body unless a session already chose them. */
        base: List<String> = Flexibility.FULL_BODY_MOBILITY_MUSCLES,
    ): List<MutableMap<String, Any?>> {
        var scale = HOLD_SCALE.getValue(intensity)
        var sets = STRETCH_SETS.getValue(intensity)
        val goal = minutes * 60
        fun build(count: Int): List<MutableMap<String, Any?>> {
            val picked = Flexibility.selectStretchFlow(
                // Twice as many targets as places: a muscle with no stretch left
                // is passed over rather than leaving the flow short.
                candidates, targets(base.ifEmpty { Flexibility.FULL_BODY_MOBILITY_MUSCLES }, emphasis, count * 2), count,
                seed = seed, equipmentFree = equipmentFree, minCount = 1, orderByPosition = true,
            )
            val steps = picked.map(Flexibility::toStep).onEach {
                it["duration_seconds"] = scaledHold((it["duration_seconds"] as Number).toInt(), scale).toLong()
                it["sets"] = sets.toLong()
            }
            return Flexibility.spaceWholeBody(steps)
        }
        var count = (goal / ((AVERAGE_HOLD_S * scale * 1.5 + TRANSITION_S) * sets)).toInt().coerceIn(MIN_ITEMS, MAX_ITEMS)
        var steps = build(count)
        // Nudged until it fits: a flow's length depends on which stretches are
        // one-sided, which the first guess cannot know.
        repeat(MAX_ITEMS) {
            val total = flexibilitySeconds(steps)
            val more = build(count + 1)
            when {
                // More stretches while there are more to have.
                total < goal * 0.9 && count < MAX_ITEMS && flexibilitySeconds(more) > total -> {
                    count++
                    steps = more
                }
                total > goal * 1.1 && count > MIN_ITEMS -> steps = build(--count)
                else -> return@repeat
            }
        }
        // Still short with every distinct stretch used (a long gentle flow):
        // a longer session goes deeper rather than repeating itself -- more
        // sets, then longer holds.
        var total = flexibilitySeconds(steps)
        if (total < goal * 0.9 && sets < MAX_STRETCH_SETS) {
            sets = minOf(MAX_STRETCH_SETS, kotlin.math.ceil(sets * goal / total.toDouble()).toInt())
            steps = build(count)
            total = flexibilitySeconds(steps)
        }
        if (total < goal * 0.9) {
            scale = minOf(MAX_SCALE, scale * goal / total.toDouble())
            steps = build(count)
        }
        // A whole extra set can overshoot: trim stretches back to the length.
        while (flexibilitySeconds(steps) > goal * 1.1 && count > MIN_ITEMS) steps = build(--count)
        // Whole stretches are a coarse step at the deep levels — a one-sided
        // stretch held three times is minutes on its own — so the last
        // adjustment is to the holds, a little either way. Transitions do not
        // scale, so it takes a round or two to settle.
        repeat(3) {
            val now = flexibilitySeconds(steps)
            if (now >= goal * 0.9 && now <= goal * 1.1) return steps
            scale = (scale * goal / now).coerceIn(MIN_SCALE, MAX_SCALE)
            steps = build(count)
        }
        return steps
    }

    /** How long a flow's steps take to play, transitions included. */
    fun flexibilitySeconds(steps: List<Map<String, Any?>>): Int = steps.sumOf { s ->
        val hold = (s["duration_seconds"] as? Number)?.toInt() ?: 60
        val sets = (s["sets"] as? Number)?.toInt() ?: 1
        val sides = if (s["each_side"] == true) 2 else 1
        (hold + TRANSITION_S) * sides * sets
    }

    /** A strength session about [minutes] long. */
    fun strength(
        exercises: List<QuickExercise>,
        minutes: Int,
        intensity: Intensity,
        emphasis: List<String>,
        seed: String,
        base: List<String> = STRENGTH_MUSCLES,
        lifter: Lifter = Lifter(),
    ): List<MutableMap<String, Any?>> {
        val dose = DOSE.getValue(intensity)
        val perExercise = dose.sets * SET_WORK_S + (dose.sets - 1) * AVERAGE_REST_S + EXERCISE_SETUP_S
        val count = (minutes * 60 / perExercise).coerceIn(2, MAX_ITEMS)
        val byMuscle = HashMap<String, MutableList<QuickExercise>>()
        for (x in exercises) for (m in x.primaryMuscles) byMuscle.getOrPut(m) { ArrayList() } += x
        val seen = HashSet<String>()
        val out = ArrayList<MutableMap<String, Any?>>()
        // A few more targets than places: a muscle the library has nothing new
        // for is skipped, not left as a gap.
        for (muscle in targets(base.ifEmpty { STRENGTH_MUSCLES }, emphasis, count * 2)) {
            if (out.size >= count) break
            // Compound movements first, and easier ones at a light intensity;
            // the top few rotate by the seed so the same choices make a fresh
            // session another day.
            val pool = byMuscle[muscle].orEmpty().filter { it.name !in seen }
                .sortedWith(compareBy<QuickExercise>({ if (it.isCompound) 0 else 1 },
                    { if (intensity <= Intensity.Low) it.difficulty else -it.difficulty }, { it.name }))
                .toMutableList()
            if (pool.isEmpty()) continue
            val top = pool.take(3).toMutableList()
            PyRandom("$seed-$muscle-${out.size}").shuffle(top)
            val pick = top.first()
            seen += pick.name
            val reps = if (pick.isCompound) dose.reps else dose.isolationReps
            val pct = pctFor(reps)
            val (weight, estimated) = Loads.prescribedWeight(
                lifter.records[pick.name], pick.pattern, pick.isCompound, pick.equipment, pct,
                1.0, 1.0, lifter.bodyWeightKg, lifter.sex, lifter.units,
            )
            out += linkedMapOf<String, Any?>(
                "type" to "strength_exercise",
                "name" to pick.name,
                "sets" to dose.sets.toLong(),
                "reps" to reps.toLong(),
                "weight_kg" to weight,
                // The light end keeps sessions short, as the plan's supplementary tiers do.
                "rest_seconds" to Periodization.restSeconds(
                    pick.pattern, if (intensity <= Intensity.Low) 2 else 3, pick.isCompound, reps.toLong(),
                ),
                "target_rpe" to dose.rpe,
                "muscles" to pick.primaryMuscles,
                "primary_muscles" to pick.primaryMuscles,
                "movement_pattern" to pick.pattern,
            ).also { if (estimated && weight > 0) it["weight_estimated"] = true }
        }
        // Supersets where the kit allows, as the plan does (Supersets).
        val library = lifter.library.ifEmpty {
            exercises.associate { it.name to mapOf<String, Any?>("equipment" to it.equipment) }
        }
        return Supersets.group(out, library)
    }

    private const val MIN_ITEMS = 3
    private const val MAX_STRETCH_SETS = 3
    /** Savasana at the deepest level is 4.5 minutes; nothing else comes near. */
    private const val MAX_HOLD_S = 300
    private const val MIN_HOLD_S = 15
    /** The library's moderate holds average about this (see [Flexibility.holdSeconds]). */
    private const val AVERAGE_HOLD_S = 60
    /** How far a long gentle flow may stretch its holds to fill the time. */
    private const val MAX_SCALE = 2.0
    /** And how far a flow may shorten them, so a quick deep flow still has its stretches. */
    private const val MIN_SCALE = 0.5
    private const val MAX_ITEMS = 30
}
