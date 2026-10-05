// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire models for the dashboard.
 *
 * Split out of [Models] rather than appended to it because these share a
 * property nothing else in the client has: **every one of them is a derived
 * aggregate the server computed, and none of them is cacheable offline as-is.**
 * A summary for "last 30 days" is a different document every day, so unlike
 * activities — which are facts, mirrored locally and read from disk — these are
 * fetched live and the screen degrades to whatever it last held.
 *
 * That is worth stating because it is the one place this app knowingly steps
 * away from offline-first, and it is a deliberate trade rather than an
 * oversight. Recomputing CTL/ATL/TSB on the phone would mean holding every
 * activity's TSS locally and porting a fourth copy of the load model; the
 * server already caches these per user and answers in one round trip. The
 * offline story for the dashboard is a later, separate piece of work (mirror
 * the series, recompute the window locally) and is called out as such.
 *
 * Dates are ISO `YYYY-MM-DD` strings, not parsed types. The client has no date
 * library, and ISO dates sort lexicographically — which is precisely how the
 * web app filters its own series (`allLoad.filter(p => p.date >= after)`), so
 * string comparison here is the same operation, not an approximation of it.
 */

// ── Headline totals ──────────────────────────────────────────────────────────

/** `GET /coaching/ics-token`: the subscription URL a calendar app polls for the plan. */
@Serializable
data class UserIcsToken(
    val token: String,
    @SerialName("ics_url") val icsUrl: String,
)

@Serializable
data class MetricsSummary(
    @SerialName("activity_count") val activityCount: Int = 0,
    @SerialName("total_distance_km") val totalDistanceKm: Double? = null,
    @SerialName("total_duration_hours") val totalDurationHours: Double? = null,
    @SerialName("sport_count") val sportCount: Int = 0,
    @SerialName("device_count") val deviceCount: Int = 0,
    /** GPS activities only — the server excludes zero-distance rows. */
    @SerialName("avg_distance_km") val avgDistanceKm: Double? = null,
    @SerialName("avg_duration_minutes") val avgDurationMinutes: Double? = null,
)

@Serializable
data class SportBreakdown(
    val sport: String,
    @SerialName("activity_count") val activityCount: Int = 0,
    @SerialName("total_distance_km") val totalDistanceKm: Double? = null,
    @SerialName("total_duration_hours") val totalDurationHours: Double? = null,
    @SerialName("avg_duration_minutes") val avgDurationMinutes: Double? = null,
)

// ── Series ───────────────────────────────────────────────────────────────────

/**
 * One day of the fitness model.
 *
 * `ctl` is fitness, `atl` is fatigue, `tsb` is form — and form is the one the
 * user acts on, which is why [com.tracks.core.spec.tsbBandFor] classifies it
 * from the same `spec/zones.yaml` table the web app renders from.
 */
@Serializable
data class TrainingLoadPoint(
    val date: String,
    val tss: Double = 0.0,
    val ctl: Double = 0.0,
    val atl: Double = 0.0,
    val tsb: Double = 0.0,
    /** CTL change over the trailing 7 days; null early in the series. */
    @SerialName("ctl_ramp") val ctlRamp: Double? = null,
)

@Serializable
data class WeeklyVolumePoint(
    @SerialName("week_start") val weekStart: String,
    @SerialName("distance_km") val distanceKm: Double? = null,
    @SerialName("duration_hours") val durationHours: Double? = null,
    @SerialName("activity_count") val activityCount: Int = 0,
)

@Serializable
data class ActivityCalendarPoint(val date: String, val count: Int = 0)

@Serializable
data class Vo2MaxPoint(val date: String, val value: Double)

@Serializable
data class ReadinessHistoryPoint(
    val date: String,
    val score: Double = 0.0,
    @SerialName("hrv_score") val hrvScore: Double = 0.0,
    @SerialName("sleep_score") val sleepScore: Double = 0.0,
    @SerialName("resting_hr_score") val restingHrScore: Double = 0.0,
    @SerialName("training_score") val trainingScore: Double? = null,
    @SerialName("primary_driver") val primaryDriver: String = "default",
    /** `low` | `medium` | `high` — how much data backed the score. */
    val confidence: String = "low",
)

// ── Coaching ─────────────────────────────────────────────────────────────────

