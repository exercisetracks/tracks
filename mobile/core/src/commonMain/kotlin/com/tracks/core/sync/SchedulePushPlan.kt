// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.sync

import com.tracks.core.api.PlannedWorkout
import com.tracks.core.fit.ScheduleFit
import com.tracks.core.fit.WorkoutFit
import com.tracks.core.fit.daysFromCivil
import com.tracks.core.fit.vdotToPaces

/**
 * The training calendar, assembled on the phone.
 *
 * The on-device half of the server's `_schedule_bundle_for_user`, computed
 * against the phone's own cached plan instead of Postgres — the same move
 * [coursesToPush] and [waypointPushPlan] already make for the map library, and
 * for the same reason: a push that needs the server is a push that does not
 * happen on a hillside.
 *
 * ## Why the whole set, every time
 *
 * The watch binds a calendar entry to a workout only when the two arrive in
 * the *same* batch. A schedule naming a file delivered in an earlier session
 * resolves to nothing, and the calendar stays empty while the workouts sit
 * there listed. So this returns the schedule together with every workout it
 * names, whether or not the watch is believed to hold them already — which is
 * exactly what Garmin Connect does, and looks wasteful right up until you
 * watch the watch.
 */

/** One file on its way to the watch. */
class WatchPushFile(
    /** What the server calls it when told the push happened: `workout`, … */
    val type: String,
    val id: Int?,
    val filename: String,
    val folder: String,
    val bytes: ByteArray,
    /** Waypoints only — the whole set one file covers. */
    val ids: List<Int>? = null,
)

/**
 * A schedule and the workouts it names, to be pushed as one batch.
 *
 * Not a data class: it holds a [ByteArray] per file, and the generated
 * `equals` would compare those by identity — quietly reporting two identical
 * bundles as different, which is the sort of thing that only shows up in a
 * test that then gets rewritten to match.
 */
class WatchPushBundle(
    val schedule: WatchPushFile,
    val workouts: List<WatchPushFile>,
    /** How many workouts the calendar places. */
    val count: Int,
)

/**
 * What the phone needs, beyond the plan itself, to put coaching targets on a
 * workout it builds.
 *
 * Cached rather than computed: [vdot] comes from the plan, the rest from user
 * settings, and all of it changes rarely enough that the last value seen while
 * online is the right answer offline. With [paceCoaching] off — or with the
 * relevant number missing — steps are emitted with open targets, which is what
 * the watch shows for a user who has not asked to be paced.
 */
@kotlinx.serialization.Serializable
data class CoachingContext(
    val paceCoaching: Boolean = false,
    /** The plan's own VDOT; running pace zones derive from it. */
    val vdot: Double? = null,
    /** Threshold heart rate and functional threshold power, for the bike. */
    val lthr: Int? = null,
    val ftp: Int? = null,
    /** The goal's name, and its event date. Both name the plan on the watch. */
    val planName: String = "Training Plan",
    val planEnd: String? = null,
)

/** Where a workout file lands on the watch. See `AGENTS.md` on the batch rule. */
const val NEW_FILES_FOLDER = "GARMIN/NewFiles"

/** How far ahead a calendar reaches, matching the server's `_SYNC_DAYS_AHEAD`. */
const val SCHEDULE_DAYS_AHEAD = 4

/**
 * The workouts a calendar should carry: dated from [today] to [daysAhead] days
 * later, rest days and finished workouts excluded, in date order.
 *
 * A rest day is left out rather than sent as an empty workout — the watch has
 * nothing to show for one, and the server's own selection drops them too.
 *
 * A finished workout is left out for a reason the watch makes expensive: it
 * cannot be removed over Bluetooth (see docs/garmin-ble-protocol.md), so every
 * file sent stays there until a cable or Garmin Connect clears it, and the
 * watch refuses new ones past its workout limit. Re-sending today's completed
 * session on each calendar change is a file that can only ever be dead weight.
 * The server reaches the same answer by queueing completed workouts for
 * deletion (`_delete_items_for_user`), which is also why its calendar never
 * carries them.
 */
