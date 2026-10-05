// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.metrics

import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.parse.PyMath
import kotlin.math.exp

/** `ReadinessResult` — the dashboard gauge and its breakdown. */
data class ReadinessResult(
    val score: Double,
    val hrvScore: Double,
    val sleepScore: Double,
    val restingHrScore: Double,
    val hrvToday: Double?,
    val hrvBaseline: Double?,
    val sleepHours: Double?,
    val garminSleepScore: Double?,
    val restingHrToday: Double?,
    val restingHrBaseline: Double?,
    val trainingScore: Double?,
    val primaryDriver: String,
    val confidence: String,
    val notes: List<String>,
)

/**
 * Readiness, 0–100: a port of `backend/app/calculators/readiness.py`.
 *
 * The weights and thresholds are the server's, deliberately unexplained here —
 * the Python carries the reasoning, and two copies of it would drift.
 */
object Readiness {

    // Python's exp(-1/2), pinned for the same reason as TrainingLoad's decays.
    private val ACUTE_DECAY = Double.fromBits(0x3fe368b2fc6f960aL)
    private const val RECOVERY_MIDPOINT = 1.5
    private const val RECOVERY_STEEPNESS = 1.7
    private const val CHRONIC_FLOOR = 20.0

    /** Running ~2-day EMA of a day-by-day TSS series. */
    fun acuteLoadEma(dailyTss: List<Double>): List<Double> {
        var acute = 0.0
        return dailyTss.map { tss ->
            acute = acute * ACUTE_DECAY + tss * (1.0 - ACUTE_DECAY)
            acute
        }
    }

    private fun scoreHrv(today: Double?, baseline: Double?): Double {
        if (today == null || baseline == null || baseline <= 0) return 25.0
        val ratio = today / baseline
        return when {
            ratio >= 1.10 -> 40.0
            ratio >= 0.95 -> 35.0
            ratio >= 0.85 -> 25.0
            ratio >= 0.75 -> 15.0
            else -> 5.0
        }
    }

    private fun scoreSleep(garmin: Double?, hours: Double?): Double = when {
        garmin != null -> when {
            garmin >= 80 -> 35.0
            garmin >= 70 -> 28.0
            garmin >= 60 -> 20.0
            garmin >= 50 -> 12.0
            else -> 5.0
        }
        hours != null -> when {
            hours >= 8.0 -> 35.0
            hours >= 7.0 -> 28.0
            hours >= 6.0 -> 18.0
            else -> 8.0
        }
        else -> 20.0
    }

    private fun scoreRestingHr(today: Double?, baseline: Double?): Double {
        if (today == null || baseline == null || baseline <= 0) return 18.0
        val ratio = today / baseline
        return when {
            ratio <= 0.95 -> 25.0
            ratio <= 1.05 -> 20.0
            ratio <= 1.15 -> 12.0
            else -> 5.0
        }
    }

    private fun normalizeTsb(tsb: Double): Double =
        if (tsb >= -10.0) minOf(100.0, (tsb + 30.0) / 50.0 * 100.0)
        else 40.0 * exp((tsb + 10.0) / 56.0)

    private fun acuteRecoveryScore(acute: Double, chronic: Double): Double {
        val ratio = acute / maxOf(chronic, CHRONIC_FLOOR)
        return 100.0 / (1.0 + exp(RECOVERY_STEEPNESS * (ratio - RECOVERY_MIDPOINT)))
    }

    /** Python truthiness of an optional float: None and 0.0 are both "absent". */
    private fun Double?.present(): Double? = this?.takeIf { it != 0.0 }

