// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.parse

import com.tracks.core.fit.decode.FitDateTime

/**
 * The per-activity figures the server computes at import, ported from
 * `backend/app/calculators/activity_metrics.py`: efficiency factor, aerobic
 * decoupling, and the power and pace curves.
 *
 * The rolling-window sums are deliberately naive running doubles — `+=` one
 * sample, `-=` another — because that is what the Python does, and a
 * compensated or exact sum would disagree with it in the last bits of a
 * best-effort figure that is then rounded to one decimal. Rounding hides most
 * such drift, not all of it.
 */
internal object ActivityMetrics {

    private val powerDurations = listOf(1, 5, 10, 30, 60, 120, 300, 600, 1200, 1800, 3600, 5400)
    private val paceDistances = listOf(400, 1000, 1609, 5000, 10000, 21097, 42195)

    val cyclingSports = setOf(
        "cycling", "mountain_biking", "road_biking", "gravel_cycling",
        "virtual_cycling", "indoor_cycling", "e_biking",
    )
    val runningSports = setOf(
        "running", "trail_running", "treadmill_running", "street_running",
        "track_running", "obstacle_racing",
    )

    fun efficiencyFactor(sport: Any?, avgHr: Any?, normalizedPower: Any?, avgSpeed: Any?): Double? {
        if (!Py.truthy(avgHr) || Py.num(avgHr) <= 0) return null
        val sportLower = sportLower(sport)
        if (sportLower in cyclingSports) {
            if (Py.truthy(normalizedPower) && Py.num(normalizedPower) > 0) {
                return PyMath.round(Py.num(normalizedPower) / Py.num(avgHr), 3)
            }
            return null
        }
        if (sportLower in runningSports) {
            if (Py.truthy(avgSpeed) && Py.num(avgSpeed) > 0) {
                return PyMath.round(Py.num(avgSpeed) / Py.num(avgHr), 4)
            }
            return null
        }
        return null
    }

    fun aerobicDecoupling(sport: Any?, points: List<Map<String, Any?>>): Double? {
        if (points.size < 60) return null
        val sportLower = sportLower(sport)
        val isCycling = sportLower in cyclingSports
        val isRunning = sportLower in runningSports
        if (!(isCycling || isRunning)) return null

        val mid = points.size / 2
        fun efHalf(half: List<Map<String, Any?>>): Double? {
            val hrs = half.mapNotNull { p -> p["heart_rate"]?.takeIf(Py::truthy) }
            if (hrs.isEmpty()) return null
            val avgHr = PyMath.mean(hrs.map(Py::num))
            if (avgHr <= 0) return null
            return if (isCycling) {
                val powers = half.mapNotNull { p -> p["power"]?.takeIf(Py::truthy) }
                if (powers.isEmpty()) null else PyMath.mean(powers.map(Py::num)) / avgHr
            } else {
                val speeds = half.mapNotNull { p ->
                    p["speed"]?.takeIf { Py.truthy(it) && Py.num(it) > 0 }
                }
                if (speeds.isEmpty()) null else PyMath.mean(speeds.map(Py::num)) / avgHr
            }
        }

        val ef1 = efHalf(points.subList(0, mid)) ?: return null
        val ef2 = efHalf(points.subList(mid, points.size)) ?: return null
        if (ef1 == 0.0) return null
        return PyMath.round((ef1 - ef2) / ef1 * 100, 2)
    }

    /** `{duration_seconds: best average watts}` for the standard durations the ride is long enough for. */
    fun powerCurve(points: List<Map<String, Any?>>): Map<Long, Double> {
        val withPower = points.filter { it["power"] != null }
        if (withPower.isEmpty()) return emptyMap()
        val powers = withPower.map { Py.num(it["power"]) }
        val t0 = instant(withPower[0]["recorded_at"])
        val timestamps = withPower.map { instant(it["recorded_at"]).secondsSince(t0) }
        val total = timestamps.last()
        val result = LinkedHashMap<Long, Double>()
        for (dur in powerDurations) {
            if (dur > total) break
            bestRollingPower(powers, dur, timestamps)?.let { result[dur.toLong()] = it }
        }
        return result
    }

    private fun bestRollingPower(powers: List<Double>, windowSeconds: Int, timestamps: List<Double>): Double? {
        if (powers.size < 2 || windowSeconds <= 0) return null
        var best: Double? = null
        var left = 0
        var windowSum = 0.0
        for (right in powers.indices) {
            windowSum += powers[right]
            while (timestamps[right] - timestamps[left] > windowSeconds && left < right) {
                windowSum -= powers[left]
                left += 1
            }
            val current = timestamps[right] - timestamps[left]
            if (current >= windowSeconds * 0.9) {
                val avg = windowSum / (right - left + 1)
                if (best == null || avg > best) best = avg
            }
        }
        return best?.let { PyMath.round(it, 1) }
    }

    /** `{distance_meters: best average speed}` for the standard distances the run covers. */
    fun paceCurve(points: List<Map<String, Any?>>): Map<Long, Double> {
        val pts = points.filter { it["speed"] != null && Py.num(it["speed"]) > 0 }
        if (pts.size < 2) return emptyMap()
        val speeds = pts.map { Py.num(it["speed"]) }
        val cum = ArrayList<Double>(pts.size)
        cum.add(0.0)
        for (i in 1 until pts.size) {
            val dt = instant(pts[i]["recorded_at"]).secondsSince(instant(pts[i - 1]["recorded_at"]))
            val seg = ((speeds[i] + speeds[i - 1]) / 2) * maxOf(dt, 0.0)
            cum.add(cum.last() + seg)
        }
        val total = cum.last()
        val result = LinkedHashMap<Long, Double>()
        for (dist in paceDistances) {
            if (dist > total) break
            bestRollingSpeed(speeds, cum, dist.toDouble())?.let { result[dist.toLong()] = it }
        }
        return result
    }

    private fun bestRollingSpeed(speeds: List<Double>, cum: List<Double>, target: Double): Double? {
        if (speeds.size < 2 || target <= 0) return null
        var best: Double? = null
        var left = 0
        var sum = speeds[0]
        for (right in 1 until speeds.size) {
            sum += speeds[right]
            while (cum[right] - cum[left] > target && left < right) {
                sum -= speeds[left]
                left += 1
            }
            val windowDist = cum[right] - cum[left]
            if (windowDist >= target * 0.9) {
                val avg = sum / (right - left + 1)
                if (best == null || avg > best) best = avg
            }
        }
        return best?.let { PyMath.round(it, 4) }
    }

    private fun sportLower(sport: Any?): String = if (Py.truthy(sport)) Py.str(sport).lowercase() else ""

    private fun instant(v: Any?): FitDateTime =
        v as? FitDateTime ?: throw PyError("TypeError: unsupported operand type(s) for -: ${Py.str(v)}")
}
