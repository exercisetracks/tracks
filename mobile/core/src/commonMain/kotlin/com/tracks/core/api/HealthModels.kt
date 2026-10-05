// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The half of the Health page the watch does not record.
 *
 * Injuries, medications and meals are all *entered* rather than measured, which
 * is why they live apart from [DailyMetricFull]: that one is a passive readout
 * of what a wrist noticed overnight, and these three only exist because someone
 * typed them. That difference drives the UI too — the metric cards are charts,
 * and these are lists with an add button.
 *
 * Every field is nullable or defaulted for the usual reason ([ModelShapeTest]
 * enforces it): a server that stops sending one should cost a hidden row, not a
 * parse failure that empties the screen.
 */

// ── Injuries ─────────────────────────────────────────────────────────────────

/**
 * One injury, open or healed.
 *
 * `endDate` null is the whole state machine: an injury with no end date is
 * current, and "healing" one is a patch that sets the date to today rather than
 * a separate status field. The web app works the same way, which is what lets
 * both clients agree on what "active" means without a shared enum.
 */
@Serializable
data class Injury(
    val id: Int,
    @SerialName("body_part") val bodyPart: String = "",
    @SerialName("injury_type") val injuryType: String = "",
    /** 1–10 as the server models it; higher is worse. */
    val severity: Int = 1,
    @SerialName("start_date") val startDate: String = "",
    @SerialName("end_date") val endDate: String? = null,
    val notes: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    val isActive: Boolean get() = endDate.isNullOrBlank()
}

/**
 * An activity that happened around an injury.
 *
 * The server picks the window — a week either side of the injury's span — and
 * returns a slimmer shape than the activity list does, because the question
 * here is not "what did I do" but "what was I doing when this happened, and
 * what did I go back to". [daysFromInjury] is the whole point: negative is
 * before it started, which is where a cause would be.
 */
@Serializable
data class InjuryActivity(
    val id: Int,
    @SerialName("started_at") val startedAt: String? = null,
    val sport: String? = null,
    val title: String? = null,
    @SerialName("distance_meters") val distanceMeters: Double? = null,
    @SerialName("duration_seconds") val durationSeconds: Double? = null,
    @SerialName("days_from_injury") val daysFromInjury: Int? = null,
)

/** What the server accepts to open an injury. */
@Serializable
data class InjuryCreate(
    @SerialName("body_part") val bodyPart: String,
    @SerialName("injury_type") val injuryType: String,
    val severity: Int,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String? = null,
    val notes: String? = null,
)

/**
 * A partial update.
 *
 * Every field nullable and omitted when null — `encodeDefaults = false` in the
 * client's Json means an unset field is genuinely absent from the body rather
 * than sent as `null`, which for this endpoint is the difference between
 * "leave it alone" and "clear it".
 */
@Serializable
data class InjuryUpdate(
    @SerialName("body_part") val bodyPart: String? = null,
    @SerialName("injury_type") val injuryType: String? = null,
    val severity: Int? = null,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    val notes: String? = null,
)

// ── Medications ──────────────────────────────────────────────────────────────

@Serializable
data class Medication(
    val id: Int,
    val name: String = "",
    val dose: String? = null,
    @SerialName("dose_unit") val doseUnit: String? = null,
    val form: String? = null,
    val notes: String? = null,
    @SerialName("is_active") val isActive: Boolean = true,
    val schedules: List<MedicationSchedule> = emptyList(),
) {
    /** "500 mg", or just the name's worth of nothing when no dose is recorded. */
    val doseLabel: String?
        get() = listOfNotNull(dose?.takeIf { it.isNotBlank() }, doseUnit?.takeIf { it.isNotBlank() })
            .joinToString(" ")
            .takeIf { it.isNotBlank() }

    /** This medication as an editable payload — what an edit form starts from. */
    fun toInput(): MedicationIn = MedicationIn(
        name = name,
        dose = dose,
        doseUnit = doseUnit,
        form = form,
        notes = notes,
        isActive = isActive,
        schedules = schedules.map {
            MedicationScheduleIn(
                timeOfDay = it.timeOfDay,
                daysOfWeek = it.daysOfWeek,
                startDate = it.startDate,
                endDate = it.endDate,
                notify = it.notify,
                isAsNeeded = it.isAsNeeded,
            )
        },
    )
}

