// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.metrics

import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.parse.PyMath

/**
 * The dashboard slices and trends — a port of
 * `backend/app/calculators/dashboard_stats.py`, whose docstring states the
 * SQL semantics these reproduce (null sums, by-sport tie order).
 */
object DashboardStats {

    data class Summary(
        val activityCount: Int,
        val totalDistanceKm: Double?,
        val totalDurationHours: Double?,
        val sportCount: Int,
        val deviceCount: Int,
        val avgDistanceKm: Double?,
        val avgDurationMinutes: Double?,
    )

    data class SportBreakdown(
        val sport: String,
        val activityCount: Int,
        val totalDistanceKm: Double?,
        val totalDurationHours: Double?,
        val avgDurationMinutes: Double?,
    )

    data class WeeklyVolume(
        val weekStart: CivilDate,
        val distanceKm: Double?,
        val durationHours: Double?,
        val activityCount: Int,
    )

    data class ActivityLoad(val activityId: Long, val date: CivilDate, val sport: String?, val tss: Double)

    data class TrendBucket(
        val periodStart: CivilDate,
        val activityCount: Int,
        val totalDistanceKm: Double?,
        val totalDurationHours: Double?,
        val totalTss: Double?,
        val monotony: Double?,
        val strain: Double?,
    )

    enum class Bucket { Week, Month, Year }

    /** Python truthiness for a rounding guard: None and 0 skip the figure. */
    private fun Double?.nz(): Double? = this?.takeIf { it != 0.0 }

    private fun sumDistance(rows: List<MetricActivity>): Double? {
        val present = rows.mapNotNull { it.distanceMeters }
        return if (present.isEmpty()) null else present.drop(1).fold(present[0]) { a, b -> a + b }
    }

    // Integer seconds sum exactly, as Python ints do; one division rounds once.
    private fun sumDuration(rows: List<MetricActivity>): Long? {
        val present = rows.mapNotNull { it.durationSeconds }
        return if (present.isEmpty()) null else present.sum()
    }

    private fun avgDuration(rows: List<MetricActivity>): Double? {
        val present = rows.mapNotNull { it.durationSeconds }
        return if (present.isEmpty()) null else present.sum().toDouble() / present.size
    }

    fun summary(rows: List<MetricActivity>, deviceCount: Int): Summary {
        val dist = sumDistance(rows)
        val dur = sumDuration(rows)?.toDouble()
        val gps = rows.filter { (it.distanceMeters ?: 0.0) > 0 }
        val avgDist = sumDistance(gps)?.let { it / gps.size }
        val avgDur = avgDuration(rows)
        return Summary(
            activityCount = rows.size,
            totalDistanceKm = dist.nz()?.let { PyMath.round(it / 1000, 2) },
            totalDurationHours = dur.nz()?.let { PyMath.round(it / 3600, 2) },
            sportCount = rows.mapNotNull { it.sport }.toSet().size,
            deviceCount = deviceCount,
            avgDistanceKm = avgDist.nz()?.let { PyMath.round(it / 1000, 2) },
            avgDurationMinutes = avgDur.nz()?.let { PyMath.round(it / 60, 1) },
        )
    }

    fun bySport(rows: List<MetricActivity>): List<SportBreakdown> =
        rows.filter { it.sport != null }
            .groupBy { it.sport!! }
            .entries
            .sortedWith(compareBy({ -it.value.size }, { it.key }))
            .map { (sport, members) ->
                SportBreakdown(
                    sport = sport,
                    activityCount = members.size,
                    totalDistanceKm = sumDistance(members).nz()?.let { PyMath.round(it / 1000, 2) },
                    totalDurationHours = sumDuration(members)?.toDouble().nz()?.let { PyMath.round(it / 3600, 2) },
                    avgDurationMinutes = avgDuration(members).nz()?.let { PyMath.round(it / 60, 1) },
                )
            }

    /** Activities per day, for the contribution heatmap. */
    fun activityCalendar(rows: List<MetricActivity>): List<Pair<CivilDate, Int>> =
        rows.groupingBy { it.date.epochDay }.eachCount().toSortedMap()
            .map { (d, c) -> CivilDate.fromEpochDay(d) to c }

    /**
     * `vo2max_history`: the device's readings, or with none anywhere, the
     * pace-based estimate ([runningVo2maxHistory]) — dashboard_stats.py says why.
     */
    fun vo2maxHistory(rows: List<MetricActivity>): List<Pair<CivilDate, Double>> {
        val seen = HashMap<Long, Double>()
        for (r in rows) r.vo2maxEstimate?.let { seen[r.date.epochDay] = it }
        if (seen.isEmpty()) return runningVo2maxHistory(rows)
        return seen.toSortedMap().map { (d, v) -> CivilDate.fromEpochDay(d) to v }
    }

    private const val RUN_VO2_MIN_M = 1500.0
    private const val RUN_VO2_MIN_S = 480L
    private const val RUN_VO2_MIN_MPS = 1.5
    private const val RUN_VO2_MAX_MPS = 6.5
    private const val RUN_VO2_WINDOW_DAYS = 90L

