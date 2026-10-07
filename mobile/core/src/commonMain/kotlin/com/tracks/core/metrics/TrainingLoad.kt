// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.metrics

import com.tracks.core.cycling.Mtb
import com.tracks.core.cycling.RoadCycling
import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.parse.PyMath
import com.tracks.core.spec.sportType

/**
 * The dashboard's numbers, computed on the phone.
 *
 * A port of the server's metrics layer — `calculators/training_load.py`,
 * `readiness.py`, `user_stats.py`, `dashboard_stats.py` and the pure helpers
 * behind the `/metrics` endpoints and `/coaching/readiness` — so a phone with no server
 * shows the same fitness, form and readiness a linked one would. Held to the
 * Python by `spec/fixtures/metrics.json` (see `spec/make_metrics_fixtures.py`),
 * with exact equality: a number that changes the moment a phone links to a
 * server is worse than no number.
 *
 * Inputs are plain rows rather than database types, because the selection —
 * date range, hidden sports, claimed devices, merged trips — is the caller's,
 * as it is the SQL's on the server. Order matters where the Python's did:
 * activities arrive oldest first, as `ORDER BY started_at` gives them.
 *
 * This supersedes what `:app`'s `LocalDashboard` approximates today (totals,
 * by-sport, calendar, weekly volume) and adds what it could not do: CTL/ATL/
 * TSB, trends, readiness, the power and pace curves, auto thresholds.
 */

/** An activity, as far as metrics need one. */
data class MetricActivity(
    val id: Long,
    /**
     * The calendar date of `started_at`, as the server reads it: the date in
     * the database session's zone, which is UTC. Not the local date the user
     * saw — a run at 23:30 in Edmonton is the next day here, as it is there.
     */
    val date: CivilDate,
    val sport: String? = null,
    val distanceMeters: Double? = null,
    val durationSeconds: Long? = null,
    val avgHeartRate: Int? = null,
    val maxHeartRate: Int? = null,
    /** Device-reported TSS, from the FIT session. */
    val trainingStressScore: Double? = null,
    /**
     * Power-based or pre-computed hrTSS, set at import by
     * `calculators/activity_metrics.py` — whose port lives in
     * com.tracks.core.parse and fills this in.
     */
    val effectiveTss: Double? = null,
    val vo2maxEstimate: Double? = null,
    val avgPower: Int? = null,
    val normalizedPower: Int? = null,
    /** Running fitness reads these (com.tracks.core.plan.RunningFitness). */
    val avgSpeed: Double? = null,
    /** Metres climbed — with no heart rate, part of what load is estimated from. */
    val totalAscent: Double? = null,
)

/** One day's health readings, as `daily_metrics` holds them. */
data class DayMetric(
    val date: CivilDate,
    val hrv: Double? = null,
    val restingHr: Double? = null,
    val sleepHours: Double? = null,
    val sleepScore: Double? = null,
)

/** The threshold-HR settings `_effective_threshold_hr` reads. */
data class ThresholdSettings(
    val mode: String?,
    val manual: Int?,
    val auto: Int?,
)

data class LoadPoint(
    val date: CivilDate,
    val tss: Double,
    val ctl: Double,
    val atl: Double,
    val tsb: Double,
    /** CTL change over the last seven days; null in the first week. */
    val ctlRamp: Double? = null,
)

object TrainingLoad {

    // Python's exp(-1/42) and exp(-1/7), bit for bit. Pinned rather than
    // computed because exp() is not required to be correctly rounded, and a
    // last-bit difference compounded over years of days is a visible one.
    internal val CTL_DECAY = Double.fromBits(0x3fef3f418cf485e5L)
    internal val ATL_DECAY = Double.fromBits(0x3febbd76b8a1ab98L)

    /** `_effective_threshold_hr`: manual or auto LTHR, whichever mode says. */
    fun effectiveThresholdHr(settings: ThresholdSettings?): Double? {
        settings ?: return null
        val v = if (settings.mode == "manual") settings.manual else settings.auto
        return v?.takeIf { it != 0 }?.toDouble()
    }

    /**
     * `training_load.scale_tss`: MTB by the active MTB goal's discipline,
     * indoor cycling +10%; no sport is both.
     *
     * Applied when load is read, never stored: the discipline comes from the
     * synced goals, so baking it in at import would make a ride's load depend
     * on which goal was active on whichever device imported it first — and two
     * devices would disagree for good.
     */
    fun scaleTss(sport: String?, tss: Double?, mtbDiscipline: String?): Double? {
        if (tss == null) return null
        if (Mtb.isMtb(sport)) return PyMath.round(tss * Mtb.tssMultiplier(mtbDiscipline), 1)
        return RoadCycling.applyIndoorTssMultiplier(sport, tss)
    }