fun workoutsForSchedule(
    workouts: List<PlannedWorkout>,
    today: String,
    daysAhead: Int = SCHEDULE_DAYS_AHEAD,
): List<PlannedWorkout> {
    val cutoff = civilFromDays(daysFromCivil(today) + daysAhead)
    // ISO dates sort chronologically as strings, which is the whole reason the
    // cache keeps them in that form.
    return workouts
        .filter { it.scheduledDate >= today && it.scheduledDate <= cutoff }
        .filter { it.workoutType != "rest" }
        .filter { !it.isComplete }
        .sortedBy { it.scheduledDate }
}

/**
 * The number a workout is known by on the watch.
 *
 * Not always its id. A workout written on a phone with no signal carries a
 * temporary **negative** id until its create reaches the server, and both FIT
 * encoders clamp a serial with `maxOf(1, id)` — so every offline-authored
 * workout would arrive as serial 1, collide with each other and with the real
 * workout 1, and the calendar would bind several entries to one file.
 *
 * So a local id is folded into a high range instead. Server ids are small and
 * ascending, this range is far above anything they will reach, and the mapping
 * is stable for as long as the local id is.
 *
 * When the create finally lands the workout gets its real id, so its serial and
 * filename change and the bundle is pushed once more. That is correct rather
 * than wasteful: the file genuinely is a different file now.
 */
internal fun watchSerial(id: Int): Int =
    if (id < 0) LOCAL_SERIAL_BASE - id else maxOf(1, id)

/** Well clear of any id the server will hand out. */
private const val LOCAL_SERIAL_BASE = 1_000_000_000

/** `WKT_20260901_412.fit`, as the server names them. */
fun workoutFilename(workout: PlannedWorkout): String =
    "WKT_${workout.scheduledDate.replace("-", "")}_${watchSerial(workout.id)}.fit"

/**
 * Build the whole batch, or null when there is nothing scheduled.
 *
 * [fitTimeCreated] hands back the `file_id.time_created` for a workout, and
 * **must be stable across runs**: it is half the key the watch matches a
 * calendar entry to a workout file by, and it is also what makes the bundle's
 * bytes — and so its fingerprint — the same on a sync that changed nothing. A
 * fresh timestamp each time would rebuild the calendar every sync, and since
 * that batch import reboots the watch, it would reboot the watch every sync.
 *
 * A workout whose steps cannot be encoded is left out of *both* halves rather
 * than only the file half: a schedule naming a file that is never sent is a
 * calendar with a hole in it, and the bundle stops being self-consistent.
 */
fun buildScheduleBundle(
    workouts: List<PlannedWorkout>,
    today: String,
    context: CoachingContext,
    fitTimeCreated: (Int) -> Long,
    nowMillis: Long,
    daysAhead: Int = SCHEDULE_DAYS_AHEAD,
): WatchPushBundle? {
    val scheduled = workoutsForSchedule(workouts, today, daysAhead)
    if (scheduled.isEmpty()) return null

    val files = mutableListOf<WatchPushFile>()
    val entries = mutableListOf<ScheduleFit.Entry>()

    for (workout in scheduled) {
        val timeCreated = fitTimeCreated(workout.id)
        val bytes = runCatching { encodeWorkout(workout, context, timeCreated) }.getOrNull()
            ?: continue
        files += WatchPushFile(
            type = "workout",
            // Null for a workout the server has not seen: there is nothing for
            // it to mark as uploaded, and sending a negative id would have it
            // hunting for a row that does not exist.
            id = workout.id.takeIf { it > 0 },
            filename = workoutFilename(workout),
            folder = NEW_FILES_FOLDER,
            bytes = bytes,
        )
        entries += ScheduleFit.Entry(
            scheduledDate = workout.scheduledDate,
            workoutId = watchSerial(workout.id),
            timeCreatedMillis = timeCreated,
        )
    }

    val schedule = ScheduleFit.encode(
        entries = entries,
        planName = context.planName,
        planEnd = context.planEnd,
        nowMillis = nowMillis,
    ) ?: return null

    return WatchPushBundle(
        schedule = WatchPushFile(
            type = "schedule",
            id = null,
            filename = "SCHEDULE.fit",
            folder = NEW_FILES_FOLDER,
            bytes = schedule,
        ),
        workouts = files,
        count = entries.size,
    )
}

/**
 * One workout's FIT, routed the way the server routes it.
 *
 * Strength, mobility and flexibility go to the dedicated encoder; everything
 * else is an endurance workout. Note that only the first of those carries the
 * description — the server does not pass one to the endurance encoder, and a
 * phone that did would produce a different file for the same workout.
 */
