// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import com.tracks.core.api.ActivityCalendarPoint
import com.tracks.core.api.DailyCoaching
import com.tracks.core.coaching.Coaching
import com.tracks.core.coaching.CoachingActivity
import com.tracks.core.coaching.CoachingGoal
import com.tracks.core.api.MetricsSummary
import com.tracks.core.api.ReadinessHistoryPoint
import com.tracks.core.api.SportBreakdown
import com.tracks.core.api.TrainingLoadPoint
import com.tracks.core.api.Vo2MaxPoint
import com.tracks.core.api.WeeklyVolumePoint
import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.metrics.DashboardStats
import com.tracks.core.metrics.DayMetric
import com.tracks.core.metrics.MetricActivity
import com.tracks.core.metrics.Readiness
import com.tracks.core.metrics.TrainingLoad
import com.tracks.core.plan.EventDate
import com.tracks.core.api.DailyMetricFull
import com.tracks.core.parse.PyMath
import com.tracks.core.api.SleepNight
import com.tracks.core.api.StressDay
import com.tracks.core.api.TracksJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The dashboard's endpoints, answered on the phone.
 *
 * Each function is the server endpoint of the same name with the SQL replaced
 * by a filter over [LocalLibrary] rows and the arithmetic delegated to the
 * port in com.tracks.core.metrics, which is held to the server by
 * `spec/fixtures/metrics.json`. What is left here is only the shape change
 * into the API's models, so a screen written against the server needs no
 * other change to run on the phone.
 */
class LocalMetrics(private val library: LocalLibrary, private val sources: LocalSources) {

    /**
     * Everything here reads the database and then does the arithmetic of a
     * whole history — a 42-day fitness model, a year of readiness. Callers are
     * view models on the main thread; run there, a dashboard redraw during a
     * first sync pinned the main thread until Android reported the app as not
     * responding. So each public function moves itself onto the CPU pool.
     */
    private suspend fun <T> cpu(block: suspend () -> T): T = withContext(Dispatchers.Default) { block() }

    /** Every activity the fitness model counts: derived rows, minus what the person deleted or hid. */
    private suspend fun rows(): List<MetricActivity> {
        val visible = sources.activities().associateBy { it.id }
        return library.metricActivities(sources.accountZone())
            .filter { it.id.toInt() in visible }
            .map { a -> visible.getValue(a.id.toInt()).sport?.let { a.copy(sport = it) } ?: a }
    }

    private suspend fun window(after: String?, sport: String? = null): List<MetricActivity> {
        val from = after?.let(::civil)
        return rows().filter { a ->
            (from == null || a.date >= from) && (sport == null || a.sport == sport)
        }
    }

    suspend fun summary(after: String?): MetricsSummary? = cpu {
        val rows = window(after)
        if (rows.isEmpty() && library.activities().isEmpty()) return@cpu null
        val devices = library.q.selectActivities().executeAsList().mapNotNull { it.device_serial }.toSet().size
        val s = DashboardStats.summary(rows, devices)
        return@cpu MetricsSummary(
            activityCount = s.activityCount,
            totalDistanceKm = s.totalDistanceKm,
            totalDurationHours = s.totalDurationHours,
            sportCount = s.sportCount,
            deviceCount = s.deviceCount,
            avgDistanceKm = s.avgDistanceKm,
            avgDurationMinutes = s.avgDurationMinutes,
        )
    }

    suspend fun bySport(after: String?): List<SportBreakdown> = cpu {
        DashboardStats.bySport(window(after)).map {
            SportBreakdown(it.sport, it.activityCount, it.totalDistanceKm, it.totalDurationHours, it.avgDurationMinutes)
        }
    }

    suspend fun calendar(after: String?, sport: String?): List<ActivityCalendarPoint> = cpu {
        DashboardStats.activityCalendar(window(after, sport)).map { (d, n) -> ActivityCalendarPoint(d.isoformat(), n) }
    }

    suspend fun weeklyVolume(after: String?, sport: String?): List<WeeklyVolumePoint> = cpu {
        DashboardStats.weeklyVolume(window(after, sport)).map {
            WeeklyVolumePoint(it.weekStart.isoformat(), it.distanceKm, it.durationHours, it.activityCount)
        }
    }

    suspend fun vo2max(after: String?): List<Vo2MaxPoint> = cpu {
        DashboardStats.vo2maxHistory(window(after)).map { (d, v) -> Vo2MaxPoint(d.isoformat(), v) }
    }

