// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.metrics

import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.parse.PyMath
import com.tracks.core.plan.Matching
import com.tracks.core.time.ZoneOffsets
import com.tracks.core.spec.SpecFixtures
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Replays `spec/fixtures/metrics.json` — the server's own metrics functions,
 * run over synthetic histories — through the phone's port.
 *
 * Exact equality throughout, doubles compared bit for bit through
 * `Double.equals`. Every figure here is either rounded by `round(x, n)` or
 * built from IEEE operations both languages perform identically, so any
 * difference is a real divergence; see [PyMath] for the three that are not
 * IEEE's.
 */
class MetricsFixtureTest {

    private val corpus = SpecFixtures.load("metrics")

    // ── JSON helpers ─────────────────────────────────────────────────────────

    private fun JsonObject.opt(k: String): JsonElement? = this[k]?.takeUnless { it is JsonNull }
    private fun JsonObject.d(k: String): Double? = opt(k)?.jsonPrimitive?.content?.toDouble()
    private fun JsonObject.l(k: String): Long? = opt(k)?.jsonPrimitive?.content?.toLong()
    private fun JsonObject.i(k: String): Int? = opt(k)?.jsonPrimitive?.content?.toInt()
    private fun JsonObject.s(k: String): String? = opt(k)?.jsonPrimitive?.content
    private fun JsonObject.list(k: String) = this[k]!!.jsonArray.map { it.jsonObject }
    private fun date(iso: String): CivilDate =
        iso.take(10).split("-").let { CivilDate(it[0].toInt(), it[1].toInt(), it[2].toInt()) }

    /**
     * The activity as [com.tracks.core.local.LocalLibrary.metricActivities]
     * builds it: its day is the start's day in the account's [zone], by
     * [Matching.localDate] — the server's `activity_local_date`, which the
     * fixture's own figures were bucketed by.
     */
    private fun activity(o: JsonObject, zone: String? = null) = MetricActivity(
        id = o.l("id")!!, date = date(Matching.localDate(o.s("started_at")!!, ZoneOffsets.of(zone))!!),
        sport = o.s("sport"),
        distanceMeters = o.d("distance_meters"), durationSeconds = o.l("duration_seconds"),
        avgHeartRate = o.i("avg_heart_rate"), maxHeartRate = o.i("max_heart_rate"),
        trainingStressScore = o.d("training_stress_score"), effectiveTss = o.d("effective_tss"),
        vo2maxEstimate = o.d("vo2max_estimate"), avgPower = o.i("avg_power"),
        normalizedPower = o.i("normalized_power"), totalAscent = o.d("total_ascent"),
    )

    private fun metric(o: JsonObject) = DayMetric(
        date(o.s("date")!!), o.d("hrv"), o.d("resting_hr"), o.d("sleep_hours"), o.d("sleep_score"),
    )

    private fun thr(key: String): Double? = if (key == "None") null else key.toDouble()

    private fun load(o: JsonObject) = LoadPoint(
        date(o.s("date")!!), o.d("tss")!!, o.d("ctl")!!, o.d("atl")!!, o.d("tsb")!!, o.d("ctl_ramp"),
    )

    // ── Python numerics ──────────────────────────────────────────────────────

    @Test
    fun rounding_and_formatting_agree_with_python_on_every_pinned_value() {
        val py = corpus["py_math"]!!.jsonObject
        for (c in py.list("round")) {
            val x = c.d("x")!!
            val n = c.i("n")
            if (n == null) {
                assertEquals(c.d("round")!!, PyMath.roundToLong(x).toDouble(), "round($x)")
            } else {
                assertEquals(c.d("round")!!, PyMath.round(x, n), "round($x, $n)")
                assertEquals(c.s("fixed")!!, PyMath.fixed(x, n), "f\"{$x:.${n}f}\"")
            }
        }
    }

    @Test
    fun mean_and_pstdev_are_exact_rational_like_pythons_statistics() {
        val py = corpus["py_math"]!!.jsonObject
        for (c in py.list("stats")) {
            val data = c["data"]!!.jsonArray.map { it.jsonPrimitive.content.toDouble() }
            assertEquals(c.d("mean")!!, PyMath.mean(data), "mean $data")
            assertEquals(c.d("pstdev")!!, PyMath.pstdev(data), "pstdev $data")
        }
        for (c in py.list("median")) {
            val data = c["data"]!!.jsonArray.map { it.jsonPrimitive.content.toDouble() }
            assertEquals(c.d("median")!!, PyMath.median(data), "median $data")
        }
    }

