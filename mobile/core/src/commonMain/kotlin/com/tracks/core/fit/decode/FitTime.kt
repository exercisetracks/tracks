// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.fit.decode

/**
 * Instants and dates as the server's parsers see them, without java.time.
 *
 * `commonMain` has to compile for iOS, so neither `java.time` nor a date
 * library that is not already a dependency is available. What the parsers need
 * is small — a UTC instant, its calendar date, and Python's `isoformat()` text
 * for both — and all of it is integer arithmetic on the proleptic Gregorian
 * calendar, which is what Python's `datetime` uses too.
 */

private const val MICROS_PER_SECOND = 1_000_000L
private const val SECONDS_PER_DAY = 86_400L

/**
 * A UTC instant, as fitdecode's timezone-aware `datetime`.
 *
 * Microsecond resolution because that is Python's: a `date_time` value that
 * arrives as a float (it can, through a scaled component) keeps its fraction
 * exactly as far as `datetime.fromtimestamp` would.
 */
data class FitDateTime(val epochMicros: Long) : Comparable<FitDateTime> {

    /** Whole seconds, floored — Python's `int(dt.timestamp())` for these instants. */
    val epochSeconds: Long get() = epochMicros.floorDiv(MICROS_PER_SECOND)

    /** The UTC calendar date, as `dt.date()` on an aware UTC datetime. */
    val utcDate: CivilDate get() = CivilDate.fromEpochDay(epochSeconds.floorDiv(SECONDS_PER_DAY))

    override fun compareTo(other: FitDateTime): Int = epochMicros.compareTo(other.epochMicros)

    /** `(self - other).total_seconds()` — exact here, as it is in Python for whole microseconds. */
    fun secondsSince(other: FitDateTime): Double = (epochMicros - other.epochMicros) / 1_000_000.0

    /**
     * Python's `isoformat()` for an aware UTC datetime:
     * `2024-05-01T01:32:00+00:00`, with `.ffffff` only when there is a fraction.
     */
    fun isoformat(): String {
        val seconds = epochSeconds
        val micros = epochMicros - seconds * MICROS_PER_SECOND
        val date = CivilDate.fromEpochDay(seconds.floorDiv(SECONDS_PER_DAY))
        val secOfDay = seconds.mod(SECONDS_PER_DAY)
        val h = secOfDay / 3600
        val m = (secOfDay % 3600) / 60
        val s = secOfDay % 60
        val time = "${pad2(h)}:${pad2(m)}:${pad2(s)}" +
            if (micros != 0L) "." + micros.toString().padStart(6, '0') else ""
        return "${date.isoformat()}T$time+00:00"
    }

    override fun toString(): String = isoformat()

    companion object {
        fun ofEpochSeconds(seconds: Long): FitDateTime = FitDateTime(seconds * MICROS_PER_SECOND)

        /**
         * `datetime.fromtimestamp(t, timezone.utc)` for a float `t`: the
         * fraction rounds half-to-even to a microsecond, as CPython's
         * `_fromtimestamp` does.
         */
        fun ofEpochSeconds(seconds: Double): FitDateTime {
            // modf: the integral part and a fraction of the same sign.
            val whole = kotlin.math.truncate(seconds)
            val frac = seconds - whole
            var t = whole.toLong()
            // kotlin.math.round rounds half to even, as Python's round does.
            var us = kotlin.math.round(frac * 1e6).toLong()
            if (us >= MICROS_PER_SECOND) {
                t += 1
                us -= MICROS_PER_SECOND
            } else if (us < 0) {
                t -= 1
                us += MICROS_PER_SECOND
            }
            return FitDateTime(t * MICROS_PER_SECOND + us)
        }
    }
}

/** A calendar date, as Python's `datetime.date`. */
data class CivilDate(val year: Int, val month: Int, val day: Int) : Comparable<CivilDate> {

    override fun compareTo(other: CivilDate): Int = epochDay.compareTo(other.epochDay)

    /** `YYYY-MM-DD`, as `date.isoformat()`. */
    fun isoformat(): String = "${year.toString().padStart(4, '0')}-${pad2(month.toLong())}-${pad2(day.toLong())}"

    override fun toString(): String = isoformat()

    /** Days since 1970-01-01. */
    val epochDay: Long
        get() {
            // Howard Hinnant's days_from_civil.
            val y = if (month <= 2) year - 1 else year
            val era = y.floorDiv(400)
            val yoe = y - era * 400
            val mp = (month + 9) % 12
            val doy = (153 * mp + 2) / 5 + day - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era.toLong() * 146_097 + doe - 719_468
        }

    companion object {
        /** Howard Hinnant's civil_from_days. */
        fun fromEpochDay(days: Long): CivilDate {
            val z = days + 719_468
            val era = z.floorDiv(146_097L)
            val doe = z - era * 146_097
            val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
            val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
            val y = (yoe + era * 400).toInt() + if (m <= 2) 1 else 0
            return CivilDate(y, m, d)
        }
    }
}

/** A time of day, as fitdecode renders `localtime_into_day` (`datetime.time`). */
data class FitTimeOfDay(val hour: Int, val minute: Int, val second: Int, val micro: Int = 0) {
    fun isoformat(): String =
        "${pad2(hour.toLong())}:${pad2(minute.toLong())}:${pad2(second.toLong())}" +
            if (micro != 0) "." + micro.toString().padStart(6, '0') else ""

    override fun toString(): String = isoformat()

    companion object {
        /** `datetime.time.max`, which fitdecode substitutes for 86400 and above. */
        val MAX = FitTimeOfDay(23, 59, 59, 999_999)
    }
}

private fun pad2(v: Long): String = v.toString().padStart(2, '0')