    fun compute(
        today: DayMetric?,
        recent: List<DayMetric>,
        tsb: Double? = null,
        acuteLoad: Double? = null,
        chronicLoad: Double? = null,
    ): ReadinessResult {
        val notes = ArrayList<String>()
        val hrvToday = today?.hrv.present()
        val rhrToday = today?.restingHr.present()
        val sleepHrs = today?.sleepHours.present()
        val sleepScore = today?.sleepScore.present()

        val priorHrv = recent.mapNotNull { it.hrv.present() }
        val priorRhr = recent.mapNotNull { it.restingHr.present() }
        val hrvBaseline = priorHrv.takeIf { it.isNotEmpty() }?.let(PyMath::mean)
        val rhrBaseline = priorRhr.takeIf { it.isNotEmpty() }?.let(PyMath::mean)

        val hrvSc = scoreHrv(hrvToday, hrvBaseline)
        val slpSc = scoreSleep(sleepScore, sleepHrs)
        val rhrSc = scoreRestingHr(rhrToday, rhrBaseline)
        val physio = hrvSc + slpSc + rhrSc

        val trainingReady = when {
            // Both zero = no training history, which is unknown rather than
            // rested — see the Python: a new account must not read "Prime".
            acuteLoad != null && chronicLoad != null && (acuteLoad != 0.0 || chronicLoad != 0.0) ->
                acuteRecoveryScore(acuteLoad, chronicLoad)
            tsb != null -> normalizeTsb(tsb)
            else -> null
        }
        val hasHealth = hrvToday != null || rhrToday != null || sleepHrs != null || sleepScore != null

        val (raw, driver, confidence) = when {
            hasHealth && trainingReady != null -> Triple(physio * 0.55 + trainingReady * 0.45, "mixed", "high")
            trainingReady != null -> Triple(trainingReady * 0.85 + 15.0, "training_load", "medium")
            hasHealth -> Triple(physio, "health_data", "medium")
            else -> Triple(50.0, "default", "low")
        }
        val total = PyMath.round(raw, 1)

        if (hrvToday == null) {
            notes += "No HRV data for today — using neutral estimate"
        } else if (hrvBaseline.present() != null && hrvToday < hrvBaseline!! * 0.85) {
            notes += "HRV significantly below baseline (${PyMath.fixed(hrvToday, 0)} vs " +
                "${PyMath.fixed(hrvBaseline, 0)} avg) — prioritise recovery"
        }
        if (sleepScore == null && sleepHrs == null) {
            notes += "No sleep data — using neutral estimate"
        } else if (sleepHrs != null && sleepHrs < 6.5) {
            notes += "Short sleep (${PyMath.fixed(sleepHrs, 1)}h) — consider an easy day"
        }
        if (rhrToday == null) {
            notes += "No resting HR data — using neutral estimate"
        } else if (rhrBaseline.present() != null && rhrToday > rhrBaseline!! * 1.10) {
            notes += "Resting HR elevated (${PyMath.fixed(rhrToday, 0)} vs " +
                "${PyMath.fixed(rhrBaseline, 0)} avg) — may indicate residual fatigue"
        }
        when {
            confidence == "low" ->
                notes += "Not enough data for a reliable assessment — sync your watch or add health data"
            driver == "training_load" ->
                notes += "Score is primarily based on training load — health data would improve accuracy"
            driver == "mixed" -> notes += "Score blends health data and training load"
        }
        notes += when {
            total >= 80 -> "Readiness is high — body is well recovered"
            total >= 60 -> "Readiness is moderate — listen to your body during the session"
            else -> "Readiness is low — favour easy or recovery work today"
        }

        return ReadinessResult(
            score = total, hrvScore = hrvSc, sleepScore = slpSc, restingHrScore = rhrSc,
            hrvToday = hrvToday, hrvBaseline = hrvBaseline, sleepHours = sleepHrs,
            garminSleepScore = sleepScore, restingHrToday = rhrToday, restingHrBaseline = rhrBaseline,
            trainingScore = trainingReady, primaryDriver = driver, confidence = confidence, notes = notes,
        )
    }

    /** The live gauge, as `/coaching/readiness` builds it. */
    fun today(
        today: CivilDate,
        metrics: List<DayMetric>,
        activities: List<MetricActivity>,
        thresholdHr: Double?,
        mtbDiscipline: String? = null,
    ): ReadinessResult {
        val byDay = metrics.associateBy { it.date.epochDay }
        val recent = metrics.filter { it.date.epochDay < today.epochDay }
            .sortedByDescending { it.date.epochDay }.take(7)
        val tss = TrainingLoad.tssByDate(activities, thresholdHr, mtbDiscipline)
        val (ctl, atl, _) = TrainingLoad.ctlAtlToday(tss, today)
        val tsb = if (ctl != 0.0 || atl != 0.0) ctl - atl else null
        val acute = TrainingLoad.acuteLoadToday(tss, today)
        return compute(byDay[today.epochDay], recent, tsb = tsb, acuteLoad = acute, chronicLoad = ctl)
    }

    data class HistoryPoint(
        val date: CivilDate,
        val score: Double,
        val hrvScore: Double,
        val sleepScore: Double,
        val restingHrScore: Double,
        val trainingScore: Double?,
        val primaryDriver: String,
        val confidence: String,
    )

    /**
     * `/metrics/readiness-history`: each day scored exactly as the live gauge
     * ([today]) scored it that morning. So [activities] must be the live
     * selection (claimed devices, hidden sports removed, the whole history) and
     * [thresholdHr] the same effective threshold. See `calculators/readiness.py`.
     */
    fun history(
        metrics: List<DayMetric>,
        activities: List<MetricActivity>,
        today: CivilDate,
        days: Int,
        thresholdHr: Double? = null,
        mtbDiscipline: String? = null,
    ): List<HistoryPoint> {
        val start = today.epochDay - (days - 1)
        val byDay = metrics.associateBy { it.date.epochDay }
        val tss = TrainingLoad.tssByDate(activities, thresholdHr, mtbDiscipline)
        val ctlByDay = HashMap<Long, Double>()
        val atlByDay = HashMap<Long, Double>()
        val acuteByDay = HashMap<Long, Double>()
        if (tss.isNotEmpty()) {
            val extended = tss.toMutableMap().apply { putIfAbsent(today.epochDay, 0.0) }.toSortedMap()
            val points = TrainingLoad.calculateCtlAtlTsb(extended.map { (d, t) -> CivilDate.fromEpochDay(d) to t })
            val acute = acuteLoadEma(points.map { it.tss })
            points.forEachIndexed { i, p ->
                ctlByDay[p.date.epochDay] = p.ctl
                atlByDay[p.date.epochDay] = p.atl
                acuteByDay[p.date.epochDay] = acute[i]
            }
        }
        return (start..today.epochDay).map { day ->
            val prior = (1..7).mapNotNull { byDay[day - it] }
            // Before the first activity the live score saw an empty history,
            // which it reads as zero load rather than unknown.
            val ctl = ctlByDay[day] ?: 0.0
            val atl = atlByDay[day] ?: 0.0
            val tsb = if (ctl != 0.0 || atl != 0.0) ctl - atl else null
            val r = compute(byDay[day], prior, tsb = tsb, acuteLoad = acuteByDay[day] ?: 0.0, chronicLoad = ctl)
            HistoryPoint(
                CivilDate.fromEpochDay(day), r.score, r.hrvScore, r.sleepScore, r.restingHrScore,
                r.trainingScore, r.primaryDriver, r.confidence,
            )
        }
    }
}
