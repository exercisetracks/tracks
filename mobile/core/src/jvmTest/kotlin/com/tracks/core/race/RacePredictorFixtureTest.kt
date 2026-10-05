// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.race

import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.plan.RunningFitness
import com.tracks.core.spec.SpecFixtures
import com.tracks.core.spec.SpecFixtures.cases
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** Replays spec/fixtures/race_predictor.json — the server's race predictor. */
class RacePredictorFixtureTest {

    private val fx = SpecFixtures.load("race_predictor")

    private fun JsonElement?.str(): String? = if (this == null || this is JsonNull) null else jsonPrimitive.content
    private fun JsonElement?.dbl(): Double? = if (this == null || this is JsonNull) null else jsonPrimitive.doubleOrNull
    private fun JsonElement?.int(): Int? = if (this == null || this is JsonNull) null else jsonPrimitive.intOrNull

    private val courses: Map<String, List<Segment>> = fx["courses"]!!.jsonObject.mapValues { (_, v) ->
        v.jsonArray.map {
            val o = it.jsonObject
            Segment(o["distance_m"].dbl()!!, o["gradient"].dbl()!!, o["elevation_gain_m"].dbl()!!)
        }
    }
    private val paths: Map<String, List<Pair<Double, Double>>> = fx["paths"]!!.jsonObject.mapValues { (_, v) ->
        v.jsonArray.map { p -> p.jsonArray[0].dbl()!! to p.jsonArray[1].dbl()!! }
    }

    private fun course(name: String?) = name?.let { courses[it] }

    @Test
    fun times_and_paces_format_as_the_server_formats_them() {
        for (c in fx.cases("format_time")) assertEquals(c["expected"].str(), RacePredictor.formatTime(c["s"].dbl()!!), "$c")
        for (c in fx.cases("fmt_pace")) assertEquals(c["expected"].str(), RacePredictor.fmtPace(c["s"].dbl()!!), "$c")
        for (c in fx.cases("swim_pace")) {
            assertEquals(c["expected"].str(), RacePredictor.formatSwimPace(c["css"].dbl()!!, c["i"].dbl()!!), "$c")
        }
    }

    @Test
    fun grade_weather_and_wind_models_match() {
        for (c in fx.cases("grade")) {
            val g = c["g"].dbl()!!
            assertEquals(c["mult"].dbl(), RacePredictor.gradeCostMultiplier(g), "$g")
            assertEquals(c["gap"].dbl(), RacePredictor.gradeAdjustmentFactor(g), "$g")
        }
        for (c in fx.cases("weather")) {
            assertEquals(
                c["expected"].dbl(),
                RacePredictor.weatherSlowdownFactor(c["t"].dbl()!!, c["h"].dbl()!!, c["w"].dbl()!!, c["a"].dbl()!!),
                "$c",
            )
        }
        for (c in fx.cases("wind")) {
            val p = paths.getValue(c["path"].str()!!)
            val d = c["dir"].dbl()!!
            val m = c["mps"].dbl()!!
            assertEquals(wind(c["run"]!!.jsonObject), RacePredictor.windCourseExposure(p, d, m), "$c")
            assertEquals(wind(c["bike"]!!.jsonObject), RacePredictor.cyclingWindCourseExposure(p, d, m), "$c")
        }
    }

    private fun wind(o: JsonObject) = WindExposure(o["net_headwind_mps"].dbl()!!, o["course_note"].str(), o["wind_factor"].dbl()!!)

    @Test
    fun course_statistics_match() {
        for (c in fx.cases("course")) {
            val segs = course(c["course"].str()) ?: emptyList()
            val tech = c["tech"]!!.jsonArray
            assertEquals(tech[0].dbl()!! to tech[1].str()!!, RacePredictor.technicalityFactor(segs), "$c")
            val t = c["totals"]!!.jsonObject
            assertEquals(
                CourseTotals(t["distance_m"].int()!!.toLong(), t["elevation_gain_m"].int()!!.toLong(), t["elevation_loss_m"].int()!!.toLong()),
                RacePredictor.courseTotals(segs),
            )
        }
    }

