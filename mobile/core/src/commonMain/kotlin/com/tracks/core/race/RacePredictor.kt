// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.race

import com.tracks.core.parse.PyMath
import com.tracks.core.plan.PlanBase
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The race predictor — a port of backend/app/calculators/race_predictor/,
 * held to it by spec/fixtures/race_predictor.json.
 *
 * Everything but two pieces: GPX parsing (`parse_gpx`, `extract_path_points`),
 * which leans on Python's XML parser and its exact failure behaviour, and the
 * race FIT encoder (`fit.py`). A course reaches this code as [Segment]s.
 *
 * Weather is an input, never a fetch: offline, a caller predicts with no
 * weather factor and says so, rather than this reaching for a network.
 */

/** ~100 m of course, as `parse_gpx` builds them. */
data class Segment(val distanceM: Double, val gradient: Double, val elevationGainM: Double = 0.0)

/** One lap of a race plan (running paces, or cycling power with derived paces). */
data class RaceLap(
    val lap: Int,
    val distanceM: Long,
    val targetSecPerKm: Double,
    val targetPace: String,
    val gradient: Double,
    val gradeMultiplier: Double,
    val gradeAdjSec: Double,
    val gradeAdjPace: String,
    val cumulativeKm: Double,
    val hrCeiling: Int?,
    val targetWatts: Int? = null,
    val targetWattsPctFtp: Int? = null,
)

data class WindExposure(val netHeadwindMps: Double, val courseNote: String?, val windFactor: Double)

data class CourseTotals(val distanceM: Long, val elevationGainM: Long, val elevationLossM: Long)

object RacePredictor {

    // ── formatting ───────────────────────────────────────────────────────────

    fun formatTime(seconds: Double): String {
        val s = PyMath.roundToLong(seconds)
        val h = s.floorDiv(3600L)
        val rem = s.mod(3600L)
        val m = rem / 60
        val sc = rem % 60
        return if (h != 0L) "$h:${pad(m)}:${pad(sc)}" else "$m:${pad(sc)}"
    }

    internal fun fmtPace(secPerKm: Double): String {
        val (q, r) = PyMath.divmod(secPerKm, 60.0)
        return "${q.toLong()}:${pad(r.toLong())}/km"
    }

    fun formatSwimPace(cssSecPer100m: Double, intensity: Double = 1.0): String {
        val (q, r) = PyMath.divmod(cssSecPer100m * intensity, 60.0)
        return "${q.toLong()}:${pad(r.toLong())}/100m"
    }

    private fun pad(v: Long) = v.toString().padStart(2, '0')

    // ── geo ──────────────────────────────────────────────────────────────────

    // CPython multiplies by a precomputed constant; (d * PI) / 180 differs in the last bit.
    private fun rad(d: Double) = d * (PI / 180.0)

    internal fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val p1 = rad(lat1)
        val p2 = rad(lat2)
        val dp = rad(lat2 - lat1)
        val dl = rad(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    internal fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = rad(lat1)
        val p2 = rad(lat2)
        val dl = rad(lon2 - lon1)
        val x = sin(dl) * cos(p2)
        val y = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return PyMath.divmod(atan2(x, y) * (180.0 / PI) + 360, 360.0).second
    }

    // ── grade (Minetti 2002) ─────────────────────────────────────────────────

    private const val FLAT_COST = 2.5

    private fun minettiCost(g0: Double): Double {
        val g = max(-0.45, min(0.45, g0))
        return 280.5 * g.pow(5) - 58.7 * g.pow(4) - 76.8 * g.pow(3) + 51.9 * g.pow(2) + 19.6 * g + FLAT_COST
    }

    fun gradeCostMultiplier(gradient: Double): Double {
        val g = max(-0.45, min(0.45, gradient))
        val cost = minettiCost(g)
        if (g >= 0) return min(3.0, max(0.5, cost / FLAT_COST))
        val benefit = 1.0 - cost / FLAT_COST
        var mult = 1.0 - benefit * 0.35
        if (g < -0.10) mult += abs(g - -0.10) * 2.50
        return max(0.70, mult)
    }

    fun gradeAdjustmentFactor(gradient: Double): Double = 1.0 / gradeCostMultiplier(gradient)

    // ── weather / wind ───────────────────────────────────────────────────────