    /**
     * Best available TSS: device → stored effective → hrTSS estimated from
     * heart rate against [thresholdHr] (or 87 % of the activity's max HR) →
     * with no heart rate, [estimatedTss] from sport, duration, distance and climb.
     */
    fun estimateTss(
        a: MetricActivity, thresholdHr: Double? = null, mtbDiscipline: String? = null,
        calibration: Map<String, Double> = emptyMap(),
    ): Double {
        a.trainingStressScore?.let { return it }
        // Stored unscaled; the sport multipliers apply here, as the server's do.
        a.effectiveTss?.let { return scaleTss(a.sport, it, mtbDiscipline)!! }
        val dur = a.durationSeconds
        val avg = a.avgHeartRate
        if (dur == null || dur == 0L) return 0.0
        if (avg == null || avg == 0) {
            val tss = estimatedTss(a.sport, dur.toDouble(), a.distanceMeters, a.totalAscent, mtbDiscipline)
            return calibrated(tss, a.sport, calibration)
        }
        val threshold = thresholdHr ?: run {
            val maxHr = a.maxHeartRate?.takeIf { it != 0 } ?: (avg * 1.15).toInt()
            maxHr * 0.87
        }
        if (threshold <= 0) return 0.0
        val ratio = minOf(avg / threshold, 1.5)
        val hours = dur.toDouble() / 3600
        return PyMath.round(hours * (ratio * ratio) * 100, 1)
    }

    // ── Load with no heart rate ─────────────────────────────────────────────
    //
    // `training_load.estimated_tss`, whose notes explain the model and why it
    // has no athlete-relative pace: a per-sport intensity on the planner's
    // hrTSS scale, moved by equivalent-flat speed where speed means something
    // (on foot, on a road bike) and by climb rate where it does not (running,
    // MTB, nordic). A function of the activity alone, so a phone with no
    // server and the server score the same file the same.
    //
    // Every operation below is the Python's, in the Python's order: the
    // result has to be the same double, not a close one.

    private val EST_IF = mapOf(
        "running" to 0.78, "triathlon" to 0.80, "nordic_skiing" to 0.75,
        "team_sports" to 0.75, "fitness_equipment" to 0.70,
        "cycling" to 0.70, "mtb" to 0.72, "indoor_cycling" to 0.72,
        "swimming" to 0.72, "rowing" to 0.72,
        "paddling" to 0.62, "hiking" to 0.60,
        "strength" to 0.60, "climbing" to 0.60, "bouldering" to 0.60, "other" to 0.60,
        "skiing" to 0.55,
        "golf" to 0.45, "mind_body" to 0.45,
    )

    /** Metres of flat per metre climbed, then (slow m/s, IF) … (fast m/s, IF). */
    private class SpeedBand(
        val perClimb: Double, val slow: Double, val slowIf: Double, val fast: Double, val fastIf: Double,
    )

    private val EST_SPEED_BANDS = mapOf(
        "hiking" to SpeedBand(8.0, 1.1, 0.55, 2.2, 0.75),
        "cycling" to SpeedBand(25.0, 4.2, 0.60, 8.3, 0.75),
    )

    private val EST_CLIMB_IF = mapOf("running" to 0.12, "mtb" to 0.10, "nordic_skiing" to 0.10)
    private const val EST_CLIMB_CAP = 800.0

    /** Stillness practices score zero, not mind_body's IF (`_EST_ZERO`). */
    private val EST_ZERO = listOf("meditat", "breath")

