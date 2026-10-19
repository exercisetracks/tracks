// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.strengthplan

import com.tracks.core.parse.PyRandom

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
    )

    /** Two in every five picks go to the chosen muscles. */
    const val EMPHASIS_SHARE = 0.4

    /** Between holds: getting out of one stretch and into the next (the runner's transition). */
    const val TRANSITION_S = 10

    /** Hold, seconds, and sets, by intensity: gentle, moderate, deep. */
    private val HOLD_S = mapOf(
        Intensity.VeryLow to 20, Intensity.Low to 30, Intensity.Medium to 45, Intensity.High to 60, Intensity.VeryHigh to 90,
    )
    private val STRETCH_SETS = mapOf(
        Intensity.VeryLow to 1, Intensity.Low to 1, Intensity.Medium to 1, Intensity.High to 2, Intensity.VeryHigh to 1,
    )

    /** Sets, reps, rest (s) and target RPE, by intensity: light, moderate, hard. */
    private data class Dose(val sets: Int, val reps: Int, val restS: Int, val rpe: Double)
    private val DOSE = mapOf(
        Intensity.VeryLow to Dose(2, 15, 45, 5.0),
        Intensity.Low to Dose(2, 12, 60, 6.0),
        Intensity.Medium to Dose(3, 10, 90, 7.0),
        Intensity.High to Dose(4, 6, 120, 8.0),
        Intensity.VeryHigh to Dose(5, 4, 150, 9.0),
    )

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
        var hold = HOLD_S.getValue(intensity)
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
                it["duration_seconds"] = hold.toLong()
                it["sets"] = sets.toLong()
            }
            return Flexibility.spaceWholeBody(steps)
        }
        var count = (goal / ((hold * 1.5 + TRANSITION_S) * sets)).toInt().coerceIn(MIN_ITEMS, MAX_ITEMS)
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
            hold = minOf(MAX_HOLD_S, (hold * goal / total.toDouble()).toInt() / 5 * 5)
            steps = build(count)
        }
        // A whole extra set can overshoot: trim stretches back to the length.
        while (flexibilitySeconds(steps) > goal * 1.1 && count > MIN_ITEMS) steps = build(--count)
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
    ): List<MutableMap<String, Any?>> {
        val dose = DOSE.getValue(intensity)
        val perExercise = dose.sets * SET_WORK_S + (dose.sets - 1) * dose.restS + EXERCISE_SETUP_S
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
            out += linkedMapOf(
                "type" to "strength_exercise",
                "name" to pick.name,
                "sets" to dose.sets.toLong(),
                "reps" to dose.reps.toLong(),
                "rest_seconds" to dose.restS.toLong(),
                "target_rpe" to dose.rpe,
                "muscles" to pick.primaryMuscles,
            )
        }
        return out
    }

    private const val MIN_ITEMS = 3
    private const val MAX_STRETCH_SETS = 3
    private const val MAX_HOLD_S = 90
    private const val MAX_ITEMS = 30
}
