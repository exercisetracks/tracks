// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.race

import com.tracks.core.parse.PyMath
import kotlin.math.abs

/**
 * A triathlon's courses: the whole course split at T1/T2 into its legs, and
 * any leg's own file in place of its stretch. The server's
 * calculators/race_predictor/triathlon.py, which explains the split; held to
 * it by spec/fixtures/race_plan.json (SportPlanFixtureTest).
 */
object TriCourse {

    val LEGS = listOf("swim", "bike", "run")
    private const val WINDOW = 0.3
    private const val ZONE_M = 500.0

    /** A course point: lat, lon and its elevation (null when the file had none). */
    data class PathPoint(val lat: Double, val lon: Double, val ele: Double?)

    data class LegFile(val segments: List<Segment>, val path: List<PathPoint>)

    /** One leg's course, and where it came from: "own", "whole", or null for none. */
    data class LegCourse(val segments: List<Segment>?, val path: List<PathPoint>, val source: String?)

    data class Courses(val legs: Map<String, LegCourse>, val transitionsM: List<Double>?)

    /** Python's `sum()` (compensated), so a leg's length matches the server's to the bit. */
    fun totalM(segments: List<Segment>?): Double = PyMath.sum(segments.orEmpty().map { it.distanceM })

    private fun positions(path: List<PathPoint>, length: Double): List<Double> {
        val cum = mutableListOf(0.0)
        for (i in 1 until path.size) {
            cum += cum.last() + RacePredictor.haversineM(path[i - 1].lat, path[i - 1].lon, path[i].lat, path[i].lon)
        }
        val own = cum.last().takeIf { it != 0.0 } ?: 1.0
        return cum.map { it / own * length }
    }

    /** [T1, T2] in metres along the whole course. */
    fun autoTransitions(segments: List<Segment>, path: List<PathPoint>, splits: SportPlan.TriSplits): List<Double> {
        val length = totalM(segments)
        val nominal = listOf(splits.swimM, splits.swimM + splits.bikeM)
        if (path.size < 2 || length <= 0) return nominal.map { PyMath.round(minOf(it, length), 1) }
        val pos = positions(path, length)
        val out = mutableListOf<Double>()
        var zone = path[0]
        for ((i, n) in nominal.withIndex()) {
            val leg = if (i == 0) splits.swimM else splits.bikeM
            val window = maxOf(leg * WINDOW, 100.0)
            var best: Triple<Double, Double, PathPoint>? = null
            for ((p, m) in path.zip(pos)) {
                if (abs(m - n) > window) continue
                val d = RacePredictor.haversineM(p.lat, p.lon, zone.lat, zone.lon)
                if (best == null || d < best.first) best = Triple(d, m, p)
            }
            if (best != null && best.first <= ZONE_M) {
                out += PyMath.round(best.second, 1)
                zone = best.third
            } else {
                out += PyMath.round(minOf(n, length), 1)
            }
        }
        if (out[1] <= out[0]) out[1] = PyMath.round(minOf(out[0] + splits.bikeM, length), 1)
        return out
    }

    fun sliceSegments(segments: List<Segment>, startM: Double, endM: Double): List<Segment> {
        val out = mutableListOf<Segment>()
        var at = 0.0
        for (s in segments) {
            val d = s.distanceM
            val lo = maxOf(at, startM)
            val hi = minOf(at + d, endM)
            if (hi - lo > 0.5) {
                val share = if (d != 0.0) (hi - lo) / d else 0.0
                out += Segment(PyMath.round(hi - lo, 1), s.gradient, PyMath.round(s.elevationGainM * share, 1))
            }
            at += d
        }
        return out
    }

    fun slicePath(path: List<PathPoint>, length: Double, startM: Double, endM: Double): List<PathPoint> {
        if (path.isEmpty()) return emptyList()
        val pos = positions(path, length)
        return path.zip(pos).filter { (_, m) -> m >= startM - 1e-6 && m <= endM + 1e-6 }.map { it.first }
    }

    fun legCourses(
        segments: List<Segment>,
        path: List<PathPoint>,
        legFiles: Map<String, LegFile>,
        transitions: List<Double>?,
        splits: SportPlan.TriSplits,
    ): Courses {
        val whole = segments.isNotEmpty()
        val length = totalM(segments)
        val cuts = if (whole) {
            if (transitions != null && transitions.size == 2) transitions else autoTransitions(segments, path, splits)
        } else null
        val bounds = cuts?.let { mapOf("swim" to (0.0 to it[0]), "bike" to (it[0] to it[1]), "run" to (it[1] to length)) }
        val legs = LEGS.associateWith { leg ->
            val own = legFiles[leg]
            when {
                own != null && own.segments.isNotEmpty() -> LegCourse(own.segments, own.path, "own")
                bounds != null -> {
                    val (a, b) = bounds.getValue(leg)
                    LegCourse(sliceSegments(segments, a, b), slicePath(path, length, a, b), "whole")
                }
                else -> LegCourse(null, emptyList(), null)
            }
        }
        return Courses(legs, cuts)
    }
}
