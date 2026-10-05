// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.meds

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.tracks.core.api.Medication
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Reminders that fire whether or not the app is running.
 *
 * ## Why alarms rather than the web app's approach
 *
 * The browser's reminder engine is a 30-second timer that only ticks while the
 * medication tab is open. That is honest about what a web page can do and
 * useless as an actual reminder — nobody leaves the tab open until eight in the
 * morning. A phone can do the thing properly, and a medication reminder that
 * only works while you are already looking at the app is worse than none,
 * because you would stop checking.
 *
 * ## Why inexact alarms
 *
 * `setAndAllowWhileIdle` wakes the device out of Doze but is not to-the-minute:
 * Android delivers it within a few minutes and rate-limits an app to roughly one
 * every nine minutes while idle. Exactness is available — `SCHEDULE_EXACT_ALARM`
 * or `USE_EXACT_ALARM` — and is not worth it here. Both are permissions the user
 * has to be asked for or that carry store-policy weight, and "take your tablet"
 * is not an instruction that degrades at four minutes late. Waking a sleeping
 * phone at all is the hard part, and this does that.
 *
 * ## Why each alarm arms the next one
 *
 * The obvious design schedules the next fortnight at once and re-arms whenever
 * the app opens. That fails exactly when it matters — someone who does not open
 * Tracks for three weeks is someone whose reminders quietly stopped. So an
 * alarm's last act is to schedule its own next occurrence, from recurrence rules
 * carried in its own extras. The chain needs no server, no database read, and no
 * app launch to keep going, and [rearmAll] exists only to recover it after a
 * reboot, which is the one event that clears the alarm table.
 */
object MedicationReminders {

    const val CHANNEL_ID = "medication_reminders"

    /**
     * Notification ids are derived from the schedule id, so a second reminder
     * for the same dose replaces the first rather than stacking. Offset well
     * clear of the sync notifications' 1001/1002.
     */
    private const val ID_BASE = 20_000

    const val EXTRA_SPEC = "spec"
    const val EXTRA_AT = "at"
    const val EXTRA_STATUS = "status"

    private const val PREFS = "medication_reminders"
    private const val KEY_SPECS = "specs"

    /**
     * Everything an alarm needs to announce itself and to arm its successor.
     *
     * Flat and small on purpose: this rides in the alarm's own extras, which
     * live in the system's alarm table across reboots.
     */
    data class Spec(
        val medicationId: Int,
        val scheduleId: Int,
        val name: String,
        val dose: String? = null,
        /** `HH:MM`. */
        val time: String,
        /** Sunday-based, null for every day — see [occursOn]. */
        val days: List<Int>? = null,
        val startDate: String? = null,
        val endDate: String? = null,
    )

    fun notificationId(scheduleId: Int): Int = ID_BASE + scheduleId

