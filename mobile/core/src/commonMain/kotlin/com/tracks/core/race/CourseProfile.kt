// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.race

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The race course's profile: elevation by distance with the plan's target
 * alongside, for the plot under the race plan's map. The web's twin is
 * frontend/src/components/raceplan/courseProfile.js; the two are one
 * algorithm, tested with the same numbers (CourseProfileTest and
 * CourseProfile.test.js).
 *
 * The target drawn is the sport's own coaching number — pace for running,
 * power for cycling and a triathlon's bike laps, the HR ceiling for mountain
 * biking, whose laps carry no watts. An open-water swim has no plot. The
 * line is smooth through each lap's middle rather than stepped: the user
 * chose it as the easier read against a hill; the lap table says exactly
 * where each target starts.
 */
object CourseProfile {

    enum class Metric { PACE, POWER, HR }

    data class Point(val m: Double, val ele: Double, val target: Double?, val lat: Double, val lon: Double)

    private const val R = 6_371_000.0
    private const val MAX_POINTS = 400

    /** Which target the plot draws, or null for none. A sport missing its own number falls back to pace. */
    fun metric(sport: String?, laps: List<RaceLap>): Metric? {
        if (sport == "swimming") return null
        if (sport == "mountain_biking" && laps.any { it.hrCeiling != null }) return Metric.HR
        if ((sport == "cycling" || sport == "triathlon") && laps.any { it.targetWatts != null }) return Metric.POWER
        return if (laps.isNotEmpty()) Metric.PACE else null
    }

    private fun value(lap: RaceLap, metric: Metric): Double? = when (metric) {
        Metric.PACE -> lap.targetSecPerKm
        Metric.POWER -> lap.targetWatts?.toDouble()
        Metric.HR -> lap.hrCeiling?.toDouble()
    }

    private fun haversine(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val dLat = (b.first - a.first) * PI / 180
        val dLon = (b.second - a.second) * PI / 180
        val h = sin(dLat / 2).let { it * it } +
            cos(a.first * PI / 180) * cos(b.first * PI / 180) * sin(dLon / 2).let { it * it }
        return R * 2 * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Each lap's target at its middle, with the plan's distance scaled to the course's length. */
    private fun anchors(laps: List<RaceLap>, metric: Metric, courseM: Double): List<Pair<Double, Double>> {
        val total = laps.sumOf { it.distanceM.toDouble() }
        if (total <= 0) return emptyList()
        var start = 0.0
        return buildList {
            for (l in laps) {
                val d = l.distanceM.toDouble()
                val v = value(l, metric)
                if (v != null && d > 0) add((start + d / 2) / total * courseM to v)
                start += d
            }
        }
    }

    private fun interpolate(a: List<Pair<Double, Double>>, x: Double): Double? {
        if (a.isEmpty()) return null
        if (x <= a.first().first) return a.first().second
        for (i in 1 until a.size) {
            val (x1, y1) = a[i]
            if (x <= x1) {
                val (x0, y0) = a[i - 1]
                return y0 + (y1 - y0) * (x - x0) / (x1 - x0).let { if (it == 0.0) 1.0 else it }
            }
        }
        return a.last().second
    }

    /**
     * The plot's points, or null when the course carries no elevation —
     * without the hills there is nothing to read the target against.
     * [elevations] runs alongside [path], null where a point has none.
     */
    fun points(path: List<Pair<Double, Double>>, elevations: List<Double?>, laps: List<RaceLap>, metric: Metric?): List<Point>? {
        if (path.size < 2 || elevations.none { it != null }) return null
        val cum = DoubleArray(path.size)
        for (i in 1 until path.size) cum[i] = cum[i - 1] + haversine(path[i - 1], path[i])
        val courseM = cum.last()
        val a = if (metric != null) anchors(laps, metric, courseM) else emptyList()
        val step = max(1, ceil(path.size / MAX_POINTS.toDouble()).toInt())
        val out = mutableListOf<Point>()
        fun add(i: Int) {
            val e = elevations.getOrNull(i) ?: return
            out += Point(cum[i], e, interpolate(a, cum[i]), path[i].first, path[i].second)
        }
        for (i in path.indices step step) add(i)
        // The finish is always drawn, whatever the sampling step skipped.
        if (out.lastOrNull()?.m != courseM) add(path.lastIndex)
        return out.takeIf { it.size > 1 }
    }
}
