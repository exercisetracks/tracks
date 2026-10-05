// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.cycling

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
import kotlin.math.abs
import kotlin.math.ulp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Replays spec/fixtures/cycling.json — road_cycling.py and mtb.py, run by the server. */
class CyclingFixtureTest {

    private val fx = SpecFixtures.load("cycling")

    private fun JsonElement?.str(): String? = if (this == null || this is JsonNull) null else jsonPrimitive.content
    private fun JsonElement?.dbl(): Double? = if (this == null || this is JsonNull) null else jsonPrimitive.doubleOrNull
    private fun JsonElement?.int(): Int? = if (this == null || this is JsonNull) null else jsonPrimitive.intOrNull

    private fun fuel(o: JsonElement) = o.jsonObject.let {
        FuelingPlan(it["carbs_g_per_h"].int()!!, it["reminder_min"].int()!!, it["notes"].str()!!)
    }

    @Test
    fun sports_are_classified_as_the_server_classifies_them() {
        for (c in fx.cases("sports")) {
            val s = c["sport"].str()
            assertEquals(c["road"]!!.jsonPrimitive.booleanOrNull, RoadCycling.isRoadCycling(s), "$s")
            assertEquals(c["indoor"]!!.jsonPrimitive.booleanOrNull, RoadCycling.isIndoorCycling(s), "$s")
            assertEquals(c["mtb"]!!.jsonPrimitive.booleanOrNull, Mtb.isMtb(s), "$s")
        }
    }

    @Test
    fun disciplines_inferred_from_distance_match() {
        for (c in fx.cases("infer")) {
            val d = c["distance"].dbl()
            assertEquals(c["road"].str(), RoadCycling.inferDisciplineFromDistance(d))
            assertEquals(c["mtb"].str(), Mtb.inferDisciplineFromDistance(d))
        }
    }

    @Test
    fun per_discipline_factors_and_hr_ceilings_match() {
        for (c in fx.cases("disciplines")) {
            val d = c["discipline"].str()
            assertEquals(c["drafting"].dbl(), RoadCycling.draftingFactor(d), "$d")
            assertEquals(c["mtb_tss"].dbl(), Mtb.tssMultiplier(d), "$d")
            for (h in c["hr"]!!.jsonArray.map { it.jsonObject }) {
                val l = h["lthr"].int()
                assertEquals(h["road"].int(), RoadCycling.raceHrCeiling(d, l), "$d $l")
                assertEquals(h["mtb"].int(), Mtb.raceHrCeiling(d, l), "$d $l")
            }
        }
    }

    @Test
    fun duration_based_guidance_matches() {
        for (c in fx.cases("seconds")) {
            val s = c["seconds"].dbl()
            assertEquals(fuel(c["road_fuel"]!!), RoadCycling.fuelingPlan(s), "$s")
            assertEquals(fuel(c["mtb_fuel"]!!), Mtb.fuelingPlan(s), "$s")
            assertEquals(c["w_per_kg"].dbl(), RoadCycling.hillClimbTargetWPerKg(s), "$s")
        }
    }

    @Test
    fun grade_multipliers_match() {
        for (c in fx.cases("grades")) {
            val g = c["grad"].dbl()!!
            assertEquals(c["road"].dbl(), RoadCycling.gradeMultiplier(g), "$g")
            assertEquals(c["mtb"].dbl(), Mtb.gradeMultiplier(g), "$g")
        }
    }

    @Test
    fun tss_multipliers_and_pace_formatting_match() {
        for (c in fx.cases("indoor_tss")) {
            assertEquals(c["expected"].dbl(), RoadCycling.applyIndoorTssMultiplier(c["sport"].str(), c["tss"].dbl()))
        }
        for (c in fx.cases("pace")) {
            assertEquals(c["expected"].str(), Mtb.fmtPaceSecPerKm(c["sec"].dbl()!!), "${c["sec"]}")
        }
    }

    @Test
    fun the_active_goal_decides_the_discipline_as_the_server_query_does() {
        for (c in fx.cases("goals")) {
            val goals = c["goals"]!!.jsonArray.map { g ->
                val o = g.jsonObject
                GoalForDiscipline(
                    o["isActive"]!!.jsonPrimitive.booleanOrNull!!, o["goalType"].str(), o["eventSport"].str(),
                    o["eventDate"].str(), o["discipline"].str(), o["eventDistanceMeters"].dbl(),
                )
            }
            assertEquals(c["road"].str(), RoadCycling.activeDiscipline(goals), "$goals")
            assertEquals(c["mtb"].str(), Mtb.activeDiscipline(goals), "$goals")
            for (t in c["mtb_tss"]!!.jsonArray.map { it.jsonObject }) {
                assertEquals(t["expected"].dbl(), Mtb.applyTssMultiplier(t["sport"].str(), t["tss"].dbl(), goals))
            }
        }
    }

    @Test
    fun mtb_hr_only_laps_match_lap_for_lap() {
        for (c in fx.cases("laps")) {
            val i = c["input"]!!.jsonObject
            val (laps, total) = Mtb.hrOnlyLaps(
                distanceM = i["distance_m"].dbl()!!,
                totalSec = i["total_sec"].dbl()!!,
                lapKm = i["lap_km"].dbl() ?: 1.0,
                splitSpread = i["split_spread"].dbl() ?: 0.0,
                discipline = i["discipline"].str(),
                lthr = i["lthr"].int(),
                courseSegments = i["course_segments"]?.jsonArray?.map {
                    CourseSegment(it.jsonObject["distance_m"].dbl()!!, it.jsonObject["gradient"].dbl() ?: 0.0)
                },
                courseType = i["course_type"].str(),
            )
            val expected = c["laps"]!!.jsonArray.map { it.jsonObject }
            assertEquals(expected.size, laps.size, "$i")
            for ((e, a) in expected.zip(laps)) {
                assertEquals(lapOf(e), a, "$i")
            }
            // The unrounded sum goes through sin(), where the JVM and glibc may
            // differ in the last place; every rounded, user-visible field above
            // is exact.
            val want = c["total"].dbl()!!
            assertTrue(abs(want - total) <= 4 * want.ulp, "$i: $want vs $total")
        }
    }

    private fun lapOf(e: JsonObject) = MtbLap(
        lap = e["lap"].int()!!,
        distanceM = e["distance_m"]!!.jsonPrimitive.content.toLong(),
        targetSecPerKm = e["target_sec_per_km"].dbl()!!,
        targetPace = e["target_pace"].str()!!,
        gradient = e["gradient"].dbl()!!,
        gradeMultiplier = e["grade_multiplier"].dbl()!!,
        gradeAdjSec = e["grade_adj_sec"].dbl()!!,
        gradeAdjPace = e["grade_adj_pace"].str()!!,
        cumulativeKm = e["cumulative_km"].dbl()!!,
        hrCeiling = e["hr_ceiling"].int(),
    )
}