    // ── Training load ────────────────────────────────────────────────────────

    @Test
    fun tss_is_estimated_exactly_as_the_server_does() {
        for (c in corpus.list("estimate_tss")) {
            val a = activity(c.jsonObject("activity"))
            val disc = c.s("mtb_discipline")
            assertEquals(c.d("expect")!!, TrainingLoad.estimateTss(a, c.d("threshold_hr"), disc), "$a @ ${c.d("threshold_hr")} $disc")
        }
    }

    private fun calibration(o: JsonElement?): Map<String, Double> =
        (o as? JsonObject)?.mapValues { it.value.jsonPrimitive.content.toDouble() } ?: emptyMap()

    @Test
    fun the_athletes_calibration_is_read_from_history_as_the_server_reads_it() {
        val cal = corpus["calibration"]!!.jsonObject
        for ((name, h) in cal["histories"]!!.jsonObject) {
            val acts = h.jsonObject.list("activities").map(::activity)
            for ((thr, want) in h.jsonObject["expect"]!!.jsonObject) {
                assertEquals(calibration(want), TrainingLoad.loadCalibration(acts, thr(thr)), "$name @ $thr")
            }
        }
        for ((h, acts) in histories()) {
            for (c in h.list("calibration")) {
                assertEquals(
                    calibration(c["expect"]),
                    TrainingLoad.loadCalibration(acts, c.d("threshold_hr"), c.s("mtb_discipline")),
                    "seed ${h.s("seed")} $c",
                )
            }
        }
    }

    @Test
    fun an_estimate_is_scaled_by_the_calibration_exactly_as_the_server_scales_it() {
        for (c in corpus["calibration"]!!.jsonObject.list("scaled")) {
            val a = activity(c.jsonObject("activity"))
            assertEquals(
                c.d("expect")!!,
                TrainingLoad.estimateTss(a, null, c.s("mtb_discipline"), calibration(c["calibration"])),
                "$a ${c["calibration"]}",
            )
        }
    }

    @Test
    fun ctl_atl_and_tsb_match_through_gaps_and_single_days() {
        for (c in corpus.list("ctl_atl_tsb")) {
            val loads = c.list("daily_loads").map { date(it.s("date")!!) to it.d("tss")!! }
            assertEquals(c.list("expect").map(::load), TrainingLoad.calculateCtlAtlTsb(loads))
        }
    }

    @Test
    fun the_effective_threshold_follows_the_mode_and_treats_zero_as_unset() {
        for (c in corpus.list("threshold_hr")) {
            val s = c.opt("settings")?.jsonObject?.let {
                ThresholdSettings(it.s("threshold_hr_mode"), it.i("threshold_hr_manual"), it.i("threshold_hr_auto"))
            }
            assertEquals(c.d("expect"), TrainingLoad.effectiveThresholdHr(s), "$s")
        }
    }

    // ── Readiness ────────────────────────────────────────────────────────────

    private fun readiness(o: JsonObject) = ReadinessResult(
        o.d("score")!!, o.d("hrv_score")!!, o.d("sleep_score")!!, o.d("resting_hr_score")!!,
        o.d("hrv_today"), o.d("hrv_baseline"), o.d("sleep_hours"), o.d("garmin_sleep_score"),
        o.d("resting_hr_today"), o.d("resting_hr_baseline"), o.d("training_score"),
        o.s("primary_driver")!!, o.s("confidence")!!, o["notes"]!!.jsonArray.map { it.jsonPrimitive.content },
    )

    @Test
    fun readiness_scores_breakdowns_and_notes_match() {
        for (c in corpus.list("readiness")) {
            val got = Readiness.compute(
                c.opt("today")?.jsonObject?.let(::metric),
                c.list("recent").map(::metric),
                tsb = c.d("tsb"), acuteLoad = c.d("acute_load"), chronicLoad = c.d("chronic_load"),
            )
            assertEquals(readiness(c.jsonObject("expect")), got, "$c")
        }
    }

    // ── Whole histories ──────────────────────────────────────────────────────