@Serializable
data class Readiness(
    val score: Double = 0.0,
    @SerialName("hrv_score") val hrvScore: Double = 0.0,
    @SerialName("sleep_score") val sleepScore: Double = 0.0,
    @SerialName("resting_hr_score") val restingHrScore: Double = 0.0,
    @SerialName("hrv_today") val hrvToday: Double? = null,
    @SerialName("hrv_baseline") val hrvBaseline: Double? = null,
    @SerialName("sleep_hours") val sleepHours: Double? = null,
    @SerialName("resting_hr_today") val restingHrToday: Double? = null,
    @SerialName("resting_hr_baseline") val restingHrBaseline: Double? = null,
    @SerialName("primary_driver") val primaryDriver: String = "default",
    val confidence: String = "low",
    val notes: List<String> = emptyList(),
)

@Serializable
data class TrainingSignal(
    val ctl: Double = 0.0,
    val atl: Double = 0.0,
    val tsb: Double = 0.0,
    @SerialName("ctl_ramp") val ctlRamp: Double? = null,
    @SerialName("injury_risk_warning") val injuryRiskWarning: Boolean = false,
    /** base | build | peak | taper, or null with no goal set. */
    val phase: String? = null,
    @SerialName("goal_note") val goalNote: String? = null,
)

@Serializable
data class WorkoutRecommendation(
    val sport: String = "",
    val intensity: String = "",
    @SerialName("duration_minutes") val durationMinutes: Int = 0,
    @SerialName("distance_km") val distanceKm: Double? = null,
    @SerialName("hr_min") val hrMin: Int? = null,
    @SerialName("hr_max") val hrMax: Int? = null,
    val description: String = "",
    val reasoning: String = "",
    @SerialName("projected_tss") val projectedTss: Double = 0.0,
    /** cardio | strength | mobility | rest. Defaulted for rows cached before v2. */
    val modality: String = "cardio",
    val title: String? = null,
    val focus: String? = null,
)

@Serializable
data class DailyCoaching(
    val date: String,
    val readiness: Readiness = Readiness(),
    val signal: TrainingSignal = TrainingSignal(),
    val recommendations: List<WorkoutRecommendation> = emptyList(),
    @SerialName("ai_enhanced") val aiEnhanced: Boolean = false,
)

/**
 * A workout on the plan.
 *
 * `steps` is deliberately absent: it is a list of free-form dicts the server
 * shapes per workout type, it is only meaningful to the FIT builder, and the
 * dashboard shows a title and a duration. Modelling it here would be modelling
 * it wrong.
 */
@Serializable
data class PlannedWorkout(
    val id: Int,
    @SerialName("scheduled_date") val scheduledDate: String,
    val sport: String = "",
    @SerialName("workout_type") val workoutType: String = "",
    val title: String = "",
    val description: String? = null,
    @SerialName("duration_minutes") val durationMinutes: Int? = null,
    @SerialName("distance_meters") val distanceMeters: Double? = null,
    @SerialName("is_complete") val isComplete: Boolean = false,
    @SerialName("completed_activity_id") val completedActivityId: Int? = null,
    /**
     * The workout's structure, when the plan generated one.
     *
     * Empty for anything the generator described only in prose, which is most
     * non-running sports. See [WorkoutStep] for why this is one flat type
     * rather than a sealed hierarchy.
     */
    val steps: List<WorkoutStep> = emptyList(),
    /**
     * `plan` when the generator wrote it, `user` when somebody added it.
     *
     * Decides whether regenerating the plan may replace it, and is what the
     * calendar labels as the user's own.
     */
    val origin: String = "plan",
)

/**
 * One step of a structured workout.
 *
 * The server sends a free-form object per step, discriminated by [type], with a
 * different subset of fields populated for each. A sealed hierarchy would model
 * that more precisely and would also mean a decode failure the first time the
 * generator learns a new step type, which is exactly the fragility this client
 * avoids everywhere else.
 *
 * So: one flat type, every field optional, unknown types rendered by their name.
 * The web app's `StepRow` reads the same union of fields.
 *
 * ## The three families in here
 *
 * The generator writes three quite different shapes into one list, and the
 * phone's guided runner has to walk all of them:
 *
 * - **Cardio** — `run`, `ride`, `swim`, `activity`, `warmup`, `cooldown`,
 *   `fartlek`: a duration in minutes, with a [pace] zone or an [intensity].
 * - **Repeats** — `interval_set` (reps × [distanceM], [restSec]) and
 *   `effort_set` (reps × [durationMinEach] or [durationSecEach], [restMin]).
 * - **Exercises** — `strength_exercise` ([sets] × [reps] at [weightKg]) and
 *   `mobility_exercise` ([sets] holds of [durationSeconds], [eachSide]).
 *
 * Everything past `note` was already being sent and simply thrown away: with
 * only the seven original fields modelled, a strength session decoded as a list
 * of steps with no name, no sets and no reps — enough to draw a bullet in the
 * calendar, and nothing a session could be run from.
 *
 * ## Numeric types are deliberate
 *
 * Counts ([reps], [sets], rest in whole seconds) are integers server-side and
 * are integers here. Anything the generator computes rather than prescribes is
 * a `Double`, because kotlinx will decode `12` into a `Double` and will throw on
 * `12.5` into an `Int` — and one throw fails the whole plan, not one step.
 */