    /** `estimated_intensity`: the IF [estimatedTss] scores a session at. */
    fun estimatedIntensity(
        sport: String?, durationSeconds: Double, distanceMeters: Double?, totalAscent: Double?,
    ): Double {
        if (sport != null && EST_ZERO.any { sport.lowercase().contains(it) }) return 0.0
        val kind = sportType(sport)
        var intensity = EST_IF[kind] ?: EST_IF.getValue("other")
        val hours = durationSeconds / 3600
        val climb = if (totalAscent != null && totalAscent > 0) totalAscent else 0.0
        val band = EST_SPEED_BANDS[kind]
        if (band != null && distanceMeters != null && distanceMeters > 0) {
            var speed = (distanceMeters + band.perClimb * climb) / durationSeconds
            speed = minOf(maxOf(speed, band.slow), band.fast)
            intensity = band.slowIf + (band.fastIf - band.slowIf) * (speed - band.slow) / (band.fast - band.slow)
        }
        val bonus = EST_CLIMB_IF[kind]
        if (bonus != null && climb > 0) {
            intensity += bonus * minOf(climb / hours, EST_CLIMB_CAP) / EST_CLIMB_CAP
        }
        return intensity
    }

    /** `estimated_tss`: load for an activity with no heart rate, power or device TSS. */
    fun estimatedTss(
        sport: String?, durationSeconds: Double?, distanceMeters: Double? = null,
        totalAscent: Double? = null, mtbDiscipline: String? = null,
    ): Double {
        return scaleTss(sport, estimatedRawTss(sport, durationSeconds, distanceMeters, totalAscent), mtbDiscipline)!!
    }

    /** `estimated_raw_tss`: [estimatedTss] before the sport multipliers — what an import stores. */
    fun estimatedRawTss(
        sport: String?, durationSeconds: Double?, distanceMeters: Double? = null, totalAscent: Double? = null,
    ): Double {
        if (durationSeconds == null || durationSeconds <= 0) return 0.0
        val f = estimatedIntensity(sport, durationSeconds, distanceMeters, totalAscent)
        return PyMath.round(durationSeconds / 3600 * (f * f) * 100, 1)
    }

    // ── Calibrating the estimate to the athlete ─────────────────────────────
    //
    // `training_load.load_calibration`: measured ÷ estimated load per sport
    // type over the athlete's twenty most recent measured sessions (median,
    // clamped), the pooled ratio pulled halfway to 1 for a sport with too few.
    // Always over the whole history — every caller here passes all of it.
    // "Most recent" by (date, ratio), because this phone's rows arrive newest
    // first and carry no time of day: any other order could pick a different
    // twenty than the server does on a day with two sessions.

    private const val CAL_RECENT = 20
    private const val CAL_MIN_SESSIONS = 5
    private const val CAL_MIN_POOLED = 10
    private const val CAL_LO = 0.5
    private const val CAL_HI = 2.0

    private fun clampRatio(k: Double): Double = if (k < CAL_LO) CAL_LO else if (k > CAL_HI) CAL_HI else k

    private val byDateThenRatio = compareBy<Pair<Long, Double>>({ it.first }, { it.second })

    /** `load_calibration`: {sport type: scale, "*": pooled scale}. */
    fun loadCalibration(
        activities: List<MetricActivity>, thresholdHr: Double? = null, mtbDiscipline: String? = null,
    ): Map<String, Double> {
        val byType = LinkedHashMap<String, MutableList<Pair<Long, Double>>>()
        val pooled = ArrayList<Pair<Long, Double>>()
        for (a in activities) {
            val dur = a.durationSeconds
            if (dur == null || dur <= 0) continue
            val measured = a.trainingStressScore != null || a.effectiveTss != null || (a.avgHeartRate ?: 0) != 0
            if (!measured) continue
            val est = estimatedTss(a.sport, dur.toDouble(), a.distanceMeters, a.totalAscent, mtbDiscipline)
            val got = estimateTss(a, thresholdHr, mtbDiscipline)
            if (est <= 0 || got <= 0) continue
            val key = a.date.epochDay to got / est
            byType.getOrPut(sportType(a.sport)) { ArrayList() }.add(key)
            pooled.add(key)
        }
        val out = LinkedHashMap<String, Double>()
        for ((kind, keys) in byType) {
            if (keys.size >= CAL_MIN_SESSIONS) {
                out[kind] = clampRatio(PyMath.median(keys.sortedWith(byDateThenRatio).takeLast(CAL_RECENT).map { it.second }))
            }
        }
        if (pooled.size >= CAL_MIN_POOLED) {
            val k = clampRatio(PyMath.median(pooled.sortedWith(byDateThenRatio).takeLast(CAL_RECENT).map { it.second }))
            out["*"] = 1 + (k - 1) / 2
        }
        return out
    }

    /** `calibrated`: an estimate scaled by the athlete's calibration, where there is one. */
    fun calibrated(tss: Double, sport: String?, calibration: Map<String, Double>): Double {
        if (calibration.isEmpty() || tss <= 0) return tss
        val k = calibration[sportType(sport)] ?: calibration["*"] ?: return tss
        return PyMath.round(tss * k, 1)
    }