    fun weatherSlowdownFactor(tempC: Double, humidityPct: Double, windMps: Double = 0.0, windAngleDeg: Double = 0.0): Double {
        val wbt = tempC - (1 - humidityPct / 100) * 5.0
        val heat = max(0.0, (max(wbt, tempC) - 10.0) * 0.003)
        val head = cos(rad(PyMath.divmod(windAngleDeg, 360.0).second))
        val wind = windMps * 0.004 * max(0.0, head * 0.3)
        return 1.0 + heat + wind
    }

    private fun weightedMeanHeadwind(points: List<Pair<Double, Double>>, windDirectionDeg: Double): Double? {
        var total = 0.0
        var weighted = 0.0
        for (i in 1 until points.size) {
            val (lat1, lon1) = points[i - 1]
            val (lat2, lon2) = points[i]
            val d = haversineM(lat1, lon1, lat2, lon2)
            if (d < 1.0) continue
            weighted += cos(rad(bearing(lat1, lon1, lat2, lon2) - windDirectionDeg)) * d
            total += d
        }
        return if (total == 0.0) null else weighted / total
    }

    private fun headwindNote(h: Double) = when {
        h > 0.50 -> "Mostly headwind"
        h > 0.15 -> "Headwind bias"
        h < -0.50 -> "Mostly tailwind"
        h < -0.15 -> "Tailwind bias"
        else -> "Mixed / crosswind"
    }

    private val CALM = WindExposure(0.0, null, 1.0)

    /** Running (linear Pugh 1971). [points] are (lat, lon) along the course. */
    fun windCourseExposure(points: List<Pair<Double, Double>>, windDirectionDeg: Double, windMps: Double): WindExposure {
        if (points.size < 2 || windMps <= 0) return CALM
        val mean = weightedMeanHeadwind(points, windDirectionDeg) ?: return CALM
        val net = mean * windMps
        val factor = if (net >= 0) 1.0 + net * 0.015 else max(0.98, 1.0 + net * 0.008)
        return WindExposure(PyMath.round(net, 2), headwindNote(mean), PyMath.round(factor, 4))
    }

    /** Cycling (v² aerodynamic drag). */
    fun cyclingWindCourseExposure(
        points: List<Pair<Double, Double>>,
        windDirectionDeg: Double,
        windMps: Double,
        riderSpeedMps: Double = 10.0,
    ): WindExposure {
        if (points.size < 2 || windMps <= 0) return CALM
        val mean = weightedMeanHeadwind(points, windDirectionDeg) ?: return CALM
        val net = mean * windMps
        val v = riderSpeedMps
        val powerRatio = (v + net).pow(2) * v / v.pow(3)
        val speedRatio = 1.0 / powerRatio.pow(1.0 / 3)
        val factor = if (net >= 0) min(max(1.0, 1.0 / speedRatio), 1.30) else max(0.92, 1.0 / speedRatio)
        return WindExposure(PyMath.round(net, 2), headwindNote(mean), PyMath.round(factor, 4))
    }

    // ── course ───────────────────────────────────────────────────────────────

    fun technicalityFactor(segments: List<Segment>): Pair<Double, String> {
        if (segments.size < 2) return 1.0 to "Smooth"
        val g = segments.map { it.gradient }
        val n = g.size
        val mean = PyMath.sum(g) / n
        val sigma = sqrt(PyMath.sum(g.map { (it - mean).pow(2) }) / n)
        val steep = g.count { abs(it) > 0.20 }.toDouble() / n
        val factor = PyMath.round(min(1.12, 1.0 + max(0.0, (sigma - 0.04) * 0.30) + steep * 0.06), 3)
        val label = when {
            factor < 1.012 -> "Smooth"
            factor < 1.035 -> "Rolling"
            factor < 1.065 -> "Technical"
            else -> "Very technical"
        }
        return factor to label
    }

    fun courseTotals(segments: List<Segment>): CourseTotals {
        if (segments.isEmpty()) return CourseTotals(0, 0, 0)
        val d = PyMath.sum(segments.map { it.distanceM })
        val gain = PyMath.sum(segments.map { it.elevationGainM }.filter { it > 0 })
        val loss = abs(PyMath.sum(segments.map { it.elevationGainM }.filter { it < 0 }))
        return CourseTotals(PyMath.roundToLong(d), PyMath.roundToLong(gain), PyMath.roundToLong(loss))
    }

    // ── laps (shared) ────────────────────────────────────────────────────────

    private const val MAX_SPLIT_SPREAD = 0.08

    private fun lapDists(distanceM: Double, lapKm: Double): List<Double> {
        val nFull = (distanceM / (lapKm * 1000)).toLong().toInt()
        val lastM = distanceM - nFull * lapKm * 1000
        val n = nFull + if (lastM > 10) 1 else 0
        return List(n) { i -> if (i == nFull && lastM > 10) lastM else lapKm * 1000 }
    }

