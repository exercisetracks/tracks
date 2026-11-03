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
 * The race's profile: the plan's target by distance — over the course's
 * elevation when one is loaded — and the HR ceiling where the plan sets one,
 * for the plot under the race plan's map. It replaced the lap table, which
 * ran to forty rows on a marathon. The web's twin is
 * frontend/src/components/raceplan/courseProfile.js; the two are one
 * algorithm, tested with the same numbers (CourseProfileTest and
 * CourseProfile.test.js).
 *
 * The target drawn is the sport's own coaching number — pace for running,
 * power for cycling and a triathlon's bike laps, the HR ceiling for mountain
 * biking, whose laps carry no watts. An open-water swim has no plot. Each
 * target is drawn as steps — flat for its lap, changing at its boundary, as
 * the watch coaches it. A smooth line through each lap's middle was tried
 * first and read as a target drifting along a climb. So a point is added at
 * every lap boundary, and each point carries its own lap's value.
 */
object CourseProfile {

    /** SWIM is a triathlon swim leg's pace, drawn per 100 m. */
    enum class Metric { PACE, SWIM, POWER, HR }

    /** One point: metres along the race, the lap there (for the readout's ground), and no map spot without a course. */
    data class Point(
        val m: Double,
        val ele: Double?,
        val target: Double?,
        val lat: Double?,
        val lon: Double?,
        val hr: Double? = null,
        val lap: RaceLap? = null,
    )

    private const val R = 6_371_000.0
    private const val MAX_POINTS = 400
    private const val LAP_SAMPLES = 200

    /** Which target the plot draws, or null for none. A sport missing its own number falls back to pace. */
    fun metric(sport: String?, laps: List<RaceLap>): Metric? {
        if (sport == "swimming") return null
        if (sport == "mountain_biking" && laps.any { it.hrCeiling != null }) return Metric.HR
        if ((sport == "cycling" || sport == "triathlon") && laps.any { it.targetWatts != null }) return Metric.POWER
        return if (laps.isNotEmpty()) Metric.PACE else null
    }

    private fun value(lap: RaceLap, metric: Metric): Double? = when (metric) {
        Metric.PACE, Metric.SWIM -> lap.targetSecPerKm
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

    /** The lap at each of [positions], laps laid end to end over [length] metres. */
    private fun lapsAt(laps: List<RaceLap>, positions: List<Double>, length: Double, startsAt: Boolean = false): List<RaceLap> {
        val total = laps.sumOf { it.distanceM.toDouble() }.takeIf { it > 0 } ?: 1.0
        val ends = mutableListOf<Double>()
        var run = 0.0
        for (l in laps) { run += l.distanceM / total * length; ends += run }
        var k = 0
        return positions.map { m ->
            while (k < laps.size - 1 && (if (startsAt) m >= ends[k] - 1e-6 else m > ends[k])) k++
            laps[k]
        }
    }

    /**
     * The plot's points — `courseProfile` in the web's courseProfile.js. With
     * a course carrying elevation, one per course point; without, the laps
     * alone, evenly sampled, with no elevation. [elevations] runs alongside
     * [path], null where a point has none. Null with neither course nor lap.
     */
    fun points(path: List<Pair<Double, Double>>, elevations: List<Double?>, laps: List<RaceLap>, metric: Metric?): List<Point>? {
        val withHr = laps.isNotEmpty() && metric != Metric.HR && laps.any { it.hrCeiling != null }
        var positions: List<Double>
        var eles: List<Double>? = null
        var coords: List<Pair<Double, Double>>? = null
        val length: Double
        if (path.size >= 2 && elevations.any { it != null }) {
            val cum = DoubleArray(path.size)
            for (i in 1 until path.size) cum[i] = cum[i - 1] + haversine(path[i - 1], path[i])
            length = cum.last()
            val step = max(1, ceil(path.size / MAX_POINTS.toDouble()).toInt())
            val idx = (path.indices step step).filter { elevations.getOrNull(it) != null }.toMutableList()
            // The finish is always drawn, whatever the sampling step skipped.
            if (idx.lastOrNull() != path.lastIndex && elevations.getOrNull(path.lastIndex) != null) idx += path.lastIndex
            positions = idx.map { cum[it] }
            eles = idx.map { elevations[it]!! }
            coords = idx.map { path[it] }
        } else if (laps.isNotEmpty()) {
            length = laps.sumOf { it.distanceM.toDouble() }
            if (length <= 0) return null
            positions = (0..LAP_SAMPLES).map { it.toDouble() / LAP_SAMPLES * length }
        } else {
            return null
        }
        if (laps.isNotEmpty()) {
            // A point at every lap boundary, so the step falls exactly there.
            val total = laps.sumOf { it.distanceM.toDouble() }.takeIf { it > 0 } ?: 1.0
            val pos = positions.toMutableList()
            val es = eles?.toMutableList()
            val cs = coords?.toMutableList()
            var run = 0.0
            for (l in laps.dropLast(1)) {
                run += l.distanceM / total * length
                val k = pos.indexOfFirst { it >= run }
                if (k <= 0 || pos[k] == run) continue
                val f = (run - pos[k - 1]) / (pos[k] - pos[k - 1])
                pos.add(k, run)
                es?.add(k, es[k - 1] + (es[k] - es[k - 1]) * f)
                cs?.add(k, (cs[k - 1].first + (cs[k].first - cs[k - 1].first) * f) to
                    (cs[k - 1].second + (cs[k].second - cs[k - 1].second) * f))
            }
            positions = pos; eles = es; coords = cs
        }
        // At a boundary the point belongs to the lap starting there.
        val at = if (laps.isNotEmpty()) lapsAt(laps, positions, length, startsAt = true) else null
        val out = positions.mapIndexed { i, m ->
            val lap = at?.get(i)
            Point(m, eles?.get(i), lap?.let { l -> metric?.let { value(l, it) } }, coords?.get(i)?.first, coords?.get(i)?.second,
                if (withHr) lap?.hrCeiling?.toDouble() else null, lap)
        }
        return out.takeIf { it.size > 1 }
    }
}