    /** CTL/ATL/TSB over every calendar day from the first load to the last. */
    fun calculateCtlAtlTsb(dailyLoads: List<Pair<CivilDate, Double>>): List<LoadPoint> {
        if (dailyLoads.isEmpty()) return emptyList()
        val byDate = dailyLoads.associate { it.first.epochDay to it.second }
        val out = ArrayList<LoadPoint>()
        var ctl = 0.0
        var atl = 0.0
        for (day in dailyLoads.first().first.epochDay..dailyLoads.last().first.epochDay) {
            val tss = byDate[day] ?: 0.0
            ctl = ctl * CTL_DECAY + tss * (1 - CTL_DECAY)
            atl = atl * ATL_DECAY + tss * (1 - ATL_DECAY)
            out += LoadPoint(
                CivilDate.fromEpochDay(day),
                PyMath.round(tss, 1), PyMath.round(ctl, 1), PyMath.round(atl, 1),
                PyMath.round(ctl - atl, 1),
            )
        }
        return out
    }

    /**
     * Summed TSS per day, in date order — the server's `tss_by_date`.
     * [activities] must be the whole history: the calibration is read from it.
     */
    fun tssByDate(
        activities: List<MetricActivity>, thresholdHr: Double?, mtbDiscipline: String? = null,
        calibration: Map<String, Double> = loadCalibration(activities, thresholdHr, mtbDiscipline),
    ): Map<Long, Double> {
        val sums = LinkedHashMap<Long, Double>()
        for (a in activities) {
            sums[a.date.epochDay] = (sums[a.date.epochDay] ?: 0.0) + estimateTss(a, thresholdHr, mtbDiscipline, calibration)
        }
        return sums.toSortedMap()
    }

    /**
     * `/metrics/training-load`: the fitness series with its 7-day CTL ramp.
     *
     * The series runs through [today], not just to the last activity, as the
     * server's `_compute_tload_points` does: rest days keep decaying fitness
     * and fatigue, and a series that stopped at the last workout left a gap
     * at the right of the chart until the next one was recorded.
     */
    fun trainingLoad(
        activities: List<MetricActivity>, thresholdHr: Double?, mtbDiscipline: String? = null,
        today: CivilDate? = null,
    ): List<LoadPoint> {
        if (activities.isEmpty()) return emptyList()
        val sums = tssByDate(activities, thresholdHr, mtbDiscipline).toMutableMap()
        if (today != null) {
            sums.putIfAbsent(today.epochDay, 0.0)
            sums.keys.removeAll { it > today.epochDay }
        }
        val loads = sums.toSortedMap().map { (d, t) -> CivilDate.fromEpochDay(d) to t }
        val points = calculateCtlAtlTsb(loads)
        val ctlByDay = points.associate { it.date.epochDay to it.ctl }
        return points.map { p ->
            val weekAgo = ctlByDay[p.date.epochDay - 7]
            p.copy(ctlRamp = weekAgo?.let { PyMath.round(p.ctl - it, 1) })
        }
    }

    /** Today's (CTL, ATL, CTL a week ago), with rest days decayed up to [today]. */
    fun ctlAtlToday(tssByDate: Map<Long, Double>, today: CivilDate): Triple<Double, Double, Double?> {
        if (tssByDate.isEmpty()) return Triple(0.0, 0.0, null)
        val extended = tssByDate.toMutableMap().apply { putIfAbsent(today.epochDay, 0.0) }
        val points = calculateCtlAtlTsb(
            extended.toSortedMap().map { (d, t) -> CivilDate.fromEpochDay(d) to t },
        ).associateBy { it.date.epochDay }
        val now = points.getValue(today.epochDay)
        return Triple(now.ctl, now.atl, points[today.epochDay - 7]?.ctl)
    }

    /** The ~2-day acute load as of [today] — see [Readiness.acuteLoadEma]. */
    fun acuteLoadToday(tssByDate: Map<Long, Double>, today: CivilDate): Double {
        if (tssByDate.isEmpty()) return 0.0
        val start = minOf(tssByDate.keys.min(), today.epochDay)
        val series = (start..today.epochDay).map { tssByDate[it] ?: 0.0 }
        return Readiness.acuteLoadEma(series).last()
    }
}
