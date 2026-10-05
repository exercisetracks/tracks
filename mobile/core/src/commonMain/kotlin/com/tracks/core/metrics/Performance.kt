// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.metrics

import com.tracks.core.parse.PyMath
import kotlin.math.pow

/**
 * Power and pace curves and race predictions — the pure halves of
 * `backend/app/api/metrics/performance.py`. The per-activity bests themselves
 * come from the parser port; these take the best per duration/distance.
 */
object Performance {

    data class PowerPoint(val durationSeconds: Int, val avgWatts: Double)
    data class PacePoint(val distanceMeters: Int, val avgSpeedMps: Double, val pacePerKm: String)
    data class RacePrediction(
        val distanceMeters: Int,
        val distanceLabel: String,
        val predictedTimeSeconds: Long,
        val formattedTime: String,
        val referenceDistanceMeters: Int,
        val isActual: Boolean,
    )

    /** Best watts per duration from every PowerBest row in range. */
    fun powerCurve(bests: List<Pair<Int, Double>>): List<PowerPoint> =
        bests.groupBy({ it.first }, { it.second }).toSortedMap()
            .map { (dur, w) -> PowerPoint(dur, PyMath.round(w.max(), 1)) }

    /** Best speed per distance from every PaceBest row in range. */
    fun bestSpeeds(bests: List<Pair<Int, Double>>): Map<Int, Double> =
        bests.groupBy({ it.first }, { it.second }).mapValues { it.value.max() }.toSortedMap()

    fun paceCurve(bests: List<Pair<Int, Double>>): List<PacePoint> =
        bestSpeeds(bests).map { (d, s) -> PacePoint(d, PyMath.round(s, 4), formatPace(s)) }

    private val RACE_DISTANCES = listOf(
        1000 to "1 km", 1609 to "1 mile", 5000 to "5 km", 10000 to "10 km",
        21097 to "Half Marathon", 42195 to "Marathon",
    )

    fun racePredictions(bestSpeeds: Map<Int, Double>): List<RacePrediction> =
        RACE_DISTANCES.mapNotNull { (target, label) ->
            val actual = bestSpeeds[target]
            if (actual != null) {
                val secs = PyMath.roundToLong(target / actual)
                RacePrediction(target, label, secs, formatRaceTime(secs), target, true)
            } else {
                val ref = bestSpeeds.keys.filter { it < target }.maxOrNull() ?: return@mapNotNull null
                val t1 = ref / bestSpeeds.getValue(ref)
                val secs = PyMath.roundToLong(t1 * (target.toDouble() / ref).pow(1.06))
                RacePrediction(target, label, secs, formatRaceTime(secs), ref, false)
            }
        }

    /** m/s as `m:ss` per km, with Python's float `//` and `%`. */
    internal fun formatPace(speed: Double): String {
        if (speed <= 0) return "--:--"
        val pace = 1000 / speed
        val (div, mod) = PyMath.divmod(pace, 60.0)
        return "${div.toLong()}:${mod.toLong().toString().padStart(2, '0')}"
    }

    private fun formatRaceTime(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        val mm = m.toString().padStart(2, '0')
        val ss = s.toString().padStart(2, '0')
        return if (h > 0) "$h:$mm:$ss" else "$m:$ss"
    }

}