    private fun histories() = corpus.list("histories").map { h ->
        h to h.list("activities").map { activity(it, h.s("timezone")) }
    }

    @Test
    fun the_fitness_series_matches_over_a_long_awkward_history() {
        for ((h, acts) in histories()) {
            for ((key, series) in h.jsonObject("tload")) {
                val expected = series.jsonArray.map { load(it.jsonObject) }
                assertEquals(expected, TrainingLoad.trainingLoad(acts, thr(key), today = date(h.s("today")!!)), "seed ${h.s("seed")} thr $key")
            }
        }
    }

    @Test
    fun dashboard_slices_match() {
        for ((h, acts) in histories()) {
            val s = h.jsonObject("summary")
            assertEquals(
                DashboardStats.Summary(
                    s.i("activity_count")!!, s.d("total_distance_km"), s.d("total_duration_hours"),
                    s.i("sport_count")!!, s.i("device_count")!!, s.d("avg_distance_km"), s.d("avg_duration_minutes"),
                ),
                DashboardStats.summary(acts, 3),
            )
            assertEquals(
                h.list("by_sport").map {
                    DashboardStats.SportBreakdown(
                        it.s("sport")!!, it.i("activity_count")!!, it.d("total_distance_km"),
                        it.d("total_duration_hours"), it.d("avg_duration_minutes"),
                    )
                },
                DashboardStats.bySport(acts),
            )
            assertEquals(
                h.list("calendar").map { date(it.s("date")!!) to it.i("count")!! },
                DashboardStats.activityCalendar(acts),
            )
            assertEquals(
                h.list("vo2max").map { date(it.s("date")!!) to it.d("value")!! },
                DashboardStats.vo2maxHistory(acts),
            )
            // No device reading anywhere: estimated from running pace instead.
            assertEquals(
                h.list("vo2max_from_pace").map { date(it.s("date")!!) to it.d("value")!! },
                DashboardStats.vo2maxHistory(acts.map { it.copy(vo2maxEstimate = null) }),
            )
            assertEquals(
                h.list("weekly_volume").map {
                    DashboardStats.WeeklyVolume(
                        date(it.s("week_start")!!), it.d("distance_km"), it.d("duration_hours"), it.i("activity_count")!!,
                    )
                },
                DashboardStats.weeklyVolume(acts),
            )
            for ((key, pts) in h.jsonObject("activity_load")) {
                assertEquals(
                    pts.jsonArray.map { it.jsonObject }.map {
                        DashboardStats.ActivityLoad(it.l("activity_id")!!, date(it.s("date")!!), it.s("sport"), it.d("tss")!!)
                    },
                    DashboardStats.activityLoad(acts, thr(key)),
                )
            }
        }
    }

    @Test
    fun trends_with_monotony_and_strain_match_for_every_bucket() {
        for ((h, acts) in histories()) {
            for ((key, rows) in h.jsonObject("trends")) {
                val (bucket, t) = key.split(":")
                val b = DashboardStats.Bucket.valueOf(bucket.replaceFirstChar { it.uppercase() })
                val expected = rows.jsonArray.map { it.jsonObject }.map {
                    DashboardStats.TrendBucket(
                        date(it.s("period_start")!!), it.i("activity_count")!!, it.d("total_distance_km"),
                        it.d("total_duration_hours"), it.d("total_tss"), it.d("monotony"), it.d("strain"),
                    )
                }
                assertEquals(expected, DashboardStats.trends(acts, thr(t), b), "seed ${h.s("seed")} $key")
            }
        }
    }

    @Test
    fun the_live_load_behind_the_readiness_gauge_matches() {
        for ((h, acts) in histories()) {
            val live = h.jsonObject("live_load")
            val tss = TrainingLoad.tssByDate(acts, live.d("threshold_hr"))
            for (p in live.list("points")) {
                val today = date(p.s("today")!!)
                val (ctl, atl, ctl7) = TrainingLoad.ctlAtlToday(tss, today)
                assertEquals(Triple(p.d("ctl"), p.d("atl"), p.d("ctl_7d_ago")), Triple(ctl, atl, ctl7), "$today")
                assertEquals(p.d("acute")!!, TrainingLoad.acuteLoadToday(tss, today), "$today")
            }
        }
        assertEquals(Triple(0.0, 0.0, null), TrainingLoad.ctlAtlToday(emptyMap(), CivilDate(2026, 1, 1)))
    }