@Serializable
data class MedicationSchedule(
    val id: Int,
    /** `HH:MM`. */
    @SerialName("time_of_day") val timeOfDay: String = "",
    @SerialName("days_of_week") val daysOfWeek: List<Int>? = null,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    val notify: Boolean = false,
    @SerialName("is_as_needed") val isAsNeeded: Boolean = false,
)

/**
 * A medication as the server accepts it, schedules and all.
 *
 * One payload for create *and* update, because the server's PATCH replaces the
 * schedule list wholesale rather than merging it — a partial update type here
 * would imply a partial update the endpoint does not perform.
 *
 * Separate from [Medication] because that one carries server-allocated ids on
 * every schedule, and sending those back on a create would be inventing rows
 * the server has not made yet.
 */
@Serializable
data class MedicationIn(
    val name: String,
    val dose: String? = null,
    @SerialName("dose_unit") val doseUnit: String? = null,
    val form: String? = null,
    val notes: String? = null,
    @SerialName("is_active") val isActive: Boolean = true,
    val schedules: List<MedicationScheduleIn> = emptyList(),
)

/**
 * One time of day a medication is due.
 *
 * [daysOfWeek] is null for "every day" and otherwise a list of day numbers with
 * **Sunday as 0** — the server's convention, taken from JavaScript's
 * `Date.getDay()` and not from `java.time.DayOfWeek`, which starts on Monday
 * at 1. Getting that wrong shifts every reminder by a day, so the conversion
 * happens once, where the phone builds the list.
 */
@Serializable
data class MedicationScheduleIn(
    /** `HH:MM`, in the wearer's own time. */
    @SerialName("time_of_day") val timeOfDay: String,
    @SerialName("days_of_week") val daysOfWeek: List<Int>? = null,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    val notify: Boolean = false,
    @SerialName("is_as_needed") val isAsNeeded: Boolean = false,
)

/** One dose taken, skipped, or otherwise accounted for. */
@Serializable
data class MedicationLog(
    val id: Int,
    @SerialName("medication_id") val medicationId: Int,
    @SerialName("schedule_id") val scheduleId: Int? = null,
    /** `taken`, `skipped`, … — the server owns the vocabulary. */
    val status: String = "",
    @SerialName("scheduled_for") val scheduledFor: String? = null,
    @SerialName("logged_at") val loggedAt: String = "",
    val notes: String? = null,
)

@Serializable
data class MedicationLogCreate(
    @SerialName("medication_id") val medicationId: Int,
    @SerialName("schedule_id") val scheduleId: Int? = null,
    val status: String = STATUS_TAKEN,
    @SerialName("scheduled_for") val scheduledFor: String? = null,
    val notes: String? = null,
) {
    companion object {
        const val STATUS_TAKEN = "taken"
        const val STATUS_SKIPPED = "skipped"

        /** A dose taken outside any schedule — the "as needed" case. */
        const val STATUS_AS_NEEDED = "as_needed"
    }
}

// ── Meals ────────────────────────────────────────────────────────────────────

/** A saved meal — a template the user can log again without retyping macros. */
@Serializable
data class Meal(
    val id: Int,
    val name: String = "",
    val calories: Int = 0,
    @SerialName("protein_g") val proteinG: Double? = null,
    @SerialName("carbs_g") val carbsG: Double? = null,
    @SerialName("fat_g") val fatG: Double? = null,
    val notes: String? = null,
)