@Serializable
data class WorkoutStep(
    val type: String = "",
    @SerialName("duration_min") val durationMin: Double? = null,
    /** Pace zone name for run steps — `easy`, `threshold`, … */
    val pace: String? = null,
    /** Effort zone for the sports that steer by power or feel rather than pace. */
    val intensity: String? = null,
    val reps: Int? = null,
    @SerialName("distance_m") val distanceM: Double? = null,
    @SerialName("rest_sec") val restSec: Int? = null,
    val note: String? = null,

    // ── Repeats ──────────────────────────────────────────────────────────────
    @SerialName("duration_min_each") val durationMinEach: Double? = null,
    @SerialName("duration_sec_each") val durationSecEach: Double? = null,
    @SerialName("rest_min") val restMin: Double? = null,
    /**
     * A fartlek's two halves, in minutes. Sent for `fartlek` steps only, and
     * needed to build one: without them the phone can describe the step but
     * cannot encode the alternating blocks a watch would run.
     */
    @SerialName("hard_min") val hardMin: Double? = null,
    @SerialName("easy_min") val easyMin: Double? = null,

    // ── Swimming, rowing and the other sport builders ────────────────────────
    /** The step's name on the watch ("CSS 100", "Kick"), when the plan gives one. */
    val label: String? = null,
    /** Pool stroke — `freestyle`, `drill`, … — sent as the watch's swim-stroke target. */
    val stroke: String? = null,
    /** Pool equipment — `swim_kickboard`, `swim_pull_buoy`, … */
    val equipment: String? = null,
    /** Rowing stroke rate, strokes per minute: the watch's cadence target. */
    @SerialName("spm_low") val spmLow: Int? = null,
    @SerialName("spm_high") val spmHigh: Int? = null,

    // ── Exercises: strength and mobility ─────────────────────────────────────
    /** The exercise or stretch. Absent on every cardio step. */
    val name: String? = null,
    val sets: Int? = null,
    @SerialName("weight_kg") val weightKg: Double? = null,
    @SerialName("target_rpe") val targetRpe: Double? = null,
    @SerialName("rest_seconds") val restSeconds: Int? = null,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    /** A one-sided stretch is two holds, not one held twice as long. */
    @SerialName("each_side") val eachSide: Boolean = false,
    val tempo: String? = null,
    /**
     * Garmin's own name for this movement, as a category slug and a number
     * within it.
     *
     * The pair is what makes an animation play on the watch — see
     * `com.tracks.core.fit.resolveExercise`. The server has always sent these;
     * modelling them is what lets the phone build a strength or mobility
     * workout that animates, rather than one that lists silent steps.
     */
    @SerialName("garmin_category") val garminCategory: String? = null,
    @SerialName("garmin_subtype") val garminSubtype: Int? = null,
    /** `warmup` | `main` | `finisher`, which is what orders a strength session. */
    val phase: String? = null,
    val cues: List<String> = emptyList(),
    val description: String? = null,
    @SerialName("breath_cue") val breathCue: String? = null,
    @SerialName("primary_muscles") val primaryMuscles: List<String> = emptyList(),
    /**
     * The block this step belongs to — a repeat group or a superset — or null
     * for a step on its own. Consecutive steps sharing [StepGroup.uid] form one
     * block; see `WorkoutBlocks.runs`.
     */
    val group: StepGroup? = null,
)

/**
 * A block around several steps: a circuit repeated [rounds] times, or a
 * superset done back to back for [rounds] sets, with [restSeconds] after each
 * round. Held on every member rather than as a row of its own — see
 * spec/sync.yaml, workout_exercise.
 */
