// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.fit

import com.tracks.core.strengthplan.GarminAnimations

/**
 * Garmin's exercise vocabulary: the numbers a workout step carries, and the
 * names the watch expects to see beside them.
 *
 * Ported from `app/calculators/fit_workout.py`. The category table is a
 * transcription of the FIT `exercise_category` enum; the naming rules below
 * are not in any specification and were read off Connect's own workout files.
 */

/**
 * `exercise_category` as the watch numbers it.
 *
 * Kept as an explicit table rather than derived from the FIT profile because
 * the plan stores these as snake_case strings — this is the only place the two
 * vocabularies meet.
 */
internal val EXERCISE_CATEGORY: Map<String, Int> = mapOf(
    "bench_press" to 0, "calf_raise" to 1, "cardio" to 2, "carry" to 3, "chop" to 4,
    "core" to 5, "crunch" to 6, "curl" to 7, "deadlift" to 8, "flye" to 9,
    "hip_raise" to 10, "hip_stability" to 11, "hip_swing" to 12,
    "hyperextension" to 13, "lateral_raise" to 14, "leg_curl" to 15,
    "leg_raise" to 16, "lunge" to 17, "olympic_lift" to 18, "plank" to 19,
    "plyo" to 20, "pull_up" to 21, "push_up" to 22, "row" to 23,
    "shoulder_press" to 24, "shoulder_stability" to 25, "shrug" to 26,
    "sit_up" to 27, "squat" to 28, "total_body" to 29, "triceps_extension" to 30,
    "warm_up" to 31, "run" to 32, "bike" to 33, "cardio_sensors" to 34,
    "move" to 35, "pose" to 36, "banded_exercises" to 37, "battle_rope" to 38,
    "elliptical" to 39, "floor_climb" to 40, "indoor_bike" to 41, "indoor_row" to 42,
    "ladder" to 43, "sandbag" to 44, "sled" to 45, "sledge_hammer" to 46,
    "stair_stepper" to 47, "suspension" to 49, "tire" to 50,
)

/** No category known. A step still runs; the watch just shows no animation. */
internal const val UNKNOWN_CATEGORY = 65534

/** No exercise known within the category. As above. */
internal const val UNKNOWN_EXERCISE = 65535

/** The `pose` category, which is the one that gets a " Pose" suffix. */
private const val CATEGORY_POSE = 36

/**
 * Words Garmin leaves lowercase inside a name — "Low Lunge with Knee Down
 * Pose", not "…With…". Taken from its reference workouts rather than from an
 * English style guide, and the first word is always capitalised regardless.
 */
private val LOWERCASE_WORDS = setOf(
    "with", "and", "to", "of", "on", "in", "the", "a", "an", "or", "at", "by", "for",
)

/**
 * What a step's `(category, exercise)` pair resolves to on the wire.
 *
 * [displayName] is what goes in `wkt_step_name` and the `exercise_title`
 * block — which is not always what the user calls it. See [resolveYogaExercise].
 */
internal data class ResolvedExercise(
    val category: Int,
    val exercise: Int,
    val displayName: String,
)

/**
 * Resolve one strength exercise to the numbers and name a step carries.
 *
 * Strength animation lookup goes by the numbers, so the user's own name is
 * sent beside them. A stretch in a Yoga-app file is resolved differently — see
 * [resolveYogaExercise].
 */
internal fun resolveExercise(
    garminCategory: String?,
    garminSubtype: Int?,
    name: String?,
): ResolvedExercise {
    val categoryKey = garminCategory.orEmpty()
    val category = EXERCISE_CATEGORY[categoryKey.lowercase()] ?: UNKNOWN_CATEGORY
    val exercise = garminSubtype ?: UNKNOWN_EXERCISE
    val userName = (name ?: "Exercise").trim()
    return ResolvedExercise(category, exercise, userName)
}

/**
 * Resolve one hold in a Yoga-app file: a Garmin pose or the stretch's own
 * name, never both. `_resolve_yoga_keys` in fit_workout.py.
 *
 * ## A pose goes out under Garmin's name
 *
 * The Fenix Yoga app does not find a pose animation from the
 * `(exercise_category, exercise_name)` integers alone — it also matches the
 * step's name against its own pose dictionary. A stretch sent as "Kneeling Hip
 * Flexor Stretch" with the correct numbers is accepted and shows no animation;
 * the same step sent as "Low Lunge with Knee Down Pose" plays. So when the
 * Yoga app animates the pair, the user's library name is replaced by
 * Garmin's canonical one.
 *
 * ## Anything else goes out under its own name
 *
 * No mapping, or a pair only the strength app animates, is sent as the
 * unknown category with the stretch's own name. The watch labels a step from
 * the `exercise_title` keyed by its pair, so each distinct name takes the next
 * free number in [ownNames]; one shared number would show every such step
 * under the first one's name. That is the encoding third-party generators use
 * for custom strength exercises, unverified in the 6X Yoga app.
 */
internal fun resolveYogaExercise(
    garminCategory: String?,
    garminSubtype: Int?,
    name: String?,
    ownNames: MutableMap<String, Int>,
): ResolvedExercise {
    val category = EXERCISE_CATEGORY[garminCategory.orEmpty().lowercase()]
    if (category != null && garminSubtype != null &&
        GarminAnimations.yogaAnimatable(garminCategory, garminSubtype.toLong())
    ) {
        val official = garminOfficialName(category, garminSubtype)
        if (official != null) return ResolvedExercise(category, garminSubtype, official)
    }
    // Python's `name or "Stretch"`: an empty name is no name.
    val own = (name?.takeIf { it.isNotEmpty() } ?: "Stretch").trim()
    return ResolvedExercise(UNKNOWN_CATEGORY, ownNames.getOrPut(own) { ownNames.size }, own)
}

/**
 * Garmin's own name for a catalogued pair, or null when it catalogues none.
 *
 * The manifest stores `LOW_LUNGE_WITH_KNEE_DOWN`; the watch wants
 * "Low Lunge with Knee Down Pose".
 */
internal fun garminOfficialName(category: Int, exercise: Int): String? {
    val display = GARMIN_POSE_TITLES[(category.toLong() shl 32) or exercise.toLong()]
        ?: return null
    val words = display.lowercase().split('_')
    val title = words.mapIndexed { index, word ->
        if (index > 0 && word in LOWERCASE_WORDS) word else word.replaceFirstChar { it.uppercase() }
    }.joinToString(" ")
        // The only possessive in the pose enum, which stores no apostrophes.
        .replace("Childs", "Child's")
    return if (category == CATEGORY_POSE && !title.endsWith(" Pose")) "$title Pose" else title
}
