// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.plan

import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.parse.PyMath

/**
 * How fit a runner is today, as the VDOT every running pace is derived from —
 * `calculators/plan/running_fitness.py`, held to it by
 * spec/fixtures/running_fitness.json (RunningFitnessFixtureTest).
 *
 * The reasoning — efforts as a floor that ages, heart rate on ordinary runs as
 * the central estimate, the watch's VO2max as its fallback, the profile only
 * with no running evidence at all, and the detraining curve — is written once,
 * in the Python module's docstring and docs/running-pace-research.md; this is
 * the same arithmetic in the same order, so that a plan built on the phone and
 * one built on the server pace the same runs the same way.
 */
object RunningFitness {

    /** One pace best: the fastest stretch of a run at a standard distance. */
    data class Effort(val date: CivilDate, val distanceM: Double, val speedMps: Double)

    /** One running activity; every field but the date may be missing. */
    data class Run(
        val date: CivilDate,
        val sport: String?,
        val distanceM: Double? = null,
        val durationS: Double? = null,
        val avgSpeed: Double? = null,
        val avgHr: Double? = null,
        val ascentM: Double? = null,
        val vo2max: Double? = null,
    )

    /** The estimate: [source] is `effort`, `heart_rate`, `watch` or `profile`. */
    data class Estimate(
        val vdot: Double,
        val source: String,
        val measured: Boolean,
        val effortVdot: Double?,
    )

    const val EFFORT_CURRENT_DAYS = 42
    const val EFFORT_MAX_AGE_DAYS = 365
    const val EFFORT_MIN_DISTANCE_M = 3000
    const val RECENT_DAYS = 90
    const val HR_MIN_RUNS = 3
    const val HR_MIN_DURATION_S = 1200
    const val HR_MIN_RESERVE = 0.50
    const val HR_MAX_RESERVE = 0.95
    const val HR_MIN_SPREAD_BPM = 60
    const val HR_MIN_SPEED_MPS = 1.8
    const val HR_MAX_SPEED_MPS = 7.0
    const val HR_MAX_CLIMB_M_PER_KM = 15.0
    private val HR_RUN_SPORTS = setOf("running", "road_running")
    private const val PLAUSIBLE_LO = 15.0
    private const val PLAUSIBLE_HI = 90.0

    // Jackson et al. (1990) — unverified against the paper (see the Python).
    private const val JACKSON_INTERCEPT = 56.363
    private const val JACKSON_PAR = 1.921
    private const val JACKSON_AGE = -0.381
    private const val JACKSON_BMI = -0.754
    private const val JACKSON_MALE = 10.987
    const val PROFILE_ECONOMY = 0.85
    const val DEFAULT_AGE = 40
    const val DEFAULT_BMI = 25.0
    private val PAR_FOR_LEVEL = mapOf("never" to 1, "occasional" to 4, "1_2" to 5, "3_4" to 6, "5_plus" to 7)
    const val DEFAULT_PAR = 5

    /** `detraining_factor`: share of fitness left after [days] without training. */
    fun detrainingFactor(days: Double): Double = when {
        days <= 7 -> 1.0
        days <= 21 -> 1.0 - 0.07 * (days - 7) / 14
        days <= 56 -> 0.93 - 0.09 * (days - 21) / 35
        else -> 0.84
    }

    /** `daniels_vo2`: the O2 cost of a speed on the curve VDOT is defined by. */
    fun danielsVo2(speedMps: Double): Double {
        val v = speedMps * 60
        return -4.60 + 0.182258 * v + 0.000104 * (v * v)
    }

    /** `hr_run_vdot`: the VDOT one steady run implies, or null outside the method. */
    fun hrRunVdot(speedMps: Double, avgHr: Double, maxHr: Double, restingHr: Double): Double? {
        if (maxHr - restingHr < HR_MIN_SPREAD_BPM) return null
        if (speedMps < HR_MIN_SPEED_MPS || speedMps > HR_MAX_SPEED_MPS) return null
        val reserve = (avgHr - restingHr) / (maxHr - restingHr)
        if (reserve < HR_MIN_RESERVE || reserve > HR_MAX_RESERVE) return null
        val est = 3.5 + (danielsVo2(speedMps) - 3.5) / reserve
        return if (est in PLAUSIBLE_LO..PLAUSIBLE_HI) est else null
    }

    private fun days(today: CivilDate, d: CivilDate): Long = today.epochDay - d.epochDay

    private fun runSpeed(r: Run): Double? {
        val speed = r.avgSpeed
        if (speed != null && speed > 0) return speed
        val dist = r.distanceM
        val dur = r.durationS
        if (dist != null && dist != 0.0 && dur != null && dur > 0) return dist / dur
        return null
    }

