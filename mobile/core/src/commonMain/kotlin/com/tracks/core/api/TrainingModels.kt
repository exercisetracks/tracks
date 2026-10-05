// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The training library — strength exercises, stretches, and what they work.
 *
 * ## Muscles are names, not an enum
 *
 * `primary_muscles` and `secondary_muscles` carry slugs from the `spec` YAML,
 * generated into `core.spec.MuscleGroupsData` and shared with the browser.
 * They stay `String` here rather than becoming a Kotlin enum: the generated
 * table is the definition, an enum would be a second one, and a server that
 * learns a new muscle would then fail to decode rather than simply showing a
 * name the body model does not highlight.
 *
 * ## Exercises and stretches are separate types
 *
 * They rhyme — both have muscles, equipment, difficulty, cues — but the fields
 * that differ are the ones a guided session is built out of. A stretch has a
 * hold in seconds and a side to swap; an exercise has sets, reps, and a working
 * weight. Merging them would mean every screen asking which half of the type it
 * was looking at.
 */
@Serializable
data class Exercise(
    val name: String,
    @SerialName("primary_muscles") val primaryMuscles: List<String> = emptyList(),
    @SerialName("secondary_muscles") val secondaryMuscles: List<String> = emptyList(),
    val equipment: List<String> = emptyList(),
    @SerialName("movement_pattern") val movementPattern: String? = null,
    val difficulty: Int = 1,
    @SerialName("is_compound") val isCompound: Boolean = false,
    val description: String? = null,
    val instructions: String? = null,
    val cues: List<String> = emptyList(),
    @SerialName("default_sets") val defaultSets: Int? = null,
    @SerialName("default_reps") val defaultReps: Int? = null,
    /**
     * The muscle-map artwork key, which is not always the exercise's own name.
     * See the body model's generated asset — a slug with no matching path just
     * draws nothing, which is why this is nullable rather than defaulted.
     */
    @SerialName("viewer_slug") val viewerSlug: String? = null,
    /** "preferred" / "excluded" / null — the user's own filing, set per exercise; excluded is never scheduled. */
    val preference: String? = null,
    @SerialName("is_custom") val isCustom: Boolean = false,
    @SerialName("custom_id") val customId: Int? = null,
)

/**
 * One stretch from the flexibility library, built-in or the user's own.
 *
 * The endpoint has no response model on the server (it assembles a dict), so
 * [ModelShapeTest] cannot check these names against a schema the way it does
 * for the strength library. They were read off the handler in
 * `app/api/flexibility/library.py`; treat a field that is always empty as a
 * rename there rather than as missing data.
 */
@Serializable
data class Stretch(
    val name: String,
    @SerialName("primary_muscles") val primaryMuscles: List<String> = emptyList(),
    @SerialName("secondary_muscles") val secondaryMuscles: List<String> = emptyList(),
    val equipment: List<String> = emptyList(),
    @SerialName("movement_pattern") val movementPattern: String? = null,
    val difficulty: Int = 1,
    /** How long to hold, per side if [eachSide]. The spine of the guided flow. */
    @SerialName("duration_per_side_sec") val durationPerSideSec: Int? = null,
    @SerialName("each_side") val eachSide: Boolean = false,
    val sets: Int? = null,
    val description: String? = null,
    val instructions: String? = null,
    val cautions: String? = null,
    val cues: List<String> = emptyList(),
    @SerialName("breath_cue") val breathCue: String? = null,
    val position: String? = null,
    @SerialName("viewer_slug") val viewerSlug: String? = null,
    val preference: String? = null,
    @SerialName("custom_id") val customId: Int? = null,
)

/**
 * A set the user has actually done, newest first.
 *
 * This is history rather than a plan: it is what the guided session writes back
 * through the ingest path, read here so a session can open with last time's
 * weight instead of an empty field.
 */
@Serializable
data class StrengthHistoryEntry(
    @SerialName("activity_id") val activityId: Int,
    @SerialName("activity_date") val activityDate: String,
    val sport: String = "",
    @SerialName("set_number") val setNumber: Int = 0,
    @SerialName("exercise_name") val exerciseName: String? = null,
    @SerialName("exercise_category") val exerciseCategory: String? = null,
    @SerialName("weight_kg") val weightKg: Double? = null,
    val repetitions: Int? = null,
    @SerialName("duration_seconds") val durationSeconds: Double? = null,
)

/** What the user says they have to train with; drives the library filter. */
@Serializable
data class EquipmentList(val equipment: List<String> = emptyList())