    internal fun splitRamp(n: Int, spread: Double): List<Double> {
        val mid = (n - 1) / 2.0
        val slope = -spread * MAX_SPLIT_SPREAD * 2.0 / max(n - 1, 1)
        return List(n) { i -> 1.0 + slope * (i - mid) }
    }

    internal fun lapGradients(lapDists: List<Double>, segments: List<Segment>): List<Double> {
        val out = ArrayList<Double>(lapDists.size)
        var cursor = 0
        var consumed = 0.0
        for (lapDist in lapDists) {
            var gain = 0.0
            var remaining = lapDist
            while (remaining > 0 && cursor < segments.size) {
                val seg = segments[cursor]
                val take = min(remaining, seg.distanceM - consumed)
                gain += seg.gradient * take
                remaining -= take
                consumed += take
                if (consumed >= seg.distanceM - 0.001) {
                    cursor += 1
                    consumed = 0.0
                }
            }
            out += if (lapDist > 0) gain / lapDist else 0.0
        }
        return out
    }

    /**
     * The position curve, divided per lap by the split's pace [ramp] when
     * given — a slower lap gets a lower ceiling — and capped at max HR. See
     * `compute_hr_ceilings` for why; `pct * max_hr / ramp` in that order, as
     * the Python evaluates it.
     */
    private fun hrProfile(maxHr: Int, n: Int, startPct: Double, endPct: Double, ramp: List<Double>?): List<Int> =
        List(n) { i ->
            val pos = i.toDouble() / max(n - 1, 1)
            val pct = startPct + (endPct - startPct) * pos.pow(1.5)
            if (ramp.isNullOrEmpty()) PyMath.roundToLong(pct * maxHr).toInt()
            else minOf(maxHr, PyMath.roundToLong(pct * maxHr / ramp[i]).toInt())
        }

    // ── running ──────────────────────────────────────────────────────────────