    @Test
    fun readiness_history_matches_day_by_day() {
        for ((h, acts) in histories()) {
            val r = h.jsonObject("readiness_history")
            val ids = r["activity_ids"]!!.jsonArray.map { it.jsonPrimitive.content.toLong() }.toSet()
            val got = Readiness.history(
                r.list("metrics").map(::metric), acts.filter { it.id in ids }, date(r.s("today")!!), r.i("days")!!,
                r.d("threshold_hr"),
            )
            val expected = r.list("expect").map {
                Readiness.HistoryPoint(
                    date(it.s("date")!!), it.d("score")!!, it.d("hrv_score")!!, it.d("sleep_score")!!,
                    it.d("resting_hr_score")!!, it.d("training_score"), it.s("primary_driver")!!, it.s("confidence")!!,
                )
            }
            // Exact on everything shown to a user. The unrounded training score
            // goes through exp(), and the JVM's Math.exp and glibc's exp may
            // differ in the last bit (Android's bionic is a third libm), so
            // that one field is compared to a few ulps rather than bit for bit.
            assertEquals(expected.size, got.size)
            expected.zip(got).forEach { (e, g) ->
                assertEquals(e.copy(trainingScore = null), g.copy(trainingScore = null))
                if (e.trainingScore == null || g.trainingScore == null) assertEquals(e.trainingScore, g.trainingScore)
                else assertEquals(e.trainingScore, g.trainingScore, 4 * Math.ulp(e.trainingScore))
            }
        }
    }

    // ── Performance and thresholds ───────────────────────────────────────────

    private fun pairs(o: JsonObject, k: String) = o[k]!!.jsonArray.map {
        val a = it.jsonArray
        a[0].jsonPrimitive.content.toInt() to a[1].jsonPrimitive.content.toDouble()
    }

    @Test
    fun power_and_pace_curves_and_race_predictions_match() {
        for (c in corpus.list("performance")) {
            val power = pairs(c, "power_bests")
            val pace = pairs(c, "pace_bests")
            assertEquals(
                c.list("power_curve").map { Performance.PowerPoint(it.i("duration_seconds")!!, it.d("avg_watts")!!) },
                Performance.powerCurve(power),
            )
            assertEquals(
                c.list("pace_curve").map {
                    Performance.PacePoint(it.i("distance_meters")!!, it.d("avg_speed_mps")!!, it.s("pace_per_km")!!)
                },
                Performance.paceCurve(pace),
            )
            assertEquals(
                c.list("race_predictions").map {
                    Performance.RacePrediction(
                        it.i("distance_meters")!!, it.s("distance_label")!!, it.l("predicted_time_seconds")!!,
                        it.s("formatted_time")!!, it.i("reference_distance_meters")!!,
                        it.s("is_actual")!!.toBoolean(),
                    )
                },
                Performance.racePredictions(Performance.bestSpeeds(pace)),
            )
        }
    }

    @Test
    fun auto_threshold_hr_and_ftp_match_including_the_rolling_window() {
        for (c in corpus.list("user_stats")) {
            val acts = c.list("activities").map {
                AutoThresholds.Candidate(
                    it.l("id")!!, null, null, it.i("avg_heart_rate"), it.i("max_heart_rate"),
                    it.i("avg_power"), it.i("normalized_power"),
                )
            }
            val points = c.jsonObject("points")
            val rolling = acts.associate { a ->
                val series = points[a.id.toString()]!!.jsonArray.map {
                    val p = it.jsonArray
                    p[0].jsonPrimitive.content.toDouble() to p[1].jsonPrimitive.content.toDouble()
                }
                a.id to AutoThresholds.bestRollingAvg(series)
            }
            for ((id, v) in c.jsonObject("rolling")) {
                assertEquals(v.takeUnless { it is JsonNull }?.jsonPrimitive?.content?.toDouble(), rolling[id.toLong()], "rolling $id")
            }
            assertEquals(c.l("threshold_hr"), AutoThresholds.thresholdHr(acts) { rolling[it.id] })
            assertEquals(c.l("ftp"), AutoThresholds.ftp(acts) { rolling[it.id] })
        }
    }

    private fun JsonObject.jsonObject(k: String): JsonObject = this[k]!!.jsonObject
}
