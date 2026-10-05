// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.metrics

import com.tracks.core.fit.decode.FitDateTime
import com.tracks.core.spec.sportType
import kotlin.math.abs

/**
 * A heart-rate recording checked sample by sample before its load is computed
 * — the port of `backend/app/calculators/hr_review.py`, whose docstring gives
 * the four checks, the science behind each, and why only failed samples are
 * replaced.
 *
 * Every operation is the Python's, in the Python's order and with its
 * constants written the same way: the reviewed average feeds a TSS rounded to
 * one decimal, and a phone and a server that differed in the last bit would
 * eventually differ in the load. Held to it by `spec/fixtures/hr_review.json`.
 */
object HrReview {

    const val MIN_HR = 30.0
    const val MAX_HR = 230.0

    private const val HAMPEL_HALF = 15
    private const val HAMPEL_K = 3.0
    private const val MAD_SCALE = 1.4826
    private const val HAMPEL_FLOOR = 10.0
    private const val HAMPEL_INLIER = 40.0

    private const val LOCK_WINDOW = 60
    private const val LOCK_SHARE = 0.9
    private const val LOCK_TOL = 2.0
    private const val LOCK_MIN_RANGE = 6.0

    private const val GRADE_BASE_M = 30.0
    private const val GRADE_LIMIT = 0.45
    private const val LAG_S = 40.0
    private const val RESIDUAL_BPM = 25.0
    private const val RESIDUAL_MIN_S = 30.0
    private const val MIN_FIT_SAMPLES = 120
    private const val MIN_EFFORT_CV = 0.10
    private const val MIN_VALID_SHARE = 0.25
    private const val FIT_ROUNDS = 3
    private const val WARMUP_S = 300.0
    private const val SETTLE_S = 120.0
    private const val PAUSE_S = 10.0

    // Minetti et al. 2002, highest power first.
    private val RUN_COST = doubleArrayOf(155.4, -30.4, -43.3, 46.3, 19.5, 3.6)
    private val WALK_COST = doubleArrayOf(280.5, -58.7, -76.8, 51.9, 19.6, 2.5)

    private const val G = 9.81
    private const val AERO = 0.0025
    private val CRR = mapOf("cycling" to 0.005, "mtb" to 0.012)

    /** `HrReview`: the recording's heart rate once its failed samples are replaced. */
    data class Result(
        val usable: Boolean,
        val avgHr: Double? = null,
        val maxHr: Double? = null,
        val replaced: Int = 0,
        val samples: Int = 0,
    )

    private fun median(values: List<Double>): Double {
        val s = values.sorted()
        val n = s.size
        val mid = n / 2
        return if (n % 2 != 0) s[mid] else (s[mid - 1] + s[mid]) / 2
    }

    private fun horner(coeffs: DoubleArray, x: Double): Double {
        var acc = 0.0
        for (c in coeffs) acc = acc * x + c
        return acc
    }

    private fun power(kind: String, speed: Double, grade: Double): Double? {
        if (kind == "running") return horner(RUN_COST, grade) * speed
        if (kind == "hiking") return horner(WALK_COST, grade) * speed
        val crr = CRR[kind] ?: return null
        val p = speed * G * (crr + grade) + AERO * speed * speed * speed
        return if (p > 0) p else 0.0
    }

    private fun fit(xs: List<Double>, ys: List<Double>): Pair<Double, Double>? {
        val n = xs.size
        if (n < MIN_FIT_SAMPLES) return null
        var sx = 0.0
        var sy = 0.0
        for (i in 0 until n) {
            sx += xs[i]
            sy += ys[i]
        }
        val mx = sx / n
        val my = sy / n
        var sxx = 0.0
        var sxy = 0.0
        for (i in 0 until n) {
            val dx = xs[i] - mx
            sxx += dx * dx
            sxy += dx * (ys[i] - my)
        }
        if (mx <= 0 || sxx <= 0) return null
        if (sxx / n < (MIN_EFFORT_CV * mx) * (MIN_EFFORT_CV * mx)) return null
        val b = sxy / sxx
        if (b <= 0) return null
        return (my - b * mx) to b
    }

