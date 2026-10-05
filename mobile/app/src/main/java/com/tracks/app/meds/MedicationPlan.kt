// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.meds

import com.tracks.core.api.Medication
import com.tracks.core.api.MedicationLog
import com.tracks.core.api.MedicationLogCreate
import com.tracks.core.api.MedicationSchedule
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Which doses are due, worked out on the phone.
 *
 * ## Why not just ask the server
 *
 * There is a `/medications/due` endpoint and the web app uses it. Two reasons
 * this does not.
 *
 * The first is that it needs a network, and a medication list that goes blank
 * without one is the single worst thing on this screen to lose. Everything else
 * here degrades to "no chart"; this degrades to a missed dose.
 *
 * The second is that the endpoint builds each dose's timestamp as
 * `datetime.combine(today, time_of_day, tzinfo=utc)` — it reads the schedule's
 * wall-clock time as UTC. For anyone not on UTC that puts "08:00" at the wrong
 * moment, and the further from Greenwich the wronger it gets: a dose set for 8am
 * shows as overdue at 1am in Edmonton. The wearer's own zone is the only sensible
 * reading of "eight in the morning", and the phone is the device that knows it.
 *
 * The same routine drives the reminders, so what the screen says is due and what
 * the notification fires for cannot drift apart. See [MedicationReminders].
 */

/** One scheduled dose on one day, and what has become of it. */
data class DoseSlot(
    val medication: Medication,
    val scheduleId: Int,
    /** `HH:MM`, as set. */
    val timeOfDay: String,
    val at: LocalDateTime,
    /** `taken`, `skipped`, or null when nothing has been recorded yet. */
    val status: String?,
    val notify: Boolean,
) {
    val taken: Boolean get() = status == MedicationLogCreate.STATUS_TAKEN
    val skipped: Boolean get() = status == MedicationLogCreate.STATUS_SKIPPED
    fun overdue(now: LocalDateTime): Boolean = status == null && now.isAfter(at)
}

/**
 * Every scheduled dose for [date], in the order they come round.
 *
 * As-needed schedules are deliberately absent — they are not *due*, they are
 * available, and mixing them in would make a day look busier than it is. See
 * [asNeededMedications].
 */
fun dosesOn(
    date: LocalDate,
    medications: List<Medication>,
    log: List<MedicationLog>,
    zone: ZoneId = ZoneId.systemDefault(),
): List<DoseSlot> =
    medications
        .filter { it.isActive }
        .flatMap { medication ->
            medication.schedules
                .filter { occursOn(it, date) }
                .map { schedule ->
                    DoseSlot(
                        medication = medication,
                        scheduleId = schedule.id,
                        timeOfDay = schedule.timeOfDay,
                        at = LocalDateTime.of(date, parseTime(schedule.timeOfDay)),
                        status = statusOf(schedule.id, date, log, zone),
                        notify = schedule.notify,
                    )
                }
        }
        .sortedBy { it.at }

/** Medications with an as-needed schedule — a button rather than a due time. */
fun asNeededMedications(medications: List<Medication>): List<Medication> =
    medications.filter { med -> med.isActive && med.schedules.any { it.isAsNeeded } }

/**
 * Whether a schedule fires on a given day.
 *
 * [MedicationSchedule.daysOfWeek] counts **Sunday as 0**, which is JavaScript's
 * convention and the server's, and is not `java.time`'s — where Monday is 1 and
 * Sunday is 7. The modulo below is the whole of that translation, and getting it
 * wrong would shift every reminder by exactly one day, which is the kind of bug
 * that looks like it works.
 */
fun occursOn(schedule: MedicationSchedule, date: LocalDate): Boolean {
    if (schedule.isAsNeeded) return false
    schedule.startDate?.let { start ->
        parseDate(start)?.let { if (date.isBefore(it)) return false }
    }
    schedule.endDate?.let { end ->
        parseDate(end)?.let { if (date.isAfter(it)) return false }
    }
    val days = schedule.daysOfWeek
    if (days.isNullOrEmpty()) return true
    return (date.dayOfWeek.value % 7) in days
}

/**
 * What was recorded for this schedule on this day, if anything.
 *
 * Matched on the day the dose *was for* where the server said so, and otherwise
 * on the day it was logged. Both are read in the phone's zone: a dose ticked off
 * at 9pm in Edmonton is stored as tomorrow in UTC, and comparing ISO prefixes
 * would file it under the wrong day and re-offer a dose already taken.
 */
private fun statusOf(
    scheduleId: Int,
    date: LocalDate,
    log: List<MedicationLog>,
    zone: ZoneId,
): String? = log
    .filter { it.scheduleId == scheduleId }
    .firstOrNull { entry ->
        val stamp = entry.scheduledFor ?: entry.loggedAt
        localDateOf(stamp, zone) == date
    }
    ?.status

/**
 * An ISO timestamp as a day in [zone].
 *
 * The server sends offset-aware stamps; the outbox's optimistic rows are naive
 * local ones. Both turn up in the same list, so both are accepted, and a naive
 * stamp is taken at face value — it was written by this phone, in this zone.
 */
internal fun localDateOf(text: String, zone: ZoneId = ZoneId.systemDefault()): LocalDate? =
    runCatching { OffsetDateTime.parse(text).atZoneSameInstant(zone).toLocalDate() }
        .recoverCatching { LocalDateTime.parse(text).toLocalDate() }
        .recoverCatching { LocalDate.parse(text.take(10)) }
        .getOrNull()

internal fun parseTime(text: String): LocalTime =
    runCatching { LocalTime.parse(text.take(5)) }.getOrDefault(LocalTime.of(8, 0))

internal fun parseDate(text: String): LocalDate? =
    runCatching { LocalDate.parse(text.take(10)) }.getOrNull()
