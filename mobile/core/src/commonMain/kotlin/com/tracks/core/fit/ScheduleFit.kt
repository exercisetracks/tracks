// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.fit

/**
 * `Schedule.fit` — the file that puts workouts in the watch's training
 * calendar.
 *
 * A port of `generate_schedule_fit`, which is itself hand-rolled rather than
 * built through a FIT library, for a reason that carries over unchanged: the
 * file Garmin Connect actually sends contains **global message 137**, which no
 * public FIT profile describes, and two fields on `schedule` that no profile
 * describes either. An encoder that addresses fields by profile name cannot
 * write any of them.
 *
 * Every structural choice was read off a Connect upload captured over
 * Bluetooth — the protocol version, the big-endian records, the field order,
 * and the single local message type. None of it is derived from the FIT
 * specification, and none of it should be "tidied".
 *
 * ## Fields written without knowing what they mean
 *
 * Fields 7 and 8 of message 137, and field 10 of `schedule`, are written
 * because Connect writes them and the watch is the only thing that gets a
 * vote. Field 10 took values 0-4 across eighteen captured entries and was 0 on
 * all five Fridays, which reads like an intensity the plan assigns; nothing in
 * Tracks knows how to compute one, so it is always sent as 0.
 *
 * ## The batch rule
 *
 * This file names workouts by `(serial_number, time_created)`, and the watch
 * only binds an entry to a workout delivered in the **same** transfer batch.
 * Writing a schedule that names a file from an earlier session builds no
 * calendar at all. See `AGENTS.md`; the encoder cannot enforce it, but nothing
 * that calls this should send a schedule on its own.
 */
object ScheduleFit {

    private const val MANUFACTURER_GARMIN = 1
    private const val PRODUCT_CONNECT = 65534
    private const val FILE_TYPE_SCHEDULES = 7

    /**
     * Fixed constants in Connect's output — nothing about the watch or the
     * user. The file the watch acted on carried exactly these.
     */
    private const val SCHEDULE_SERIAL = 1L
    private const val SCHEDULE_CREATOR_VERSION = 26
    private const val SCHEDULE_TYPE_WORKOUT = 0

    /**
     * The training plan's `message_index` in message 137, and the value each
     * entry carries in field 7 to point back at it. Connect used 1 for both;
     * whether field 7 really is that link is unproven, but they matched.
     */
    private const val PLAN_MESSAGE_INDEX = 1

    private const val PROTOCOL_VERSION = 0x10
    private const val PROFILE_VERSION = 21213

    private const val FIT_EPOCH_OFFSET = 631_065_600L

    private const val MESSAGE_FILE_ID = 0
    private const val MESSAGE_FILE_CREATOR = 49
    private const val MESSAGE_TRAINING_PLAN = 137
    private const val MESSAGE_SCHEDULE = 28

    /** The plan name's wire capacity, before its NUL terminator. */
    private const val PLAN_NAME_BYTES = 63

    /**
     * One calendar entry.
     *
     * [timeCreatedMillis] **must** equal the workout file's own
     * `file_id.time_created`. It is half of the key the watch matches an entry
     * to a workout by, so a workout re-encoded with a fresh timestamp leaves
     * its calendar entry pointing at nothing.
     */
    data class Entry(
        /** ISO `yyyy-mm-dd`. */
        val scheduledDate: String,
        val workoutId: Int,
        val timeCreatedMillis: Long,
    )