    @Test
    fun predictions_match() {
        for (c in fx.cases("run_predict")) {
            assertEquals(c["expected"].dbl(), RacePredictor.predictRaceTimeSec(c["vdot"].dbl()!!, c["d"].dbl()!!), "$c")
        }
        // Tanda's formula goes through exp, which the JVM and glibc may round
        // differently in the last place — a microsecond is far inside that.
        for (c in fx.cases("tanda")) {
            assertEquals(c["expected"].dbl()!!,
                RacePredictor.tandaMarathonSec(c["k"].dbl()!!, c["p"].dbl()!!, c["d"].dbl()!!), 1e-6, "$c")
        }
        for (c in fx.cases("run_volume")) {
            val k = c["k"].dbl()
            val indices = if (k == null) null else k to c["p"].dbl()!!
            assertEquals(
                c["expected"].dbl()!!,
                RacePredictor.predictRunningRaceSec(c["vdot"].dbl()!!, c["d"].dbl()!!, indices, c["f"].dbl()!!),
                1e-6, "$c",
            )
        }
        for (c in fx.cases("training_indices")) {
            val today = c["today"].str()!!.split("-").let { CivilDate(it[0].toInt(), it[1].toInt(), it[2].toInt()) }
            val runs = c["runs"]!!.jsonArray.map {
                val o = it.jsonObject
                val d = o["date"].str()!!.split("-")
                RunningFitness.Run(CivilDate(d[0].toInt(), d[1].toInt(), d[2].toInt()), "running",
                    distanceM = o["distance_m"].dbl(), durationS = o["duration_s"].dbl())
            }
            val want = c["expected"]?.takeIf { it !is JsonNull }?.jsonArray?.let { it[0].dbl()!! to it[1].dbl()!! }
            assertEquals(want, RacePredictor.trainingIndices(runs, today), "$c")
        }
        for (c in fx.cases("bike_predict")) {
            assertEquals(
                c["expected"].dbl(),
                RacePredictor.predictCyclingTimeSec(c["ftp"].dbl()!!, c["d"].dbl()!!, course(c["course"].str()), c["wind"].dbl()!!),
                "$c",
            )
        }
        for (c in fx.cases("swim")) {
            assertEquals(
                c["expected"].dbl(),
                RacePredictor.predictSwimTimeSec(c["css"].dbl()!!, c["d"].dbl()!!, c["ow"]!!.jsonPrimitive.booleanOrNull!!),
                "$c",
            )
        }
        for (c in fx.cases("hr")) {
            val h = c["max_hr"].int()!!
            val d = c["d"].dbl()!!
            val n = c["n"].int()!!
            assertEquals(c["run"]!!.jsonArray.map { it.int() }, RacePredictor.hrCeilings(h, d, n), "$c")
            assertEquals(c["bike"]!!.jsonArray.map { it.int() }, RacePredictor.cyclingHrCeilings(h, d, n), "$c")
        }
        // The split moves the ceilings (the slider used to leave them static).
        for (c in fx.cases("hr_split")) {
            val h = c["max_hr"].int()!!
            val d = c["d"].dbl()!!
            val n = c["n"].int()!!
            val ramp = c["ramp"]!!.jsonArray.map { it.dbl()!! }
            assertEquals(c["run"]!!.jsonArray.map { it.int() }, RacePredictor.hrCeilings(h, d, n, ramp), "$c")
            assertEquals(c["bike"]!!.jsonArray.map { it.int() }, RacePredictor.cyclingHrCeilings(h, d, n, ramp), "$c")
        }
    }

    @Test
    fun running_laps_match_lap_for_lap() {
        for (c in fx.cases("run_laps")) {
            val (laps, total) = RacePredictor.lapPaces(
                c["pred"].dbl()!!, c["d"].dbl()!!, c["spread"].dbl()!!, c["lap_km"].dbl()!!,
                course(c["course"].str()), c["hr"].int(),
            )
            assertEquals(c["laps"]!!.jsonArray.map { lap(it.jsonObject) }, laps, "$c")
            assertEquals(c["total"].dbl(), total, "$c")
        }
    }

    @Test
    fun cycling_laps_match_lap_for_lap() {
        for (c in fx.cases("bike_laps")) {
            val (laps, total) = RacePredictor.cyclingLapTargets(
                c["ftp"].dbl()!!, c["d"].dbl()!!, c["spread"].dbl()!!, c["lap_km"].dbl()!!,
                course(c["course"].str()), c["hr"].int(), c["pred"].dbl(), c["wind"].dbl()!!,
            )
            assertEquals(c["laps"]!!.jsonArray.map { lap(it.jsonObject) }, laps, "$c")
            assertEquals(c["total"].dbl(), total, "$c")
        }
    }

    private fun lap(o: JsonObject) = RaceLap(
        lap = o["lap"].int()!!,
        distanceM = o["distance_m"]!!.jsonPrimitive.content.toLong(),
        targetSecPerKm = o["target_sec_per_km"].dbl()!!,
        targetPace = o["target_pace"].str()!!,
        gradient = o["gradient"].dbl()!!,
        gradeMultiplier = o["grade_multiplier"].dbl()!!,
        gradeAdjSec = o["grade_adj_sec"].dbl()!!,
        gradeAdjPace = o["grade_adj_pace"].str()!!,
        cumulativeKm = o["cumulative_km"].dbl()!!,
        hrCeiling = o["hr_ceiling"].int(),
        targetWatts = o["target_watts"].int(),
        targetWattsPctFtp = o["target_watts_pct_ftp"].int(),
    )
}