/**
 * One stretch's place in a flow.
 *
 * Everything here is nullable because a flow says only what it wants to change:
 * a stretch with no duration is held for whatever the library says it should
 * be. The runner resolves each of these against the library entry of the same
 * name, so this carries the deviations and the library carries the defaults.
 */
@Serializable
data class FlowStretch(
    /** Null only on a rest block, whose length is [durationSeconds]. */
    @SerialName("exercise_name") val exerciseName: String? = null,
    @SerialName("order_index") val orderIndex: Int = 0,
    val sets: Int? = null,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    @SerialName("rest_seconds") val restSeconds: Int? = null,
    @SerialName("coaching_note") val coachingNote: String? = null,
    /** `rest` for a rest block (no exercise), null for an exercise. See spec/sync.yaml. */
    @SerialName("item_kind") val itemKind: String? = null,
    /** Block membership — see [StepGroup]; repeated on every member. */
    @SerialName("group_uid") val groupUid: String? = null,
    @SerialName("group_kind") val groupKind: String? = null,
    @SerialName("group_rounds") val groupRounds: Int? = null,
    @SerialName("group_rest_seconds") val groupRestSeconds: Int? = null,
) {
    val isRest: Boolean get() = itemKind == "rest"
    /** The block this row belongs to, or null. */
    val group: StepGroup? get() = groupUid?.let {
        StepGroup(it, groupKind ?: "repeat", groupRounds ?: 1, groupRestSeconds ?: 0)
    }
}

/** A saved, ordered mobility routine. The user's own — there is no shared library of these. */
@Serializable
data class FlexibilityFlow(
    val id: Int,
    val name: String,
    val description: String? = null,
    @SerialName("include_in_plan") val includeInPlan: Boolean = true,
    @SerialName("sync_to_watch") val syncToWatch: Boolean = true,
    val tags: List<String> = emptyList(),
    val stretches: List<FlowStretch> = emptyList(),
)

/**
 * A one-rep max the user is telling the server rather than one it inferred.
 *
 * [lastWeightKg] and [lastReps] are the working set the number came from, kept
 * so the server can tell an entered max from a computed one later.
 */
/**
 * What the server accepts to save a flow.
 *
 * No ids anywhere: the stretches are sent in the order they should run and the
 * server numbers them from the list, so reordering is just sending them in a
 * different order. The update endpoint replaces the whole list, which is the
 * right contract for something whose order is part of its meaning.
 */
@Serializable
data class FlexibilityFlowIn(
    val name: String,
    val description: String? = null,
    @SerialName("include_in_plan") val includeInPlan: Boolean = true,
    @SerialName("sync_to_watch") val syncToWatch: Boolean = true,
    val tags: List<String> = emptyList(),
    val stretches: List<FlowStretch> = emptyList(),
)

@Serializable
data class OneRepMaxOverride(
    @SerialName("estimated_1rm_kg") val estimated1rmKg: Double,
    @SerialName("last_weight_kg") val lastWeightKg: Double? = null,
    @SerialName("last_reps") val lastReps: Int? = null,
)

/**
 * Where the user is on one lift.
 *
 * The server's running state per exercise, not a computation over history: it
 * updates this as sessions are logged, blending each session's best estimated
 * max into the standing one rather than replacing it, so a single bad day does
 * not undo a month. [lastWeightKg] and [lastReps] are what a guided session
 * opens the weight field on.
 */
@Serializable
data class StrengthProgress(
    @SerialName("exercise_name") val exerciseName: String,
    @SerialName("estimated_1rm_kg") val estimated1rmKg: Double? = null,
    @SerialName("last_weight_kg") val lastWeightKg: Double? = null,
    @SerialName("last_reps") val lastReps: Int? = null,
    @SerialName("last_session_volume_kg") val lastSessionVolumeKg: Double? = null,
    @SerialName("sessions_completed") val sessionsCompleted: Int = 0,
    /**
     * How the server intends to progress this lift: `linear` for the first
     * twenty sessions, then `weekly_undulating`, then `dup` past a hundred.
     * Shown rather than acted on — the phone does not do the programming.
     */
    @SerialName("progression_stage") val progressionStage: String = "linear",
)

/** One set as it happened. Weight in kg on the wire, whatever the display unit. */
@Serializable
data class LoggedSet(
    @SerialName("weight_kg") val weightKg: Double,
    val reps: Int,
    val rpe: Int? = null,
)

@Serializable
data class LoggedExercise(
    @SerialName("exercise_name") val exerciseName: String,
    val sets: List<LoggedSet> = emptyList(),
)