    private fun steady(times: List<Double>, effort: Array<Double?>, valid: BooleanArray): Boolean {
        val xs = effort.indices
            .filter { valid[it] && effort[it] != null && times[it] - times[0] >= WARMUP_S }
            .map { effort[it]!! }
        val n = xs.size
        if (n < MIN_FIT_SAMPLES) return false
        var sx = 0.0
        for (x in xs) sx += x
        val mx = sx / n
        if (mx <= 0) return false
        var sxx = 0.0
        for (x in xs) {
            val dx = x - mx
            sxx += dx * dx
        }
        return sxx / n < (MIN_EFFORT_CV * mx) * (MIN_EFFORT_CV * mx)
    }

    /** `review_heart_rate`: check each sample; null when every one passed. */
    fun review(
        sport: String?, times: List<Double>, hrs: List<Double?>, speeds: List<Double?>,
        altitudes: List<Double?>, cadences: List<Double?>,
    ): Result? {
        val n = times.size
        if (n == 0) return null
        val present = BooleanArray(n) { hrs[it].let { h -> h != null && MIN_HR <= h && h <= MAX_HR } }
        if (hrs.all { it == null }) return null
        val valid = present.copyOf()

        // ── 2. Hampel filter over the samples that are present ────────────
        val idx = (0 until n).filter { present[it] }
        val m = idx.size
        for (k in 0 until m) {
            val lo = maxOf(0, k - HAMPEL_HALF)
            val hi = minOf(m, k + HAMPEL_HALF + 1)
            val window = (lo until hi).map { hrs[idx[it]]!! }
            val med = median(window)
            // Spread from the samples near the median only (see the Python).
            val near = window.map { abs(it - med) }.filter { it <= HAMPEL_INLIER }
            val mad = if (near.isNotEmpty()) median(near) else median(window.map { abs(it - med) })
            var limit = HAMPEL_K * MAD_SCALE * mad
            if (limit < HAMPEL_FLOOR) limit = HAMPEL_FLOOR
            if (abs(hrs[idx[k]]!! - med) > limit) valid[idx[k]] = false
        }

        // ── 3. Cadence lock ────────────────────────────────────────────────
        val locked = BooleanArray(n)
        for (i in 0 until n) {
            val c = cadences[i]
            if (present[i] && c != null && c > 0) {
                val h = hrs[i]!!
                locked[i] = abs(h - c) <= LOCK_TOL || abs(h - 2 * c) <= LOCK_TOL
            }
        }
        if (n >= LOCK_WINDOW) {
            val prefix = IntArray(n + 1)
            for (i in 0 until n) prefix[i + 1] = prefix[i] + (if (locked[i]) 1 else 0)
            val need = LOCK_SHARE * LOCK_WINDOW
            for (s in 0..n - LOCK_WINDOW) {
                if (prefix[s + LOCK_WINDOW] - prefix[s] < need) continue
                var loH: Double? = null
                var hiH: Double? = null
                for (j in s until s + LOCK_WINDOW) {
                    if (locked[j]) {
                        val h = hrs[j]!!
                        if (loH == null || h < loH) loH = h
                        if (hiH == null || h > hiH) hiH = h
                    }
                }
                if (hiH!! - loH!! >= LOCK_MIN_RANGE) {
                    for (j in s until s + LOCK_WINDOW) if (locked[j]) valid[j] = false
                }
            }
        }

        // ── 4. The athlete's own heart rate against the effort ────────────
        val kind = sportType(sport)
        val effort = arrayOfNulls<Double>(n)
        val graded = BooleanArray(n)
        if (kind == "running" || kind == "hiking" || kind == "cycling" || kind == "mtb") {
            val dist = DoubleArray(n)
            for (i in 1 until n) {
                var dt = times[i] - times[i - 1]
                if (dt < 0) dt = 0.0
                val v0 = speeds[i - 1]
                val v1 = speeds[i]
                val step = if (v0 != null && v1 != null) ((v0 + v1) / 2) * dt else 0.0
                dist[i] = dist[i - 1] + step
            }
            var j = 0
            // From rest, falling back towards rest across a pause (see the Python).
            var lagged = 0.0
            var lastT = times[0]
            for (i in 0 until n) {
                val v = speeds[i] ?: continue
                while (j + 1 < i && dist[i] - dist[j + 1] >= GRADE_BASE_M) j += 1
                var grade = 0.0
                val ai = altitudes[i]
                val aj = altitudes[j]
                if (j < i && dist[i] - dist[j] >= GRADE_BASE_M && ai != null && aj != null) {
                    grade = (ai - aj) / (dist[i] - dist[j])
                    if (grade > GRADE_LIMIT) grade = GRADE_LIMIT
                    else if (grade < -GRADE_LIMIT) grade = -GRADE_LIMIT
                }
                val p = power(kind, v, grade)!!
                var dt = times[i] - lastT
                lastT = times[i]
                if (dt < 0) dt = 0.0
                if (dt > PAUSE_S) {
                    lagged = lagged + (dt / (LAG_S + dt)) * (0.0 - lagged)
                    dt = 1.0
                }
                lagged = lagged + (dt / (LAG_S + dt)) * (p - lagged)
                effort[i] = lagged
                // Only samples with an altitude are fitted: without one the
                // gradient is unknown, and a hill would read as a fault.
                graded[i] = ai != null
            }
        }

        fun fitValid(): Pair<Double, Double>? {
            val xs = ArrayList<Double>()
            val ys = ArrayList<Double>()
            for (i in 0 until n) {
                if (valid[i] && graded[i]) {
                    xs.add(effort[i]!!)
                    ys.add(hrs[i]!!)
                }
            }
            return fit(xs, ys)
        }

        fun flagRuns(off: BooleanArray): Boolean {
            var flagged = false
            var i = 0
            while (i < n) {
                if (!off[i]) {
                    i += 1
                    continue
                }
                val start = i
                while (i + 1 < n && off[i + 1]) i += 1
                if (times[i] - times[start] >= RESIDUAL_MIN_S) {
                    for (k in start..i) valid[k] = false
                    flagged = true
                }
                i += 1
            }
            return flagged
        }

        var model = fitValid()
        var rounds = 0
        while (model != null && rounds < FIT_ROUNDS) {
            val (a, b) = model
            val off = BooleanArray(n) { i ->
                valid[i] && graded[i] && times[i] - times[0] >= SETTLE_S &&
                    abs(hrs[i]!! - (a + b * effort[i]!!)) > RESIDUAL_BPM
            }
            if (!flagRuns(off)) break
            model = fitValid()
            rounds += 1
        }

        if (model == null && steady(times, effort, valid)) {
            val typical = median((0 until n).filter { valid[it] && times[it] - times[0] >= WARMUP_S }.map { hrs[it]!! })
            flagRuns(BooleanArray(n) { i ->
                valid[i] && times[i] - times[0] >= WARMUP_S && abs(hrs[i]!! - typical) > RESIDUAL_BPM
            })
        }

        // ── Replace what failed ────────────────────────────────────────────
        val good = (0 until n).filter { valid[it] }.map { hrs[it]!! }
        val replaced = n - good.size
        if (replaced == 0) return null
        if (good.isEmpty() || (model == null && good.size < MIN_VALID_SHARE * n)) {
            return Result(usable = false, replaced = replaced, samples = n)
        }
        var total = 0.0
        for (h in good) total += h
        val meanGood = total / good.size
        val loGood = good.min()
        val hiGood = good.max()
        var acc = 0.0
        for (i in 0 until n) {
            acc += when {
                valid[i] -> hrs[i]!!
                model != null && graded[i] -> {
                    var pred = model.first + model.second * effort[i]!!
                    if (pred < loGood) pred = loGood else if (pred > hiGood) pred = hiGood
                    pred
                }
                else -> meanGood
            }
        }
        return Result(usable = true, avgHr = acc / n, maxHr = hiGood, replaced = replaced, samples = n)
    }

    /** `review_points`: [review] over the parser's data points. */
    fun reviewPoints(sport: String?, points: List<Map<String, Any?>>): Result? {
        val pts = points.filter { it["recorded_at"] is FitDateTime }
        if (pts.isEmpty()) return null
        val t0 = pts[0]["recorded_at"] as FitDateTime
        fun num(v: Any?): Double? = (v as? Number)?.toDouble()
        return review(
            sport,
            pts.map { (it["recorded_at"] as FitDateTime).secondsSince(t0) },
            pts.map { num(it["heart_rate"]) },
            pts.map { num(it["speed"]) },
            pts.map { num(it["altitude"]) },
            pts.map { num(it["cadence"]) },
        )
    }
}
