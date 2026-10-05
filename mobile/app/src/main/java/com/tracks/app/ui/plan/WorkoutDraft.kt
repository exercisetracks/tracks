// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.plan

import com.tracks.core.api.PlannedWorkout
import com.tracks.core.api.PlannedWorkoutCreate
import com.tracks.core.api.PlannedWorkoutUpdate
import com.tracks.core.api.WorkoutStep

/**
 * A workout being written, before it is a workout.
 *
 * Separate from the sheet that edits it so the part with rules in it — what a
 * kind of workout may contain, what an edit actually changes — can be tested
 * without Compose. The sheet holds one of these and does nothing else.
 *
 * Numbers are [String] rather than [Int] because a half-typed number is a real
 * state a text field passes through: parsing on every keystroke turns "1" into
 * a workout of one minute the moment the user means to type "15", and an empty
 * field has to stay empty rather than becoming zero.
 */
data class WorkoutDraft(
    /** Null for a workout being added; the id for one being edited. */
    val id: Int? = null,
    val date: String,
    val title: String = "",
    val sport: String = "running",
    val workoutType: String = "easy",
    val description: String = "",
    val durationMinutes: String = "",
    val steps: List<WorkoutStep> = emptyList(),
) {
    val isNew: Boolean get() = id == null

    /** A workout needs a name and a day; everything else has a sensible default. */
    val canSave: Boolean get() = title.isNotBlank() && date.isNotBlank()

    /**
     * Which family of steps this workout can hold.
     *
     * The type decides it, because the FIT encoder routes on the same value:
     * a `strength` workout is built from `strength_exercise` steps and an
     * endurance one from timed blocks. Offering the wrong kind would let
     * somebody write steps that are silently dropped when the watch file is
     * built — which is exactly the sort of quiet nothing this project has
     * spent too long chasing.
     */
    val stepFamily: StepFamily
        get() = when (workoutType) {
            "strength" -> StepFamily.STRENGTH
            "mobility", "flexibility" -> StepFamily.MOBILITY
            "rest" -> StepFamily.NONE
            else -> StepFamily.ENDURANCE
        }

    fun toCreate(): PlannedWorkoutCreate = PlannedWorkoutCreate(
        scheduledDate = date,
        title = title.trim(),
        sport = sport,
        workoutType = workoutType,
        description = description.takeIf { it.isNotBlank() },
        durationMinutes = durationMinutes.toIntOrNull(),
        steps = steps,
    )

    /**
     * Only what actually changed.
     *
     * A patch that names every field would stamp every field's edit time, and
     * a workout opened and closed without a change would then beat a real edit
     * made elsewhere in the meantime. So each field is compared against what
     * was loaded and left out when it matches.
     */
    fun toUpdate(original: PlannedWorkout): PlannedWorkoutUpdate = PlannedWorkoutUpdate(
        scheduledDate = date.takeIf { it != original.scheduledDate },
        title = title.trim().takeIf { it != original.title },
        description = description.takeIf { (it.ifBlank { null }) != original.description },
        durationMinutes = durationMinutes.toIntOrNull().takeIf { it != original.durationMinutes },
        sport = sport.takeIf { it != original.sport },
        workoutType = workoutType.takeIf { it != original.workoutType },
        steps = steps.takeIf { it != original.steps },
    )

    /** True when nothing here differs from what was loaded. */
    fun unchanged(original: PlannedWorkout): Boolean =
        toUpdate(original) == PlannedWorkoutUpdate()

    companion object {
        fun of(workout: PlannedWorkout) = WorkoutDraft(
            id = workout.id,
            date = workout.scheduledDate,
            title = workout.title,
            sport = workout.sport.ifBlank { "running" },
            workoutType = workout.workoutType.ifBlank { "easy" },
            description = workout.description.orEmpty(),
            durationMinutes = workout.durationMinutes?.toString().orEmpty(),
            steps = workout.steps,
        )

        fun blank(date: String) = WorkoutDraft(date = date)
    }
}

/** Which step kinds a workout may hold, decided by its type. */
enum class StepFamily { ENDURANCE, STRENGTH, MOBILITY, NONE }

/**
 * The step kinds the watch can actually be told about.
 *
 * Deliberately not "every type the plan generator emits". The FIT encoder
 * skips a step type it does not recognise, so anything offered here that it
 * cannot build would be a step the user wrote and the watch never sees. This
 * list is the intersection, and it is the one worth maintaining.
 */