    private fun hrEstimate(runs: List<Run>, today: CivilDate, maxHr: Double?, restingHr: Double?): Double? {
        if (maxHr == null || maxHr == 0.0 || restingHr == null || restingHr == 0.0) return null
        val ests = ArrayList<Double>()
        for (r in runs) {
            if (days(today, r.date) > RECENT_DAYS || r.sport !in HR_RUN_SPORTS) continue
            val hr = r.avgHr
            if (hr == null || hr == 0.0 || (r.durationS ?: 0.0) < HR_MIN_DURATION_S) continue
            val dist = r.distanceM
            val climb = r.ascentM
            if (climb != null && dist != null && dist != 0.0 && climb / (dist / 1000) > HR_MAX_CLIMB_M_PER_KM) continue
            val speed = runSpeed(r) ?: continue
            hrRunVdot(speed, hr, maxHr, restingHr)?.let { ests.add(it) }
        }
        return if (ests.size >= HR_MIN_RUNS) PyMath.median(ests) else null
    }

    private fun watchEstimate(runs: List<Run>, today: CivilDate): Double? {
        var latest: Pair<CivilDate, Double>? = null
        for (r in runs) {
            val v = r.vo2max ?: continue
            if (v < PLAUSIBLE_LO || v > PLAUSIBLE_HI) continue
            if (days(today, r.date) > RECENT_DAYS) continue
            if (latest == null || r.date > latest.first) latest = r.date to v
        }
        return latest?.second
    }

    private fun effortEstimate(efforts: List<Effort>, today: CivilDate, layoffDays: Long): Double? {
        var best: Double? = null
        for (e in efforts) {
            val age = days(today, e.date)
            if (e.distanceM < EFFORT_MIN_DISTANCE_M || e.speedMps <= 0 || age > EFFORT_MAX_AGE_DAYS) continue
            var v = PlanBase.calculateVdot(e.distanceM, e.distanceM / e.speedMps)
            if (v < PLAUSIBLE_LO || v > PLAUSIBLE_HI) continue
            v *= detrainingFactor(maxOf(age - (EFFORT_CURRENT_DAYS - 7), layoffDays).toDouble())
            if (best == null || v > best) best = v
        }
        return best
    }

    /** `par_from_frequencies`: the most active sport sets the PA-R. */
    fun parFromFrequencies(frequencies: Any?): Int {
        if (frequencies !is Map<*, *>) return DEFAULT_PAR
        val levels = frequencies.values.mapNotNull { PAR_FOR_LEVEL[it] }
        return levels.maxOrNull() ?: DEFAULT_PAR
    }

    /** `profile_vdot`: Jackson et al. (1990) × untrained running economy. */
    fun profileVdot(
        today: CivilDate,
        sex: String? = null,
        heightCm: Double? = null,
        weightKg: Double? = null,
        birthYear: Int? = null,
        frequencies: Any? = null,
    ): Double {
        var age = if (birthYear != null && birthYear != 0) today.year - birthYear else DEFAULT_AGE
        age = minOf(maxOf(age, 18), 80)
        val bmi = if (heightCm != null && heightCm != 0.0 && weightKg != null && weightKg != 0.0 && heightCm > 0) {
            val h = heightCm / 100
            minOf(maxOf(weightKg / (h * h), 15.0), 45.0)
        } else DEFAULT_BMI
        val male = when (sex) { "male" -> 1.0; "female" -> 0.0; else -> 0.5 }
        val vo2 = JACKSON_INTERCEPT + JACKSON_PAR * parFromFrequencies(frequencies) +
            JACKSON_AGE * age + JACKSON_BMI * bmi + JACKSON_MALE * male
        return minOf(maxOf(vo2 * PROFILE_ECONOMY, 20.0), 60.0)
    }

    /** `estimate_running_fitness`. */
    fun estimate(
        efforts: List<Effort>,
        runs: List<Run>,
        today: CivilDate,
        maxHr: Double? = null,
        restingHr: Double? = null,
        sex: String? = null,
        heightCm: Double? = null,
        weightKg: Double? = null,
        birthYear: Int? = null,
        frequencies: Any? = null,
    ): Estimate {
        fun profile() = Estimate(
            PyMath.round(profileVdot(today, sex, heightCm, weightKg, birthYear, frequencies), 1),
            "profile", false, null,
        )
        val last = (runs.map { it.date } + efforts.map { it.date }).filter { it <= today }.maxOrNull()
            ?: return profile()
        val layoff = days(today, last)
        val pastRuns = runs.filter { it.date <= today }
        val pastEfforts = efforts.filter { it.date <= today }

        val effort = effortEstimate(pastEfforts, today, layoff)
        var central = hrEstimate(pastRuns, today, maxHr, restingHr)
        var source = "heart_rate"
        if (central == null) {
            central = watchEstimate(pastRuns, today)
            source = "watch"
        }
        if (central != null) central *= detrainingFactor(layoff.toDouble())

        if (effort == null && central == null) return profile()
        val best: Double
        if (central == null || (effort != null && effort >= central)) {
            best = effort!!
            source = "effort"
        } else {
            best = central
        }
        return Estimate(PyMath.round(best, 1), source, true, effort?.let { PyMath.round(it, 1) })
    }
}