    /** All-time CTL/ATL/TSB, as `/metrics/training-load`. */
    suspend fun trainingLoad(today: CivilDate, thresholdHr: Double?): List<TrainingLoadPoint> = cpu {
        TrainingLoad.trainingLoad(rows(), thresholdHr, sources.mtbDiscipline(), today).map {
            TrainingLoadPoint(it.date.isoformat(), it.tss, it.ctl, it.atl, it.tsb, it.ctlRamp)
        }
    }

    /**
     * One sport's own fitness, fatigue and form, as the server's
     * `/metrics/training-load?sport=`: that sport's activities, each scored
     * against the whole history's calibration so a ride counts the same here
     * as in the overall series.
     */
    suspend fun sportTrainingLoad(today: CivilDate, thresholdHr: Double?, sport: String): List<TrainingLoadPoint> = cpu {
        val all = rows()
        val discipline = sources.mtbDiscipline()
        val calibration = TrainingLoad.loadCalibration(all, thresholdHr, discipline)
        val mine = all.filter { (it.sport ?: "").lowercase() == sport.lowercase() }
        TrainingLoad.trainingLoad(mine, thresholdHr, discipline, today, calibration).map {
            TrainingLoadPoint(it.date.isoformat(), it.tss, it.ctl, it.atl, it.tsb, it.ctlRamp)
        }
    }

    /**
     * What the plan's fatigue step reads (PlanAdapt), as the server's
     * `_fatigue_inputs`: today's CTL and ATL, and each activity of the last
     * [days] days with its sport and load, scored as the fitness chart scores it.
     */
    suspend fun fatigueInputs(today: CivilDate, thresholdHr: Double?, days: Long = 3):
        Triple<Double, Double, List<com.tracks.core.plan.PlanAdapt.Recent>> = cpu {
        val all = rows()
        val discipline = sources.mtbDiscipline()
        val calibration = TrainingLoad.loadCalibration(all, thresholdHr, discipline)
        val tss = TrainingLoad.tssByDate(all, thresholdHr, discipline, calibration)
        val (ctl, atl, _) = TrainingLoad.ctlAtlToday(tss, today)
        val since = today.epochDay - (days - 1)
        val recent = all.filter { it.date.epochDay in since..today.epochDay }.map {
            com.tracks.core.plan.PlanAdapt.Recent(it.date, it.sport, TrainingLoad.estimateTss(it, thresholdHr, discipline, calibration))
        }
        Triple(ctl, atl, recent)
    }

    /** (overall CTL, CTL from [family]'s load alone), as the server's `_sport_and_overall_ctl`. */
    suspend fun sportAndOverallCtl(today: CivilDate, thresholdHr: Double?, family: String): Pair<Double, Double> = cpu {
        val all = rows()
        val discipline = sources.mtbDiscipline()
        val calibration = TrainingLoad.loadCalibration(all, thresholdHr, discipline)
        val overall = TrainingLoad.tssByDate(all, thresholdHr, discipline, calibration)
        val mine = TrainingLoad.tssByDate(
            all.filter { com.tracks.core.plan.PlanBase.sportFamily((it.sport ?: "").lowercase()) == family },
            thresholdHr, discipline, calibration,
        )
        TrainingLoad.ctlAtlToday(overall, today).first to TrainingLoad.ctlAtlToday(mine, today).first
    }

    /** Today's (CTL, ATL) from the same rows the dashboard's fitness chart reads. */
    suspend fun ctlAtl(today: CivilDate, thresholdHr: Double?): Pair<Double, Double> = cpu {
        val tss = TrainingLoad.tssByDate(rows(), thresholdHr, sources.mtbDiscipline())
        val (ctl, atl, _) = TrainingLoad.ctlAtlToday(tss, today)
        ctl to atl
    }

    /**
     * Today's CTL and each recent activity's sport and load — what a
     * recommended event date is worked out from (com.tracks.core.plan.EventDate),
     * as the server's `_event_load` gathers it. Recent is EventDate.HISTORY_DAYS.
     */
    suspend fun recentLoad(today: CivilDate, thresholdHr: Double?): RecentLoad = cpu {
        val all = rows()
        val discipline = sources.mtbDiscipline()
        val calibration = TrainingLoad.loadCalibration(all, thresholdHr, discipline)
        val (ctl, _, _) = TrainingLoad.ctlAtlToday(TrainingLoad.tssByDate(all, thresholdHr, discipline, calibration), today)
        val since = today.epochDay - EventDate.HISTORY_DAYS
        RecentLoad(
            ctl,
            all.filter { it.date.epochDay > since && it.date <= today }
                .map { it.sport to TrainingLoad.estimateTss(it, thresholdHr, discipline, calibration) },
        )
    }