@Serializable
data class StepGroup(
    val uid: String,
    /** `repeat` | `superset`. Both encode alike; the words tell the user which. */
    val kind: String = "repeat",
    val rounds: Int = 1,
    @SerialName("rest_seconds") val restSeconds: Int = 0,
)

/**
 * What the user is training for.
 *
 * The phone reads goals rather than editing them: a goal is the input to plan
 * generation, and the fields below are the ones a calendar needs — which goal
 * is live, what to call its plan on the watch, and when it ends.
 *
 * Every field the web's goal form writes is modelled, because the phone now
 * creates and edits goals and generates plans from them itself. Optional so a
 * row written by an older client, or a goal type that does not use a field,
 * decodes the same.
 */
@Serializable
data class TrainingGoal(
    val id: Int,
    /** The sync identity; see [com.tracks.core.local.LocalSources]. */
    val uid: String? = null,
    /** `event` | `fitness` | `volume_target`. */
    @SerialName("goal_type") val goalType: String = "",
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("event_name") val eventName: String? = null,
    @SerialName("event_sport") val eventSport: String? = null,
    @SerialName("event_date") val eventDate: String? = null,
    @SerialName("event_distance_meters") val eventDistanceMeters: Double? = null,
    @SerialName("days_per_week") val daysPerWeek: Int? = null,
    val notes: String? = null,
    /** A fitness goal's CTL change per week, −2 … +6. */
    @SerialName("ctl_ramp_per_week") val ctlRampPerWeek: Double? = null,
    /**
     * A fitness goal's sports; null or one = the single sport in [eventSport],
     * which stays set to the first pick (models/coaching.py says why).
     */
    @SerialName("fitness_sports") val fitnessSports: List<String>? = null,
    @SerialName("target_weekly_km") val targetWeeklyKm: Double? = null,
    @SerialName("volume_sport") val volumeSport: String? = null,
    @SerialName("plan_intensity") val planIntensity: Double? = null,
    @SerialName("mtb_discipline") val mtbDiscipline: String? = null,
    @SerialName("cycling_discipline") val cyclingDiscipline: String? = null,
    @SerialName("schedule_tests") val scheduleTests: Boolean? = null,
    @SerialName("include_strength") val includeStrength: Boolean? = null,
    @SerialName("strength_tier") val strengthTier: Int? = null,
    @SerialName("strength_days_per_week") val strengthDaysPerWeek: Int? = null,
    @SerialName("strength_session_minutes") val strengthSessionMinutes: Int? = null,
) {
    /**
     * What the watch calls the training plan, mirroring the server's
     * `_plan_identity`: the event's name, or the goal type made presentable.
     */
    val planName: String
        get() = eventName?.takeIf { it.isNotBlank() }
            ?: goalType.ifBlank { "Training" }.replaceFirstChar { it.uppercase() }

    /** The plan's own end, which is well past the last workout it carries. */
    val planEnd: String? get() = eventDate
}

/**
 * A generated training plan.
 *
 * [vdot] is the reason the phone fetches this at all: running pace zones are
 * derived from it, so an offline watch push has no pace targets without it.
 * See `com.tracks.core.fit.vdotToPaces`.
 */
@Serializable
data class TrainingPlan(
    val id: Int,
    @SerialName("goal_id") val goalId: Int,
    val sport: String = "",
    val vdot: Double? = null,
    @SerialName("generated_at") val generatedAt: String? = null,
    val workouts: List<PlannedWorkout> = emptyList(),
)

/**
 * A partial update to a planned workout. Unset fields are left alone.
 *
 * Merged **field by field** server-side rather than applied wholesale, which is
 * what lets two offline edits to different parts of the same workout both
 * survive — see the backend's `app/api/training_plan/merge.py`.
 */
@Serializable
data class PlannedWorkoutUpdate(
    @SerialName("scheduled_date") val scheduledDate: String? = null,
    val title: String? = null,
    val description: String? = null,
    @SerialName("duration_minutes") val durationMinutes: Int? = null,
    @SerialName("distance_meters") val distanceMeters: Double? = null,
    val sport: String? = null,
    @SerialName("workout_type") val workoutType: String? = null,
    val steps: List<WorkoutStep>? = null,
    @SerialName("is_complete") val isComplete: Boolean? = null,
    @SerialName("completed_activity_id") val completedActivityId: Int? = null,
    /**
     * When the user made this change — **not** when it was sent.
     *
     * An edit queued in a basement gym reaches the server hours after it was
     * made, and stamping it on arrival would let it beat a change somebody
     * made in between. Carrying the real instant is the whole reason a
     * queued edit can lose gracefully instead of silently winning.
     */
    @SerialName("edited_at") val editedAt: String? = null,
)