    /**
     * Idempotent by construction, like the sync channel. High importance
     * deliberately: this one *is* a demand for attention, which is the entire
     * difference between it and a sync notice.
     */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(com.tracks.app.R.string.medication_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(com.tracks.app.R.string.medication_channel_description)
            }
        )
    }

    /**
     * Re-arm everything from the current medication list.
     *
     * Cancels the alarms for schedules that have gone — an edit that removes a
     * time, or a medication deleted — before setting the ones that remain.
     * Without that, turning a reminder off would leave it firing forever, since
     * an alarm lives in the system and not in the app.
     */
    fun reschedule(context: Context, medications: List<Medication>) {
        ensureChannel(context)
        val wanted = specsFrom(medications)
        val previous = loadSpecs(context)
        val goneIds = previous.map { it.scheduleId }.toSet() - wanted.map { it.scheduleId }.toSet()
        goneIds.forEach { cancel(context, it) }
        saveSpecs(context, wanted)
        wanted.forEach { arm(context, it, LocalDateTime.now()) }
    }

    /** After a reboot: the alarm table is empty, the intent list is not. */
    fun rearmAll(context: Context) {
        ensureChannel(context)
        loadSpecs(context).forEach { arm(context, it, LocalDateTime.now()) }
    }

    /** The schedules that asked to be reminded about. */
    fun specsFrom(medications: List<Medication>): List<Spec> =
        medications
            .filter { it.isActive }
            .flatMap { medication ->
                medication.schedules
                    .filter { it.notify && !it.isAsNeeded }
                    .map { schedule ->
                        Spec(
                            medicationId = medication.id,
                            scheduleId = schedule.id,
                            name = medication.name,
                            dose = medication.doseLabel,
                            time = schedule.timeOfDay,
                            days = schedule.daysOfWeek,
                            startDate = schedule.startDate,
                            endDate = schedule.endDate,
                        )
                    }
            }

    /**
     * The next moment this schedule comes round after [from].
     *
     * Null when it never does again — an end date in the past, or a schedule
     * with an empty day list, which would otherwise spin this loop forever.
     */
    fun nextOccurrence(spec: Spec, from: LocalDateTime): LocalDateTime? {
        val time = parseTime(spec.time)
        val end = spec.endDate?.let(::parseDate)
        var day: LocalDate = from.toLocalDate()
        repeat(SEARCH_DAYS) {
            val at = LocalDateTime.of(day, time)
            if (at.isAfter(from) && fires(spec, day) && (end == null || !day.isAfter(end))) {
                return at
            }
            if (end != null && day.isAfter(end)) return null
            day = day.plusDays(1)
        }
        return null
    }

    private fun fires(spec: Spec, date: LocalDate): Boolean {
        spec.startDate?.let(::parseDate)?.let { if (date.isBefore(it)) return false }
        val days = spec.days
        if (days.isNullOrEmpty()) return true
        return (date.dayOfWeek.value % 7) in days
    }

    /** Set the one alarm for [spec]'s next occurrence after [from]. */
    fun arm(context: Context, spec: Spec, from: LocalDateTime) {
        val next = nextOccurrence(spec, from) ?: run { cancel(context, spec.scheduleId); return }
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(context, spec, at))
    }

    fun cancel(context: Context, scheduleId: Int) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, MedicationAlarmReceiver::class.java)
            .setData(alarmUri(scheduleId))
        val pending = PendingIntent.getBroadcast(
            context,
            scheduleId,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        pending?.let {
            alarms.cancel(it)
            it.cancel()
        }
    }

    private fun alarmIntent(context: Context, spec: Spec, at: Long): PendingIntent {
        val intent = Intent(context, MedicationAlarmReceiver::class.java)
            // A distinct data URI per schedule, because PendingIntent equality
            // ignores extras: without it every schedule would resolve to the
            // same pending intent and each new alarm would overwrite the last.
            .setData(alarmUri(spec.scheduleId))
            .putExtra(EXTRA_SPEC, encodeSpec(spec))
            .putExtra(EXTRA_AT, at)
        return PendingIntent.getBroadcast(
            context,
            spec.scheduleId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun alarmUri(scheduleId: Int) =
        android.net.Uri.parse("tracks://medication/$scheduleId")

    /**
     * A spec as one line of text.
     *
     * Hand-rolled rather than serialized: `:app` does not apply the
     * kotlinx.serialization plugin — `:core` is where the wire types live — and
     * adding a compiler plugin to a module for one eight-field record would be a
     * build-wide change to avoid writing a `joinToString`. Unit separator as the
     * delimiter, because a medication name can contain almost anything else.
     */
    fun encodeSpec(spec: Spec): String = listOf(
        spec.medicationId.toString(),
        spec.scheduleId.toString(),
        spec.name,
        spec.dose.orEmpty(),
        spec.time,
        spec.days?.joinToString(",").orEmpty(),
        spec.startDate.orEmpty(),
        spec.endDate.orEmpty(),
    ).joinToString(FIELD)

    fun decodeSpec(raw: String?): Spec? {
        val parts = raw?.split(FIELD) ?: return null
        if (parts.size < 8) return null
        return Spec(
            medicationId = parts[0].toIntOrNull() ?: return null,
            scheduleId = parts[1].toIntOrNull() ?: return null,
            name = parts[2],
            dose = parts[3].takeIf { it.isNotEmpty() },
            time = parts[4],
            days = parts[5].takeIf { it.isNotEmpty() }
                ?.split(",")?.mapNotNull(String::toIntOrNull),
            startDate = parts[6].takeIf { it.isNotEmpty() },
            endDate = parts[7].takeIf { it.isNotEmpty() },
        )
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The specs, in plain preferences.
     *
     * A medication name is health data and the rest of this app keeps that in an
     * encrypted store — but an alarm's own extras hold the same name in the
     * system's alarm table regardless, and a notification puts it on the lock
     * screen by design. There is no version of a working reminder that keeps the
     * name secret, so this does not pretend otherwise. Nothing here is a dose
     * *history*; that stays in the encrypted mirror.
     */
    private fun loadSpecs(context: Context): List<Spec> =
        prefs(context).getStringSet(KEY_SPECS, emptySet())
            .orEmpty()
            .mapNotNull { decodeSpec(it) }

    private fun saveSpecs(context: Context, specs: List<Spec>) {
        prefs(context).edit()
            .putStringSet(KEY_SPECS, specs.map(::encodeSpec).toSet())
            .apply()
    }

    /** Unit separator — a control character no medication name carries. */
    private const val FIELD = "\u001f"

    /**
     * How far ahead to look for the next occurrence.
     *
     * A year, so that a schedule for one weekday still finds its next day and a
     * schedule whose start date is months away still arms — bounded so a
     * malformed rule cannot loop forever.
     */
    private const val SEARCH_DAYS = 366
}