    /**
     * Build the schedule, or null when there is nothing to schedule.
     *
     * [nowMillis] is the file's own creation time. It is a parameter rather
     * than a clock read so the output is reproducible — which is what lets
     * this be tested against captured reference bytes at all.
     */
    fun encode(
        entries: List<Entry>,
        planName: String = "Training Plan",
        planStart: String? = null,
        planEnd: String? = null,
        nowMillis: Long,
    ): ByteArray? {
        if (entries.isEmpty()) return null

        val dates = entries.map { it.scheduledDate }.sorted()
        val start = planStart ?: dates.first()
        val finish = planEnd ?: dates.last()

        val writer = FitWriter(
            PROTOCOL_VERSION, PROFILE_VERSION, bigEndian = true, localTypes = 1,
        )

        writer.write(
            MESSAGE_FILE_ID,
            listOf(
                fitEnum(0, FILE_TYPE_SCHEDULES),
                fitUint16(1, MANUFACTURER_GARMIN),
                fitUint16(2, PRODUCT_CONNECT),
                fitUint32(4, nowMillis / 1000 - FIT_EPOCH_OFFSET),
                fitUint32z(3, SCHEDULE_SERIAL),
                fitUint16(5, 1),
            ),
        )

        writer.write(
            MESSAGE_FILE_CREATOR,
            listOf(fitUint16(0, SCHEDULE_CREATOR_VERSION), fitUint8(1, 0)),
        )

        writer.write(
            MESSAGE_TRAINING_PLAN,
            listOf(
                fitUint16(254, PLAN_MESSAGE_INDEX),
                planNameField(planName),
                fitUint32(1, noonUtcFitSeconds(start)),
                fitUint32(2, noonUtcFitSeconds(finish)),
                // Written twice, as Connect writes it. Which of the two the
                // watch reads is unknown.
                fitUint32(3, noonUtcFitSeconds(finish)),
                fitEnum(4, 1),
                fitEnum(5, 0),
                fitEnum(6, 1),
                fitUint8(7, minOf(255, maxOf(0, entries.size - 1))),
                fitUint8(8, 60),
            ),
        )

        for (entry in entries) {
            writer.write(
                MESSAGE_SCHEDULE,
                listOf(
                    fitUint16(0, MANUFACTURER_GARMIN),
                    fitUint16(1, PRODUCT_CONNECT),
                    fitUint32z(2, maxOf(1L, entry.workoutId.toLong())),
                    fitUint32(3, entry.timeCreatedMillis / 1000 - FIT_EPOCH_OFFSET),
                    fitEnum(5, SCHEDULE_TYPE_WORKOUT),
                    fitUint32(6, noonUtcFitSeconds(entry.scheduledDate)),
                    fitEnum(4, 0),                       // completed
                    fitUint8(10, 0),                     // see the class doc
                    fitUint16(7, PLAN_MESSAGE_INDEX),    // -> the plan above
                ),
            )
        }

        return writer.finish()
    }

    /**
     * The plan name, cut to 63 raw bytes.
     *
     * Deliberately a byte-wise cut with no regard for character boundaries,
     * because that is what the reference encoder does and the point of this
     * port is to produce the same file. A name long enough to be cut mid
     * character yields a malformed string field — a real if cosmetic bug, and
     * one that has to be fixed on both sides at once or the two encoders stop
     * agreeing.
     */
    private fun planNameField(planName: String): FitField {
        val name = planName.ifEmpty { "Training Plan" }
        val bytes = name.encodeToByteArray()
        val cut = if (bytes.size <= PLAN_NAME_BYTES) bytes else bytes.copyOf(PLAN_NAME_BYTES)
        return fitStringBytes(0, cut + 0)
    }

    /**
     * A date as FIT seconds at **noon UTC**.
     *
     * The watch reads this as a local wall-clock time, so noon UTC lands at
     * noon local everywhere. Connect uses noon rather than midnight because
     * midnight UTC falls into the previous day west of Greenwich, and the
     * today's-workout widget then misses the entry entirely.
     */
    private fun noonUtcFitSeconds(isoDate: String): Long {
        val seconds = daysFromCivil(isoDate) * 86_400L + 12 * 3600L
        return maxOf(0L, seconds - FIT_EPOCH_OFFSET)
    }
}

/**
 * Days from 1970-01-01 for an ISO `yyyy-mm-dd` date.
 *
 * Written out rather than taken from a date library because `core` has none —
 * it targets iOS as well as the JVM, and this is the only calendar arithmetic
 * shared code needs. The algorithm shifts the year to start in March so that
 * the leap day lands at the end of a cycle and needs no special case.
 */
internal fun daysFromCivil(isoDate: String): Long {
    val year = isoDate.substring(0, 4).toLong()
    val month = isoDate.substring(5, 7).toLong()
    val day = isoDate.substring(8, 10).toLong()

    val shiftedYear = if (month <= 2) year - 1 else year
    val era = (if (shiftedYear >= 0) shiftedYear else shiftedYear - 399) / 400
    val yearOfEra = shiftedYear - era * 400
    val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146_097 + dayOfEra - 719_468
}