    /**
     * `running_vo2max_history`: each run's Daniels VDOT, and per day with a
     * run the best of the trailing 90 days. The server's docstring has the
     * reasoning and the thresholds' sources.
     */
    fun runningVo2maxHistory(rows: List<MetricActivity>): List<Pair<CivilDate, Double>> {
        val runs = ArrayList<Pair<Long, Double>>()
        for (r in rows) {
            if (com.tracks.core.plan.PlanBase.sportFamily(r.sport.orEmpty()) != "running") continue
            val dist = r.distanceMeters ?: continue
            val dur = r.durationSeconds ?: continue
            if (dist == 0.0 || dur == 0L || dist < RUN_VO2_MIN_M || dur < RUN_VO2_MIN_S) continue
            val speed = dist / dur
            if (speed < RUN_VO2_MIN_MPS || speed > RUN_VO2_MAX_MPS) continue
            runs += r.date.epochDay to com.tracks.core.plan.PlanBase.calculateVdot(dist, dur.toDouble())
        }
        return runs.map { it.first }.toSortedSet().map { d ->
            val best = runs.filter { (day, _) -> day > d - RUN_VO2_WINDOW_DAYS && day <= d }.maxOf { it.second }
            CivilDate.fromEpochDay(d) to PyMath.round(best, 1)
        }
    }

    /** Python's `date.weekday()`: Monday is 0. 1970-01-01 was a Thursday. */
    internal fun weekday(d: CivilDate): Int = (d.epochDay + 3).mod(7L).toInt()

    fun weeklyVolume(rows: List<MetricActivity>): List<WeeklyVolume> {
        class B { var dist = 0.0; var dur = 0.0; var count = 0 }
        val buckets = HashMap<Long, B>()
        for (r in rows) {
            val b = buckets.getOrPut(r.date.epochDay - weekday(r.date)) { B() }
            b.dist += r.distanceMeters ?: 0.0
            b.dur += (r.durationSeconds ?: 0L).toDouble()
            b.count += 1
        }
        return buckets.toSortedMap().map { (ws, b) ->
            WeeklyVolume(
                CivilDate.fromEpochDay(ws),
                b.dist.nz()?.let { PyMath.round(it / 1000, 2) },
                b.dur.nz()?.let { PyMath.round(it / 3600, 2) },
                b.count,
            )
        }
    }

    fun activityLoad(
        rows: List<MetricActivity>, thresholdHr: Double?, mtbDiscipline: String? = null,
        calibration: Map<String, Double> = TrainingLoad.loadCalibration(rows, thresholdHr, mtbDiscipline),
    ): List<ActivityLoad> =
        rows.map { ActivityLoad(it.id, it.date, it.sport, TrainingLoad.estimateTss(it, thresholdHr, mtbDiscipline, calibration)) }

    private fun periodStart(d: CivilDate, bucket: Bucket): CivilDate = when (bucket) {
        Bucket.Week -> CivilDate.fromEpochDay(d.epochDay - weekday(d))
        Bucket.Month -> CivilDate(d.year, d.month, 1)
        Bucket.Year -> CivilDate(d.year, 1, 1)
    }

    /** Volume and TSS per period, with Foster's monotony and strain. */
    fun trends(
        rows: List<MetricActivity>, thresholdHr: Double?, bucket: Bucket, mtbDiscipline: String? = null,
        calibration: Map<String, Double> = TrainingLoad.loadCalibration(rows, thresholdHr, mtbDiscipline),
    ): List<TrendBucket> {
        class B { var count = 0; var dist = 0.0; var dur = 0.0; var tss = 0.0; val days = HashSet<Long>() }
        val dailyTss = HashMap<Long, Double>()
        val buckets = HashMap<Long, B>()
        for (r in rows) {
            val tss = TrainingLoad.estimateTss(r, thresholdHr, mtbDiscipline, calibration)
            val day = r.date.epochDay
            dailyTss[day] = (dailyTss[day] ?: 0.0) + tss
            val b = buckets.getOrPut(periodStart(r.date, bucket).epochDay) { B() }
            b.count += 1
            b.dist += r.distanceMeters ?: 0.0
            b.dur += (r.durationSeconds ?: 0L).toDouble()
            b.tss += tss
            b.days += day
        }

        fun monotonyStrain(b: B): Pair<Double?, Double?> {
            if (b.days.isEmpty() || b.tss == 0.0) return null to null
            val span = (b.days.min()..b.days.max()).map { dailyTss[it] ?: 0.0 }
            if (span.size < 2) return null to null
            val std = PyMath.pstdev(span)
            if (std == 0.0) return null to null
            val mono = PyMath.round(PyMath.mean(span) / std, 2)
            return mono to PyMath.round(b.tss * mono, 1)
        }

        return buckets.toSortedMap().map { (key, b) ->
            val (mono, strain) = monotonyStrain(b)
            TrendBucket(
                CivilDate.fromEpochDay(key), b.count,
                b.dist.nz()?.let { PyMath.round(it / 1000, 2) },
                b.dur.nz()?.let { PyMath.round(it / 3600, 2) },
                b.tss.nz()?.let { PyMath.round(it, 1) },
                mono, strain,
            )
        }
    }
}
