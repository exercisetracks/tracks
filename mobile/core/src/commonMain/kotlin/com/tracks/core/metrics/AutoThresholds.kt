// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.metrics

import com.tracks.core.parse.PyMath

/**
 * Auto max HR, LTHR and FTP from activity history — a port of
 * `backend/app/calculators/user_stats.py`, whose docstrings explain the method.
 */
object AutoThresholds {

    private const val MAX_CANDIDATES = 20
    private const val MIN_POINTS = 30
    const val WINDOW_SECONDS = 1200

    /** An activity with the fields the threshold search reads. */
    data class Candidate(
        val id: Long,
        val sport: String?,
        val durationSeconds: Long?,
        val avgHeartRate: Int?,
        val maxHeartRate: Int?,
        val avgPower: Int?,
        val normalizedPower: Int?,
    )

    fun maxHr(activities: List<Candidate>): Int? =
        activities.mapNotNull { it.maxHeartRate }.maxOrNull()?.takeIf { it != 0 }

    /** The SQL filter in `_calc_threshold_hr`. */
    fun qualifiesForThresholdHr(a: Candidate): Boolean =
        a.avgHeartRate != null && a.maxHeartRate != null && (a.durationSeconds ?: 0) > 1800 &&
            (a.sport == "running" || a.sport == "cycling")

    /** The SQL filter in `_calc_ftp`. */
    fun qualifiesForFtp(a: Candidate): Boolean =
        (a.durationSeconds ?: 0) > 1800 && a.sport == "cycling" &&
            ((a.avgPower ?: 0) > 0 || (a.normalizedPower ?: 0) > 0)

    /** Best rolling average over (epoch seconds, value) points in time order. */
    fun bestRollingAvg(points: List<Pair<Double, Double>>, windowSeconds: Int = WINDOW_SECONDS): Double? {
        if (points.size < MIN_POINTS) return null
        var best = 0.0
        var j = 0
        var sum = 0.0
        for (i in points.indices) {
            sum += points[i].second
            while (points[i].first - points[j].first > windowSeconds) {
                sum -= points[j].second
                j++
            }
            val avg = sum / (i - j + 1)
            if (avg > best) best = avg
        }
        return best.takeIf { it > 0 }
    }

    /** LTHR from qualifying activities, given each one's best 20-min rolling HR. */
    fun thresholdHr(qualifying: List<Candidate>, rollingHr: (Candidate) -> Double?): Long? {
        if (qualifying.isEmpty()) return null
        val median = PyMath.median(qualifying.map { it.avgHeartRate!!.toDouble() })
        val hard = qualifying.filter { it.avgHeartRate!! >= median }
        val spread = { a: Candidate -> a.maxHeartRate!! - a.avgHeartRate!! }
        val steady = hard.filter { spread(it) < 25 }.ifEmpty { hard.filter { spread(it) < 35 } }.ifEmpty { hard }
        var best = 0.0
        for (a in steady.sortedByDescending { it.avgHeartRate!! }.take(MAX_CANDIDATES)) {
            val v = rollingHr(a)
            if (v != null && v != 0.0 && v > best) best = v
        }
        return if (best > 0) PyMath.roundToLong(best) else null
    }

    /** FTP from qualifying rides, given each one's best 20-min rolling power. */
    fun ftp(qualifying: List<Candidate>, rollingPower: (Candidate) -> Double?): Long? {
        if (qualifying.isEmpty()) return null
        val sorted = qualifying.sortedByDescending {
            it.normalizedPower?.takeIf { p -> p != 0 } ?: it.avgPower?.takeIf { p -> p != 0 } ?: 0
        }
        var best = 0.0
        for (a in sorted.take(MAX_CANDIDATES)) {
            val v = rollingPower(a)
            if (v != null && v != 0.0 && v > best) best = v
        }
        return if (best > 0) PyMath.roundToLong(best * 0.95) else null
    }
}
