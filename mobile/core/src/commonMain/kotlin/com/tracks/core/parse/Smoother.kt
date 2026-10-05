// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.parse

import kotlin.math.abs

/**
 * Track-point smoothing, ported from `backend/app/parsers/smoother.py`.
 *
 * Spike removal (twice, so neighbouring glitches that shield each other are
 * both caught), then a 7-point rolling median. Timestamps and coordinates are
 * never touched. The thresholds and window are the server's and must stay so:
 * the summary figures (average speed, average heart rate) are recomputed from
 * the smoothed track, so a different smoother is a different average.
 *
 * The LTTB downsampler in the same Python module is not ported — it runs when
 * the server *serves* a track, not when it parses one.
 */
internal object Smoother {

    // Max deviation from the mean of the two neighbours, per field. In this
    // order — each pass walks the fields in sequence and edits in place, so
    // the order is part of the result.
    private val spikeDelta = listOf(
        "speed" to 3.0,
        "heart_rate" to 15.0,
        "altitude" to 20.0,
        "cadence" to 15.0,
        "power" to 300.0,
    )
    private const val SPIKE_PASSES = 2
    private const val ROLLING_WINDOW = 7
    private val rollingFields = listOf("heart_rate", "altitude", "cadence", "power", "grit", "flow")

    fun smooth(points: List<MutableMap<String, Any?>>): List<MutableMap<String, Any?>> {
        if (points.size < 3) return points
        val out = points.map { LinkedHashMap(it) }
        repeat(SPIKE_PASSES) {
            for ((field, maxDelta) in spikeDelta) removeSpikes(out, field, maxDelta)
        }
        for (field in rollingFields) rollingMedian(out, field, ROLLING_WINDOW)
        return out
    }

    private fun removeSpikes(points: List<MutableMap<String, Any?>>, field: String, maxDelta: Double) {
        for (i in 1 until points.size - 1) {
            val v = points[i][field] ?: continue
            val prev = points[i - 1][field] ?: continue
            val next = points[i + 1][field] ?: continue
            val interp = (Py.num(prev) + Py.num(next)) / 2.0
            if (abs(Py.num(v) - interp) > maxDelta) {
                points[i][field] = PyMath.round(interp, 4)
            }
        }
    }

    private fun rollingMedian(points: List<MutableMap<String, Any?>>, field: String, window: Int) {
        val n = points.size
        val half = window / 2
        // Read from a snapshot, so the window never sees values this pass wrote.
        val orig = points.map { it[field] }
        for (i in 0 until n) {
            val start = maxOf(0, i - half)
            val end = minOf(n, i + half + 1)
            val vals = (start until end).mapNotNull { orig[it] }
            if (vals.isNotEmpty()) points[i][field] = median(vals)
        }
    }

    /**
     * `statistics.median`: the middle element itself for an odd count — which
     * keeps an integer an integer — and the mean of the middle two otherwise.
     */
    private fun median(vals: List<Any>): Any {
        if (vals.size == 1) return vals[0]
        val sorted = vals.sortedBy { Py.num(it) }
        val n = sorted.size
        if (n % 2 == 1) return sorted[n / 2]
        return (Py.num(sorted[n / 2 - 1]) + Py.num(sorted[n / 2])) / 2
    }

    /**
     * `recompute_summary`: averages and maxima from the smoothed track,
     * overwriting the session's only where the track has any values at all.
     */
    fun recomputeSummary(activity: Map<String, Any?>, points: List<Map<String, Any?>>): MutableMap<String, Any?> {
        fun values(key: String) = points.mapNotNull { it[key] }
        val speeds = values("speed")
        val hrs = values("heart_rate")
        val cadences = values("cadence")
        val powers = values("power")
        val out = LinkedHashMap(activity)
        if (speeds.isNotEmpty()) {
            out["avg_speed"] = PyMath.mean(speeds.map(Py::num))
            out["max_speed"] = pyMax(speeds)
        }
        if (hrs.isNotEmpty()) {
            out["avg_heart_rate"] = PyMath.roundToLong(PyMath.mean(hrs.map(Py::num)))
            out["max_heart_rate"] = PyMath.roundToLong(Py.num(pyMax(hrs)))
        }
        if (cadences.isNotEmpty()) out["avg_cadence"] = PyMath.roundToLong(PyMath.mean(cadences.map(Py::num)))
        if (powers.isNotEmpty()) out["avg_power"] = PyMath.roundToLong(PyMath.mean(powers.map(Py::num)))
        return out
    }

    /** `max()`: the first of the largest. */
    private fun pyMax(values: List<Any>): Any {
        var best = values[0]
        for (v in values.drop(1)) if (Py.num(v) > Py.num(best)) best = v
        return best
    }
}