enum class StepKind(
    val type: String,
    val label: String,
    val family: StepFamily,
) {
    WARMUP("warmup", "Warm up", StepFamily.ENDURANCE),
    RUN("run", "Steady block", StepFamily.ENDURANCE),
    INTERVALS("interval_set", "Intervals", StepFamily.ENDURANCE),
    EFFORTS("effort_set", "Efforts", StepFamily.ENDURANCE),
    FARTLEK("fartlek", "Fartlek", StepFamily.ENDURANCE),
    COOLDOWN("cooldown", "Cool down", StepFamily.ENDURANCE),
    STRENGTH("strength_exercise", "Exercise", StepFamily.STRENGTH),
    MOBILITY("mobility_exercise", "Hold", StepFamily.MOBILITY),
    ;

    companion object {
        fun forFamily(family: StepFamily): List<StepKind> = entries.filter { it.family == family }

        /** The kind a loaded step is, or null for one this editor cannot show. */
        fun of(step: WorkoutStep): StepKind? = entries.firstOrNull { it.type == step.type }
    }
}

/** A new step of [kind], with the defaults the plan generator would use. */
fun newStep(kind: StepKind): WorkoutStep = when (kind) {
    StepKind.WARMUP -> WorkoutStep(type = "warmup", durationMin = 15.0, pace = "easy")
    StepKind.RUN -> WorkoutStep(type = "run", durationMin = 30.0, pace = "easy")
    StepKind.INTERVALS -> WorkoutStep(
        type = "interval_set", reps = 4, distanceM = 800.0, restSec = 90, pace = "threshold",
    )
    StepKind.EFFORTS -> WorkoutStep(
        type = "effort_set", reps = 3, durationMinEach = 8.0, restMin = 4.0, intensity = "sweet_spot",
    )
    StepKind.FARTLEK -> WorkoutStep(
        type = "fartlek", hardMin = 3.0, easyMin = 2.0, reps = 5, pace = "threshold",
    )
    StepKind.COOLDOWN -> WorkoutStep(type = "cooldown", durationMin = 10.0, pace = "easy")
    StepKind.STRENGTH -> WorkoutStep(
        type = "strength_exercise", name = "", sets = 3, reps = 8, restSeconds = 90,
    )
    StepKind.MOBILITY -> WorkoutStep(
        type = "mobility_exercise", name = "", durationSeconds = 45, sets = 1,
    )
}

/** Sports a workout can be filed under, and what the watch calls each. */
val SPORT_OPTIONS = listOf(
    "running" to "Run",
    "cycling" to "Ride",
    "mountain_biking" to "MTB",
    "road_biking" to "Road",
    "gravel_cycling" to "Gravel",
    "swimming" to "Swim",
    "hiking" to "Hike",
    "walking" to "Walk",
    "open_water_swimming" to "Open water",
    "rowing" to "Row",
    "cross_country_skiing" to "XC ski",
    "climbing" to "Climb",
    "training" to "Gym",
)

/**
 * Workout types, in the order somebody picks them.
 *
 * The last three change what the workout *is* rather than how hard it is —
 * they route to a different watch app entirely — so they sit apart from the
 * endurance intensities.
 */
val WORKOUT_TYPE_OPTIONS = listOf(
    "easy" to "Easy",
    "long" to "Long",
    "tempo" to "Tempo",
    "interval" to "Intervals",
    "recovery" to "Recovery",
    "race" to "Race",
    "strength" to "Strength",
    "mobility" to "Mobility",
    "rest" to "Rest",
)

/** Pace zones a step can target. Matches what a plan's VDOT resolves. */
val PACE_OPTIONS = listOf(
    "recovery" to "Recovery",
    "easy" to "Easy",
    "marathon" to "Marathon",
    "threshold" to "Threshold",
    "interval" to "Interval",
    "repetition" to "Rep",
)

/** Effort zones for the sports that steer by power or feel rather than pace. */
val INTENSITY_OPTIONS = listOf(
    "recovery" to "Recovery",
    "easy" to "Easy",
    "endurance" to "Endurance",
    "tempo" to "Tempo",
    "sweet_spot" to "Sweet spot",
    "threshold" to "Threshold",
    "vo2" to "VO2",
)