private fun encodeWorkout(
    workout: PlannedWorkout,
    context: CoachingContext,
    timeCreatedMillis: Long,
): ByteArray {
    val name = workout.title.ifBlank { "Workout" }
    if (workout.workoutType in STRENGTH_ROUTED) {
        return WorkoutFit.strength(
            name = name,
            exercises = workout.steps,
            workoutId = watchSerial(workout.id),
            timeCreatedMillis = timeCreatedMillis,
            workoutType = workout.workoutType,
            description = workout.description,
        )
    }

    val sport = workout.sport.ifBlank { "running" }.lowercase()
    val family = enduranceFamily(sport)
    val coaching = context.paceCoaching
    val paces = if (coaching && family == "running" && context.vdot != null) {
        vdotToPaces(context.vdot)
    } else {
        null
    }
    val cycling = family == "mountain_biking" || family == "cycling"
    return WorkoutFit.endurance(
        name = name,
        sport = sport,
        planSteps = workout.steps,
        workoutId = watchSerial(workout.id),
        timeCreatedMillis = timeCreatedMillis,
        paceCoaching = coaching,
        paces = paces,
        lthr = if (coaching && cycling) context.lthr else null,
        ftp = if (coaching && cycling) context.ftp else null,
    )
}

private val STRENGTH_ROUTED = setOf("strength", "mobility", "flexibility")

private fun enduranceFamily(sport: String): String = when (sport) {
    "mountain_biking", "trail_biking" -> "mountain_biking"
    "running" -> "running"
    "cycling", "road_biking", "gravel_cycling" -> "cycling"
    else -> "generic"
}

/**
 * The inverse of [daysFromCivil]: an ISO `yyyy-mm-dd` from a day number.
 *
 * Here rather than in a date library for the same reason as its counterpart —
 * `core` targets iOS as well, and this is the only calendar arithmetic shared
 * code needs.
 */
internal fun civilFromDays(days: Long): String {
    val shifted = days + 719_468
    val era = (if (shifted >= 0) shifted else shifted - 146_096) / 146_097
    val dayOfEra = shifted - era * 146_097
    val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146_096) / 365
    val year = yearOfEra + era * 400
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val monthShifted = (5 * dayOfYear + 2) / 153
    val day = dayOfYear - (153 * monthShifted + 2) / 5 + 1
    val month = if (monthShifted < 10) monthShifted + 3 else monthShifted - 9
    val calendarYear = if (month <= 2) year + 1 else year
    return buildString {
        append(calendarYear.toString().padStart(4, '0'))
        append('-')
        append(month.toString().padStart(2, '0'))
        append('-')
        append(day.toString().padStart(2, '0'))
    }
}


/**
 * The coaching context a workout file's targets are built from, read from the
 * phone's own replica.
 *
 * Deliberately a pure function of synced rows rather than a server fetch with
 * a cache: the bytes of every workout file depend on it, and a context that
 * differed with and without signal would change those bytes, which changes
 * the schedule's digest, which resends it — and a schedule landing in
 * `GARMIN/NewFiles` reboots the watch. Read from rows every replica shares, it
 * is the same answer online and off, here and on the web.
 *
 * The goal chosen mirrors the server's `_plan_identity` — the newest active
 * one — so the plan named on the watch is the plan named in the browser.
 */
suspend fun localCoachingContext(sources: com.tracks.core.local.LocalSources): CoachingContext {
    val goal = sources.goals().filter { it.isActive }.maxByOrNull { it.id }
    val thresholds = sources.importThresholds()
    val vdot = goal?.uid?.let { goalUid ->
        sources.replica.rows("plan").firstOrNull { (it.fields["goal_uid"] as? kotlinx.serialization.json.JsonPrimitive)?.content == goalUid }
            ?.let { (it.fields["vdot"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() }
    }
    return CoachingContext(
        paceCoaching = sources.setting("pace_coaching") == "true",
        vdot = vdot,
        lthr = thresholds.thresholdHr?.toInt(),
        ftp = thresholds.ftp?.toInt(),
        planName = goal?.planName ?: "Training Plan",
        planEnd = goal?.planEnd,
    )
}