/** A meal actually eaten, at a time. */
@Serializable
data class MealLog(
    val id: Int,
    @SerialName("meal_id") val mealId: Int? = null,
    val name: String = "",
    val calories: Int = 0,
    @SerialName("protein_g") val proteinG: Double? = null,
    @SerialName("carbs_g") val carbsG: Double? = null,
    @SerialName("fat_g") val fatG: Double? = null,
    @SerialName("logged_at") val loggedAt: String = "",
    val notes: String? = null,
)

@Serializable
data class MealLogCreate(
    @SerialName("meal_id") val mealId: Int? = null,
    val name: String,
    val calories: Int? = null,
    @SerialName("protein_g") val proteinG: Double? = null,
    @SerialName("carbs_g") val carbsG: Double? = null,
    @SerialName("fat_g") val fatG: Double? = null,
    @SerialName("logged_at") val loggedAt: String? = null,
    val notes: String? = null,
)

/**
 * A day's correction to what the watch reported.
 *
 * Only three fields, and that is the server's shape rather than a subset: these
 * are the values a watch cannot know. Weight comes off a scale the watch has
 * never met, and hydration and calories-in are things only the athlete can say.
 */
@Serializable
data class DailyMetricPatch(
    @SerialName("weight_kg") val weightKg: Double? = null,
    @SerialName("hydration_ml") val hydrationMl: Int? = null,
    @SerialName("calories_in") val caloriesIn: Int? = null,
)


// ── Sleep detail ─────────────────────────────────────────────────────────────

/**
 * One span of a night at one stage.
 *
 * [level] is the watch's own vocabulary — `deep`, `light`, `rem`, `awake`,
 * `unmeasurable` — lowercased by the server and otherwise passed through. A
 * level nobody here recognises is drawn in the neutral colour rather than
 * dropped: a gap in the middle of a night reads as a bug, and the shape of the
 * night is the entire point of this data.
 */
@Serializable
data class SleepStage(
    val start: String,
    val end: String,
    val level: String = "",
    val seconds: Int = 0,
)

/**
 * One night, minute by minute.
 *
 * [stages] is empty for any night recorded before the server began keeping the
 * timeline, and that is a normal answer rather than an error — the totals on
 * the day's [DailyMetricFull] are still there, and the summary view is what a
 * client should draw when this comes back empty.
 */
@Serializable
data class SleepNight(
    val date: String,
    val stages: List<SleepStage> = emptyList(),
)

// ── Stress detail ────────────────────────────────────────────────────────────

/**
 * One day of stress, reading by reading.
 *
 * ## Why this is not a field on the day
 *
 * A day's `avg_stress_level` is one number for a quantity that moves all day.
 * A calm morning and a shattering afternoon average out to the same 40 as a
 * flat, mediocre day, and those are not the same day — the watch itself draws
 * the curve, and a history chart with one point per day cannot answer the
 * question anybody opens it with.
 *
 * The curve is also five hundred readings a day, which is two orders of
 * magnitude more than the row it belongs to. Folding it into `/metrics/daily`
 * would pay that cost on every load of the Health page for a chart nobody has
 * opened, so it is fetched separately and only for the short windows where a
 * day is still a distinguishable part of the picture.
 *
 * [points] is `[minute of local day, level]` — a pair rather than an object
 * because at this many readings the field names would be most of the payload.
 */
@Serializable
data class StressDay(
    val date: String,
    val points: List<List<Int>> = emptyList(),
) {
    /**
     * The readings in a shape a chart can use, malformed pairs dropped.
     *
     * The wire form is two anonymous numbers and nothing enforces that it stays
     * two; a short pair should cost its own reading rather than throwing in a
     * draw phase.
     */
    val samples: List<StressSample>
        get() = points.mapNotNull { pair ->
            if (pair.size < 2) null else StressSample(pair[0], pair[1])
        }
}

/** One stress reading: minutes since local midnight, and the level 0–100. */
data class StressSample(val minute: Int, val level: Int)