/**
 * A finished session, on its way to the server.
 *
 * [plannedWorkoutId] is what ties a free session apart from one that completes
 * a plan: set it and the server marks that workout done and refuses a second
 * log of the same one. A session put together on the phone from the library has
 * no planned workout behind it, and leaves it null.
 */
@Serializable
data class WorkoutSessionIn(
    @SerialName("planned_workout_id") val plannedWorkoutId: Int? = null,
    val exercises: List<LoggedExercise> = emptyList(),
    @SerialName("session_rpe") val sessionRpe: Int? = null,
    val notes: String? = null,
)

@Serializable
data class WorkoutSessionOut(
    val id: Int,
    @SerialName("planned_workout_id") val plannedWorkoutId: Int? = null,
    @SerialName("total_volume_kg") val totalVolumeKg: Double? = null,
    @SerialName("session_rpe") val sessionRpe: Int? = null,
    @SerialName("completed_at") val completedAt: String? = null,
)

// ── Saved workouts: the user's own strength templates ────────────────────────

/**
 * One exercise's place in a saved workout.
 *
 * Everything here is a *prescription* rather than a record: how many sets, what
 * rep range, how close to failure, how long to rest, and how the working weight
 * is decided. What actually happened goes to [WorkoutSessionIn] instead.
 *
 * [weightValue] means different things depending on [weightMethod], which is
 * the one sharp edge in this type: a fraction of the estimated one-rep max for
 * `percentage_e1rm`, a target RPE for `rpe`, and kilograms for `fixed`. The
 * server stores it the same way, so this is a faithful mirror rather than a
 * simplification — but a UI that shows it without saying which is showing a
 * number between 0 and 1 as if it were a weight.
 */
@Serializable
data class UserWorkoutExercise(
    /** Null only on a rest block, whose length is [restSeconds]. */
    @SerialName("exercise_name") val exerciseName: String? = null,
    /** `library` or `custom` — which table the name resolves against. */
    @SerialName("exercise_source") val exerciseSource: String = "library",
    /** Reps per set — one number, because the watch's workout step holds only one. */
    @SerialName("target_reps") val targetReps: Int = 8,
    @SerialName("target_sets") val targetSets: Int = 3,
    /** Reps in reserve: 0 is to failure, 2 a standard working set. */
    @SerialName("rir_target") val rirTarget: Int = 2,
    @SerialName("rest_seconds") val restSeconds: Int = 90,
    /** `fixed` | `percentage_e1rm` | `rpe`. See [weightValue]. */
    @SerialName("weight_method") val weightMethod: String = "percentage_e1rm",
    @SerialName("weight_value") val weightValue: Double? = null,
    @SerialName("order_index") val orderIndex: Int = 0,
    val notes: String? = null,
    /** `rest` for a rest block (no exercise), null for an exercise. See spec/sync.yaml. */
    @SerialName("item_kind") val itemKind: String? = null,
    /** Block membership — see [StepGroup]; repeated on every member. */
    @SerialName("group_uid") val groupUid: String? = null,
    @SerialName("group_kind") val groupKind: String? = null,
    @SerialName("group_rounds") val groupRounds: Int? = null,
    @SerialName("group_rest_seconds") val groupRestSeconds: Int? = null,
) {
    val isRest: Boolean get() = itemKind == "rest"
    /** The block this row belongs to, or null. */
    val group: StepGroup? get() = groupUid?.let {
        StepGroup(it, groupKind ?: "repeat", groupRounds ?: 1, groupRestSeconds ?: 0)
    }
}

/**
 * A saved workout — the user's own, not one the plan generated.
 *
 * [includeInPlan] is what lets the strength planner pick this up when it builds
 * a week; [syncToWatch] is whether it should also become a file on the wrist.
 * Both default the way the server defaults them.
 */
@Serializable
data class UserWorkout(
    val id: Int,
    val name: String,
    val description: String? = null,
    /** From a fixed vocabulary — see the server's `VALID_TAGS`. */
    val tags: List<String> = emptyList(),
    @SerialName("include_in_plan") val includeInPlan: Boolean = true,
    @SerialName("sync_to_watch") val syncToWatch: Boolean = false,
    val exercises: List<UserWorkoutExercise> = emptyList(),
)

/** What the server accepts to save one. The update endpoint takes the same shape. */
@Serializable
data class UserWorkoutIn(
    val name: String,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    @SerialName("include_in_plan") val includeInPlan: Boolean = true,
    @SerialName("sync_to_watch") val syncToWatch: Boolean = false,
    val exercises: List<UserWorkoutExercise> = emptyList(),
)