    /** Bisection for the time at which the Daniels VDOT of [distanceM] equals [vdot]. */
    fun predictRaceTimeSec(vdot: Double, distanceM: Double): Double {
        var lo = distanceM / 12.0
        var hi = distanceM * 6.0
        repeat(60) {
            val mid = (lo + hi) / 2
            if (PlanBase.calculateVdot(distanceM, mid) > vdot) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    // ── The marathon's training-volume correction (race_predictor/running.py) ──

    /** The marathon distances the correction applies to, metres. */
    val MARATHON_BAND_M = 40_000.0..44_000.0
    const val TRAINING_WINDOW_DAYS = 56
    private const val MIN_WEEKS_WITH_RUNS = 6

    /** `tanda_marathon_sec`: Tanda (2011), from mean weekly km and mean training pace. */
    fun tandaMarathonSec(weeklyKm: Double, meanPaceSecKm: Double, distanceM: Double = 42_195.0): Double {
        val pace = 17.1 + 140.0 * kotlin.math.exp(-0.0053 * weeklyKm) + 0.55 * meanPaceSecKm
        return pace * distanceM / 1000.0
    }

    /**
     * `training_indices`: (mean weekly km, mean pace s/km) over the 8 weeks
     * before [today]; null unless runs fall in at least six of them.
     */
    fun trainingIndices(
        runs: List<com.tracks.core.plan.RunningFitness.Run>,
        today: com.tracks.core.fit.decode.CivilDate,
    ): Pair<Double, Double>? {
        var km = 0.0
        var sec = 0.0
        val weeks = HashSet<Long>()
        for (r in runs) {
            val age = today.epochDay - r.date.epochDay
            if (age < 0 || age >= TRAINING_WINDOW_DAYS) continue
            val dist = r.distanceM
            val dur = r.durationS
            if (dist == null || dur == null || dist <= 0 || dur <= 0) continue
            km += dist / 1000.0
            sec += dur
            weeks.add(age / 7)
        }
        if (weeks.size < MIN_WEEKS_WITH_RUNS || km <= 0) return null
        return km / (TRAINING_WINDOW_DAYS / 7.0) to sec / km
    }

    /**
     * `predict_running_race_sec`: VDOT's finish time, corrected by training
     * volume in the marathon when [indices] are known; [freshness] is the
     * race plan's TSB factor as a time multiplier (1 on the phone, which
     * predicts a neutral day).
     */
    fun predictRunningRaceSec(
        vdot: Double, distanceM: Double, indices: Pair<Double, Double>? = null, freshness: Double = 1.0,
    ): Double {
        var sec = predictRaceTimeSec(vdot, distanceM)
        if (indices != null && distanceM in MARATHON_BAND_M) {
            sec = (sec + tandaMarathonSec(indices.first, indices.second, distanceM) * freshness) / 2
        }
        return sec
    }

    fun hrCeilings(maxHr: Int, distanceM: Double, nLaps: Int, ramp: List<Double>? = null): List<Int> {
        val (a, b) = when {
            distanceM <= 2_000 -> 0.90 to 1.00
            distanceM <= 5_000 -> 0.82 to 0.97
            distanceM <= 10_000 -> 0.78 to 0.95
            distanceM <= 21_097 -> 0.75 to 0.92
            distanceM <= 42_195 -> 0.70 to 0.87
            else -> 0.62 to 0.80
        }
        return hrProfile(maxHr, nLaps, a, b, ramp)
    }

    fun lapPaces(
        predictedSec: Double,
        distanceM: Double,
        splitSpread: Double = 0.0,
        lapKm: Double = 1.0,
        segments: List<Segment>? = null,
        maxHr: Int? = null,
    ): Pair<List<RaceLap>, Double> {
        val dists = lapDists(distanceM, lapKm)
        val n = dists.size
        var grads = List(n) { 0.0 }
        var mults = List(n) { 1.0 }
        if (!segments.isNullOrEmpty()) {
            grads = lapGradients(dists, segments)
            mults = grads.map(::gradeCostMultiplier)
        }
        val weighted = PyMath.sum(List(n) { dists[it] * mults[it] })
        val actualTotal = predictedSec * (weighted / distanceM)
        val ramp = splitRamp(n, splitSpread)
        val denominator = PyMath.sum(List(n) { dists[it] * mults[it] * ramp[it] })
        val baseFlat = actualTotal * 1000.0 / denominator
        val hr = if (maxHr != null && maxHr != 0) hrCeilings(maxHr, distanceM, n, ramp) else null
        var cum = 0.0
        val laps = List(n) { i ->
            val target = baseFlat * mults[i] * ramp[i]
            val gap = baseFlat * ramp[i]
            cum += dists[i] / 1000.0
            RaceLap(
                lap = i + 1,
                distanceM = PyMath.roundToLong(dists[i]),
                targetSecPerKm = PyMath.round(target, 1),
                targetPace = fmtPace(target),
                gradient = PyMath.round(grads[i], 4),
                gradeMultiplier = PyMath.round(mults[i], 4),
                gradeAdjSec = PyMath.round(gap, 1),
                gradeAdjPace = fmtPace(gap),
                cumulativeKm = PyMath.round(cum, 2),
                hrCeiling = hr?.get(i),
            )
        }
        return laps to PyMath.round(actualTotal, 1)
    }

    // ── cycling (Critical Power + aerodynamics) ──────────────────────────────

    private const val CDA = 0.36
    private const val CRR = 0.004
    private const val RHO = 1.20
    private const val M_KG = 78.0
    private const val G = 9.81
    private const val CP_RATIO = 0.97
    private const val W_PRIME_KJ = 22.0

    internal fun cyclingSpeedAtPower(watts: Double, gradient: Double = 0.0, windMps: Double = 0.0): Double {
        if (watts <= 0) return 0.0
        val rollAndGrade = CRR * M_KG * G + M_KG * G * gradient
        fun net(v: Double): Double {
            val apparent = v + windMps
            return 0.5 * CDA * RHO * apparent * abs(apparent) * v + rollAndGrade * v - watts
        }
        var lo = 0.5
        var hi = 30.0
        repeat(60) {
            val mid = (lo + hi) / 2
            if (net(mid) < 0) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    private fun powerForSpeed(speed: Double, gradient: Double, windMps: Double): Double {
        val rollAndG = CRR * M_KG * G + M_KG * G * gradient
        val apparent = speed + windMps
        return 0.5 * CDA * RHO * apparent * abs(apparent) * speed + rollAndG * speed
    }

    internal fun cyclingSustainablePower(ftp: Double, durationSec: Double): Double {
        val cp = ftp * CP_RATIO
        val mmp = cp + (W_PRIME_KJ * 1000.0) / durationSec
        return min(mmp, max(ftp * 1.10, cp + (W_PRIME_KJ * 1000.0) / 60))
    }

    fun predictCyclingTimeSec(ftp: Double, distanceM: Double, segments: List<Segment>? = null, windMps: Double = 0.0): Double {
        var guess = distanceM / (40_000.0 / 3600)
        for (iter in 0 until 40) {
            val watts = cyclingSustainablePower(ftp, guess)
            val newGuess = if (!segments.isNullOrEmpty()) {
                var total = 0.0
                for (seg in segments) {
                    total += seg.distanceM / max(cyclingSpeedAtPower(watts, seg.gradient, windMps), 0.1)
                }
                val courseD = PyMath.sum(segments.map { it.distanceM })
                if (courseD > 0) total *= distanceM / courseD
                total
            } else {
                distanceM / max(cyclingSpeedAtPower(watts, 0.0, windMps), 0.1)
            }
            if (abs(newGuess - guess) < 0.5) break
            guess = newGuess
        }
        return PyMath.round(guess, 1)
    }

    fun cyclingHrCeilings(maxHr: Int, distanceM: Double, nLaps: Int, ramp: List<Double>? = null): List<Int> {
        val (a, b) = when {
            distanceM <= 25_000 -> 0.88 to 0.98
            distanceM <= 50_000 -> 0.84 to 0.95
            distanceM <= 90_000 -> 0.78 to 0.90
            distanceM <= 160_000 -> 0.70 to 0.83
            else -> 0.62 to 0.78
        }
        return hrProfile(maxHr, nLaps, a, b, ramp)
    }

    fun cyclingLapTargets(
        ftp: Double,
        distanceM: Double,
        splitSpread: Double = 0.0,
        lapKm: Double = 1.0,
        segments: List<Segment>? = null,
        maxHr: Int? = null,
        predictedSec: Double? = null,
        windMps: Double = 0.0,
    ): Pair<List<RaceLap>, Double> {
        val dists = lapDists(distanceM, lapKm)
        val n = dists.size
        val predicted = predictedSec ?: predictCyclingTimeSec(ftp, distanceM, segments, windMps)
        val baseWatts = cyclingSustainablePower(ftp, predicted)
        val grads = if (!segments.isNullOrEmpty()) lapGradients(dists, segments) else List(n) { 0.0 }
        val ramp = splitRamp(n, splitSpread)
        val hr = if (maxHr != null && maxHr != 0) cyclingHrCeilings(maxHr, distanceM, n, ramp) else null
        var cum = 0.0
        var total = 0.0
        val laps = List(n) { i ->
            val flatSpeed = cyclingSpeedAtPower(baseWatts * ramp[i], 0.0, windMps)
            val targetW = max(10.0, PyMath.roundToLong(powerForSpeed(flatSpeed, grads[i], windMps)).toDouble())
            val pct = PyMath.roundToLong(targetW / ftp * 100).toInt()
            val lapSpeed = cyclingSpeedAtPower(targetW, grads[i], windMps)
            total += dists[i] / max(lapSpeed, 0.1)
            cum += dists[i] / 1000.0
            val flatKg = cyclingSpeedAtPower(targetW, 0.0, windMps)
            RaceLap(
                lap = i + 1,
                distanceM = PyMath.roundToLong(dists[i]),
                targetSecPerKm = PyMath.round(1000 / max(lapSpeed, 0.01), 1),
                targetPace = fmtPace(1000 / max(lapSpeed, 0.01)),
                gradient = PyMath.round(grads[i], 4),
                gradeMultiplier = 1.0,
                gradeAdjSec = PyMath.round(1000 / max(flatKg, 0.01), 1),
                gradeAdjPace = fmtPace(1000 / max(flatKg, 0.01)),
                cumulativeKm = PyMath.round(cum, 2),
                hrCeiling = hr?.get(i),
                targetWatts = targetW.toInt(),
                targetWattsPctFtp = pct,
            )
        }
        return laps to PyMath.round(total, 1)
    }

    // ── swimming (CSS) ───────────────────────────────────────────────────────

    fun predictSwimTimeSec(cssSecPer100m: Double, distanceM: Double, openWater: Boolean = false): Double {
        val intensity = when {
            distanceM <= 200 -> 0.88
            distanceM <= 400 -> 0.92
            distanceM <= 800 -> 0.96
            distanceM <= 1500 -> 0.99
            distanceM <= 2000 -> 1.01
            distanceM <= 4000 -> 1.03
            else -> 1.06
        }
        var t = (distanceM / 100.0) * cssSecPer100m * intensity
        if (openWater) t *= 1.07
        return PyMath.round(t, 1)
    }
}