/**
 * A workout somebody added themselves.
 *
 * Belongs to no generated plan, which is what stops a regeneration deleting
 * it — see the server's create handler.
 */
@Serializable
data class PlannedWorkoutCreate(
    @SerialName("scheduled_date") val scheduledDate: String,
    val title: String,
    val sport: String = "running",
    @SerialName("workout_type") val workoutType: String = "easy",
    val description: String? = null,
    @SerialName("duration_minutes") val durationMinutes: Int? = null,
    @SerialName("distance_meters") val distanceMeters: Double? = null,
    val steps: List<WorkoutStep> = emptyList(),
    /** As [PlannedWorkoutUpdate.editedAt]: when it was written, not sent. */
    @SerialName("created_at") val createdAt: String? = null,
)

// ── Activity detail extras ───────────────────────────────────────────────────

/**
 * One climb, as the watch segmented it.
 *
 * `split_type` distinguishes the ascent from the descent and the rest between —
 * a ski day is mostly not climbing, and showing every split as a climb would
 * triple the count.
 *
 * **`grade_level` and `climb_result` are integers**, and this file said `String`
 * for both. The consequence was not a mis-rendered grade: kotlinx throws on a
 * number where it was promised a string, so the whole list failed to decode —
 * and the detail screen wraps that call in `runCatching { … }.getOrDefault(
 * emptyList())`, exactly so a sport without climbs costs nothing. The two
 * together meant every climbing activity silently showed no climbs at all,
 * which reads as "the phone does not have this feature" rather than as a bug.
 *
 * `climb_result` is Garmin's enum: **3 is a send**, anything else an attempt.
 * `grade_level` is an index into a scale, not a grade — see `climbGrade`.
 * `user_grade` is what the athlete typed, and beats the watch's guess.
 */
@Serializable
data class ClimbSplit(
    val id: Int,
    @SerialName("split_number") val splitNumber: Int? = null,
    @SerialName("split_type") val splitType: String? = null,
    @SerialName("start_time") val startTime: String? = null,
    @SerialName("end_time") val endTime: String? = null,
    @SerialName("duration_seconds") val durationSeconds: Double? = null,
    @SerialName("total_ascent") val totalAscent: Double? = null,
    @SerialName("avg_vert_speed") val avgVertSpeed: Double? = null,
    @SerialName("total_calories") val totalCalories: Int? = null,
    @SerialName("min_heart_rate") val minHeartRate: Int? = null,
    @SerialName("max_heart_rate") val maxHeartRate: Int? = null,
    // Double rather than Int though the server says integer: a Double decodes
    // either, an Int throws on `5.0`, and nothing here needs the distinction.
    @SerialName("difficulty_score") val difficultyScore: Double? = null,
    @SerialName("grade_level") val gradeLevel: Int? = null,
    @SerialName("climb_result") val climbResult: Int? = null,
    @SerialName("user_grade") val userGrade: String? = null,
) {
    /** True when the watch recorded this attempt as completed. */
    val isSend: Boolean get() = climbResult == CLIMB_RESULT_SEND

    /** An ascent rather than a rest or a descent. */
    val isActive: Boolean get() = splitType == null || splitType.contains("active", ignoreCase = true)

    val isRest: Boolean get() = splitType?.contains("rest", ignoreCase = true) == true

    /**
     * What to print for the grade.
     *
     * The athlete's own label wins; failing that the watch's index is rendered
     * on the V scale, which is what the web app does for both bouldering and
     * rope climbing.
     */
    val gradeDisplay: String? get() = userGrade ?: gradeLevel?.let { "V$it" }
}

/** Garmin's `climb_result` for a completed route. Attempts use other values. */
const val CLIMB_RESULT_SEND: Int = 3