    /** Each day's readiness as the live gauge scored it that morning. */
    suspend fun readiness(today: CivilDate, days: Int, thresholdHr: Double?): List<ReadinessHistoryPoint> = cpu {
        Readiness.history(dayMetrics(), rows(), today, days, thresholdHr, sources.mtbDiscipline()).map {
            ReadinessHistoryPoint(
                date = it.date.isoformat(),
                score = it.score,
                hrvScore = it.hrvScore,
                sleepScore = it.sleepScore,
                restingHrScore = it.restingHrScore,
                trainingScore = it.trainingScore,
                primaryDriver = it.primaryDriver,
                confidence = it.confidence,
            )
        }
    }

    /**
     * `/coaching/today`, computed here from the same readiness and training
     * load the gauge shows.
     *
     * It used to be fetched, which put a server's readiness (computed from the
     * server's copy of the history) in the note directly above a gauge computed
     * from the phone's — so a phone that had not yet downloaded its history
     * said "readiness 94" over a gauge reading 50. One source for both.
     */
    suspend fun coaching(today: CivilDate, thresholdHr: Double?): DailyCoaching = cpu {
        val all = rows()
        val discipline = sources.mtbDiscipline()
        val readiness = Readiness.today(today, dayMetrics(), all, thresholdHr, discipline)
        val tss = TrainingLoad.tssByDate(all, thresholdHr, discipline)
        val (ctl, atl, ctl7dAgo) = TrainingLoad.ctlAtlToday(tss, today)
        val since = today.epochDay - 90
        val history = all.filter { it.date.epochDay >= since && it.sport != null }
            .map { CoachingActivity(it.sport, it.date, it.durationSeconds) }
        val goal = sources.goals().firstOrNull { it.isActive }
            ?.let { CoachingGoal(it.goalType, it.eventDate?.let(::civil), it.ctlRampPerWeek) }
        val strengthDates = sources.replica.rows("workout_session")
            .filter { !it.isTombstone }
            .mapNotNull { (it.fields["completed_at"] as? JsonPrimitive)?.content?.let(::civil) }
            .filter { it.epochDay >= since }
        val result = Coaching.computeRecommendations(
            readiness.score, readiness.confidence, ctl, atl, ctl7dAgo,
            history, goal, thresholdHr, today, strengthSessionDates = strengthDates,
        )
        val sig = result.signal
        return@cpu DailyCoaching(
            date = today.isoformat(),
            readiness = com.tracks.core.api.Readiness(
                score = readiness.score,
                hrvScore = readiness.hrvScore,
                sleepScore = readiness.sleepScore,
                restingHrScore = readiness.restingHrScore,
                hrvToday = readiness.hrvToday,
                hrvBaseline = readiness.hrvBaseline,
                sleepHours = readiness.sleepHours,
                restingHrToday = readiness.restingHrToday,
                restingHrBaseline = readiness.restingHrBaseline,
                primaryDriver = readiness.primaryDriver,
            ),
            signal = com.tracks.core.api.TrainingSignal(
                ctl = sig.ctl, atl = sig.atl, tsb = sig.tsb, ctlRamp = sig.ctlRamp,
                injuryRiskWarning = sig.injuryRiskWarning, phase = sig.phase,
            ),
            recommendations = result.recommendations.map { r ->
                com.tracks.core.api.WorkoutRecommendation(
                    sport = r.sport, intensity = r.intensity, durationMinutes = r.durationMinutes.toInt(),
                    distanceKm = r.distanceKm, hrMin = r.hrMin?.toInt(), hrMax = r.hrMax?.toInt(),
                    description = r.description, reasoning = r.reasoning, projectedTss = r.projectedTss,
                    modality = r.modality, title = r.title, focus = r.focus,
                )
            },
        )
    }

    // ── Health ──────────────────────────────────────────────────────────────

