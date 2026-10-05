// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.spec

/**
 * Muscle activation over the generated table (spec/muscle_groups.yaml ->
 * MuscleGroupsData.kt).
 *
 * Hand-written, unlike the table it walks. Mirrors
 * frontend/src/spec/muscleActivation.js (the original, still-shipping
 * implementation this was ported from — see frontend/src/utils/muscleGroups.js)
 * and backend/app/calculators/muscle_activation.py. What keeps the three
 * honest is spec/fixtures/muscle_groups.json: the same input/output corpus
 * runs against all three, so an implementation that diverges fails its own
 * test suite.
 */

/** A FIT exercise_category's primary/secondary muscle keys. */
data class MuscleMap(val primary: List<String>, val secondary: List<String>)

/**
 * One shape on the anatomical body diagram, and what shades it.
 *
 * [muscle] is the activation key to read — not always the region name with its
 * side suffix dropped, because the artwork is coarser than the muscle
 * vocabulary. [slug] is the part the artwork draws under, and several regions
 * share one: front, side and rear deltoids are all `deltoids`, distinguished
 * only by [view]. See the `body_viewer` comment in spec/muscle_groups.yaml.
 */
data class BodyRegion(val muscle: String, val slug: String, val view: String)

/** One logged set, the input `computeMuscleActivation` reduces over. */
data class StrengthSet(
    val setType: String?,
    val exerciseCategory: String?,
    val repetitions: Int?,
)

/**
 * `activation`: 0..1 per muscle key, normalised to the session's peak.
 * `totals`: the raw weighted rep totals `activation` was normalised from.
 * `categoryCounts`: how many active sets touched each exercise category.
 */
data class MuscleActivationResult(
    val activation: Map<String, Double>,
    val totals: Map<String, Double>,
    val categoryCounts: Map<String, Int>,
)

private const val PRIMARY_WEIGHT = 1.0
private const val SECONDARY_WEIGHT = 0.4

/**
 * Given a session's sets, compute a 0..1 activation score per muscle key.
 * Weighted by (sets x reps) when available so a 5x5 squat session loads
 * quads/glutes more than a single isolated curl.
 *
 * Non-"active" sets (warmup, rest) and unmapped/unknown categories ("",
 * "65534", "unknown", or anything not in [CATEGORY_MUSCLES]) are skipped.
 *
 * `repetitions` is clamped to [1, 40] so a very-high-rep set (e.g. a 100-rep
 * core set) can't dominate a session that also included heavy compounds. A
 * `repetitions` of null OR ZERO both fall back to 10 — this mirrors the
 * original JavaScript's `set.repetitions || 10`, where `0` is falsy and takes
 * the same branch as missing. That is a genuine quirk, not a bug to silently
 * fix here: fixing it would make this port disagree with the web app on any
 * set logged with zero reps, which is exactly the kind of one-language drift
 * spec/ exists to prevent. See spec/README.md's rounding-differences table
 * for the project's other examples of this.
 */
fun computeMuscleActivation(sets: List<StrengthSet>): MuscleActivationResult {
    val totals = LinkedHashMap<String, Double>()
    val categoryCounts = LinkedHashMap<String, Int>()

    val activeSets = sets.filter { it.setType == "active" }

    for (set in activeSets) {
        val cat = (set.exerciseCategory ?: "").lowercase()
        if (cat.isEmpty() || cat == "65534" || cat == "unknown") continue

        val map = CATEGORY_MUSCLES[cat] ?: continue
        categoryCounts[cat] = (categoryCounts[cat] ?: 0) + 1

        val raw = set.repetitions
        val effective = if (raw == null || raw == 0) 10 else raw
        val reps = effective.coerceIn(1, 40).toDouble()

        for (m in map.primary) totals[m] = (totals[m] ?: 0.0) + reps * PRIMARY_WEIGHT
        for (m in map.secondary) totals[m] = (totals[m] ?: 0.0) + reps * SECONDARY_WEIGHT
    }

    val max = totals.values.maxOrNull()?.coerceAtLeast(0.0) ?: 0.0
    val activation = LinkedHashMap<String, Double>()
    if (max > 0) {
        for ((k, v) in totals) activation[k] = v / max
    }

    return MuscleActivationResult(activation, totals, categoryCounts)
}

/** Human label for a FIT exercise_category, title-cased from the key when the
 * category isn't in the table (e.g. Garmin firmware extensions like
 * "hip_hinge"). Null/blank returns "Unknown". */
fun categoryLabel(key: String?): String {
    if (key.isNullOrEmpty()) return "Unknown"
    return CATEGORY_LABELS[key] ?: key.split("_").joinToString(" ") { word ->
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}

/** Human label for a muscle key. Unlike [categoryLabel], the fallback is the
 * raw key unchanged — not title-cased — matching the original JS. */
fun muscleLabel(key: String): String = MUSCLE_LABELS[key] ?: key

/** Every muscle key that has a label — what a picker offers. */
val muscleKeys: Set<String> get() = MUSCLE_LABELS.keys