/** One set of one exercise. */
@Serializable
data class StrengthSet(
    val id: Int,
    @SerialName("set_number") val setNumber: Int? = null,
    /** `active` or `rest` — rest sets are recorded too and are not shown. */
    @SerialName("set_type") val setType: String? = null,
    @SerialName("exercise_category") val exerciseCategory: String? = null,
    @SerialName("exercise_name") val exerciseName: String? = null,
    @SerialName("weight_kg") val weightKg: Double? = null,
    val repetitions: Int? = null,
    @SerialName("duration_seconds") val durationSeconds: Double? = null,
)

// ── Health ───────────────────────────────────────────────────────────────────

/**
 * One day of body data.
 *
 * Every field is nullable and most days carry only some: a watch worn at night
 * reports sleep and HRV, one worn for a run reports neither. The health screen
 * is built around that being normal rather than treating a gap as an error.
 */
@Serializable
data class DailyMetricFull(
    val date: String,
    @SerialName("resting_hr") val restingHr: Double? = null,
    val hrv: Double? = null,
    @SerialName("sleep_hours") val sleepHours: Double? = null,
    @SerialName("sleep_score") val sleepScore: Double? = null,
    @SerialName("sleep_deep_hours") val sleepDeepHours: Double? = null,
    @SerialName("sleep_light_hours") val sleepLightHours: Double? = null,
    @SerialName("sleep_rem_hours") val sleepRemHours: Double? = null,
    /**
     * Time in bed and not asleep, as the watch measured it.
     *
     * Never part of [sleepHours], which is time *asleep* — so a night's time in
     * bed is the two added together. Absent on every night recorded before the
     * parser started keeping it, which is not the same as a night with no
     * waking.
     */
    @SerialName("sleep_awake_hours") val sleepAwakeHours: Double? = null,
    /**
     * When the night began and ended — the hours it occupied, not its length.
     *
     * ISO-8601 with an offset, as the watch's file recorded it, so a client
     * shows the night in the zone it is standing in rather than in whatever
     * zone the server runs as. The span is time *in bed*: it runs from the
     * first stage record to the last and includes waking, which is what makes
     * it comparable with [sleepHours] plus [sleepAwakeHours].
     *
     * Both are absent for any night whose totals exist but whose stage
     * timeline was never kept — a normal answer, not an error, and the reason
     * the sleep history has to draw such a night from its totals alone.
     */
    @SerialName("sleep_start") val sleepStart: String? = null,
    @SerialName("sleep_end") val sleepEnd: String? = null,
    val steps: Int? = null,
    @SerialName("active_calories") val activeCalories: Int? = null,
    /**
     * What the body spent simply existing, pro-rated to how much of the day has
     * happened. Total calories burned is this plus [activeCalories] — which is
     * the figure the watch face shows, and the one people mean by "calories".
     */
    @SerialName("resting_calories") val restingCalories: Int? = null,
    @SerialName("avg_stress_level") val avgStressLevel: Double? = null,
    @SerialName("avg_respiration_rate") val avgRespirationRate: Double? = null,
    val spo2: Double? = null,
    /**
     * Body Battery, as the watch computed it.
     *
     * Five numbers because a day is a curve. [bodyBatteryHigh] and
     * [bodyBatteryLow] bound it, [bodyBatteryLast] is where it stands — "now"
     * for today, "at bedtime" for any earlier day — and charged/drained are the
     * day's totals gained and spent, which do not reduce to high minus low
     * because a day can rise and fall several times.
     *
     * Never derived on a client. Garmin has not published the model, so the
     * number is worth exactly as much as its being the device's own opinion.
     */
    @SerialName("body_battery_high") val bodyBatteryHigh: Int? = null,
    @SerialName("body_battery_low") val bodyBatteryLow: Int? = null,
    @SerialName("body_battery_last") val bodyBatteryLast: Int? = null,
    @SerialName("body_battery_charged") val bodyBatteryCharged: Int? = null,
    @SerialName("body_battery_drained") val bodyBatteryDrained: Int? = null,
    @SerialName("weight_kg") val weightKg: Double? = null,
    @SerialName("hydration_ml") val hydrationMl: Double? = null,
    @SerialName("calories_in") val caloriesIn: Double? = null,
)

// ── Weekly plan ──────────────────────────────────────────────────────────────

/** One day of the 7-day outlook: projected load plus what to do. */
@Serializable
data class PlanDay(
    val date: String,
    val ctl: Double = 0.0,
    val atl: Double = 0.0,
    val tsb: Double = 0.0,
    val recommendation: WorkoutRecommendation? = null,
)