    /**
     * `/metrics/daily`: every day the watch's files describe, with the day's
     * hand-entered values (weight, hydration, calories in — the `daily_entry`
     * source) laid over it, plus days that have only hand-entered values.
     * `sleep_start`/`sleep_end` are the night's first and last stage edges, as
     * the server's model derives them.
     */
    suspend fun dailyMetrics(after: String?): List<DailyMetricFull> = cpu {
        val entries = sources.replica.rows("daily_entry").associateBy { it.str("date") }
        // Each day's own fields, worked out as it is read: the day's series
        // are parsed a page at a time and dropped (LocalLibrary.forEachDay).
        val measured = HashMap<String, Map<String, JsonElement>>()
        library.forEachDay(after) { day ->
            val fields = LinkedHashMap<String, JsonElement>(day.metrics)
            val stages = day.extra["sleep_stages"] as? JsonArray
            (stages?.firstOrNull() as? JsonObject)?.get("start")?.let { fields["sleep_start"] = it }
            (stages?.lastOrNull() as? JsonObject)?.get("end")?.let { fields["sleep_end"] = it }
            // As the server's model derives them from the same series.
            val hr = (day.extra["hr_series"] as? JsonArray).orEmpty()
                .mapNotNull { ((it as? JsonArray)?.getOrNull(1) as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() }
            if (hr.isNotEmpty()) {
                fields["avg_hr"] = JsonPrimitive(PyMath.round(hr.sum().toDouble() / hr.size, 1))
                fields["min_hr"] = JsonPrimitive(hr.min())
                fields["max_hr"] = JsonPrimitive(hr.max())
            }
            measured[day.date] = fields
        }
        val dates = (measured.keys + entries.keys.filterNotNull()).filter { after == null || it >= after }.sorted()
        return@cpu dates.map { date ->
            val fields = LinkedHashMap<String, JsonElement>()
            measured[date]?.let { fields.putAll(it) }
            // Hand-entered values over the measured ones, but not over the
            // series-derived fields, which the measured map already holds.
            entries[date]?.fields?.forEach { (k, v) -> if (k != "date") fields[k] = v }
            fields["date"] = JsonPrimitive(date)
            measured[date]?.let { m -> listOf("sleep_start", "sleep_end", "avg_hr", "min_hr", "max_hr").forEach { k -> m[k]?.let { fields[k] = it } } }
            TracksJson.decodeFromJsonElement(DailyMetricFull.serializer(), JsonObject(fields.mapValues { LocalJson.integral(it.value) }))
        }
    }

    /** `/metrics/sleep/{date}`: one night's stages, empty for a night with no timeline. */
    suspend fun sleepNight(date: String): SleepNight = cpu {
        val stages = library.day(date)?.extra?.get("sleep_stages") as? JsonArray ?: JsonArray(emptyList())
        return@cpu TracksJson.decodeFromJsonElement(
            SleepNight.serializer(),
            JsonObject(mapOf("date" to JsonPrimitive(date), "stages" to stages)),
        )
    }

    /** `/health/heart-rate`: each day's all-day heart rate, in the stress curve's shape. */
    suspend fun heartRate(after: String?): List<StressDay> = series("hr_series", after)

    /** `/metrics/stress`: the curve behind each day's average, for days that have one. */
    suspend fun stress(after: String?): List<StressDay> = series("stress_series", after)

    private suspend fun series(key: String, after: String?): List<StressDay> = cpu {
        val out = ArrayList<StressDay>()
        library.forEachDay(after) { d ->
            val points = d.extra[key] as? JsonArray ?: return@forEachDay
            if (points.isEmpty()) return@forEachDay
            out += TracksJson.decodeFromJsonElement(
                StressDay.serializer(),
                JsonObject(mapOf("date" to JsonPrimitive(d.date), "points" to points)),
            )
        }
        out
    }

    /** The health readings readiness reads, from every day this phone has. */
    fun dayMetrics(): List<DayMetric> = library.dayMetrics().mapNotNull { (day, metrics) ->
        val date = civil(day) ?: return@mapNotNull null
        fun num(k: String) = (metrics[k] as? JsonPrimitive)?.doubleOrNull
        DayMetric(date, hrv = num("hrv"), restingHr = num("resting_hr"), sleepHours = num("sleep_hours"), sleepScore = num("sleep_score"))
    }

    private fun civil(iso: String): CivilDate? = runCatching {
        CivilDate(iso.substring(0, 4).toInt(), iso.substring(5, 7).toInt(), iso.substring(8, 10).toInt())
    }.getOrNull()
}

/** Today's CTL and the recent activities' (sport, TSS) — [LocalMetrics.recentLoad]. */
data class RecentLoad(val ctl: Double, val activities: List<Pair<String?, Double>>) {
    /** The recommended date for an event in [sport] of [distanceMeters], from this load. */
    fun recommend(sport: String?, distanceMeters: Double?, today: CivilDate): EventDate.Advice {
        val total = activities.sumOf { it.second }
        val forSport = activities.filter { EventDate.countsToward(sport, it.first) }.sumOf { it.second }
        return EventDate.recommend(sport, distanceMeters, today, ctl, forSport, total)
    }

    companion object {
        /** No history at all: the recommendation falls back to the general rule. */
        val NONE = RecentLoad(0.0, emptyList())
    }
}
