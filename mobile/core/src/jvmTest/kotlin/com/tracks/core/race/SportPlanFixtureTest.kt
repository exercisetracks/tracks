// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.race

import com.tracks.core.spec.SpecFixtures
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

/**
 * The phone plans a cycling, mountain-bike, swim or triathlon race exactly as
 * the server does — the same laps, watts, HR ceilings and finish. Before, it
 * planned running alone and sent everything else to the web.
 */
class SportPlanFixtureTest {

    private val fx = SpecFixtures.load("race_plan")

    private fun JsonElement?.d(): Double? = if (this == null || this is JsonNull) null else jsonPrimitive.doubleOrNull
    private fun JsonElement?.i(): Int? = if (this == null || this is JsonNull) null else jsonPrimitive.intOrNull
    private fun JsonElement?.s(): String? = if (this == null || this is JsonNull) null else jsonPrimitive.content

    private fun segmentsOf(e: JsonElement?) = if (e == null || e is JsonNull) emptyList() else e.jsonArray.map {
        val o = it.jsonObject
        Segment(o["distance_m"].d()!!, o["gradient"].d()!!, o["elevation_gain_m"].d() ?: 0.0)
    }

    private fun pathOf(e: JsonElement?) = if (e == null || e is JsonNull) emptyList() else e.jsonArray.map {
        val p = it.jsonArray
        TriCourse.PathPoint(p[0].d()!!, p[1].d()!!, p.getOrNull(2).d())
    }

    /** The fields a lap is coached by, as the server writes them. */
    private fun lapKey(o: JsonObject) = listOf(o["lap"].i(), o["distance_m"].d(), o["target_sec_per_km"].d(),
        o["gradient"].d(), o["grade_multiplier"].d(), o["cumulative_km"].d(), o["hr_ceiling"].i(),
        o["target_watts"].i(), o["target_watts_pct_ftp"].i())

    private fun lapKey(l: RaceLap) = listOf(l.lap, l.distanceM.toDouble(), l.targetSecPerKm, l.gradient,
        l.gradeMultiplier, l.cumulativeKm, l.hrCeiling, l.targetWatts, l.targetWattsPctFtp)

    @Test
    fun every_sport_is_planned_as_the_server_plans_it() {
        val cases = fx["cases"]!!.jsonArray.map { it.jsonObject }
        assert(cases.size >= 15) { "corpus shrank to ${cases.size}" }
        for (c in cases) {
            val name = c["name"].s()!!
            val mt = c["metrics"]!!.jsonObject
            val splits = c["triathlon_splits_m"]?.takeIf { it !is JsonNull }?.jsonObject?.let {
                SportPlan.TriSplits(it["swim_m"].d()!!, it["bike_m"].d()!!, it["run_m"].d()!!)
            }
            val got = SportPlan.plan(
                c["family"].s()!!,
                SportPlan.Metrics(mt["vdot"].d(), mt["ftp"].d(), mt["css"].d(), mt["max_hr"].i(), mt["lthr"].i()),
                SportPlan.Inputs(
                    distanceM = c["distance_m"].d()!!, courseType = c["course_type"].s()!!,
                    splitSpread = c["split_spread"].d()!!, paceHr = c["pace_hr"]!!.jsonPrimitive.booleanOrNull!!,
                    imperial = c["imperial"]!!.jsonPrimitive.booleanOrNull!!, segments = segmentsOf(c["course_segments"]),
                    mtbDiscipline = c["mtb_discipline"].s(), cyclingDiscipline = c["cycling_discipline"].s(),
                    triathlonSplits = splits,
                    path = pathOf(c["course_path"]),
                    triLegFiles = c["tri_leg_courses"]?.takeIf { it !is JsonNull }?.jsonObject.orEmpty()
                        .mapValues { (_, v) -> TriCourse.LegFile(segmentsOf(v.jsonObject["segments"]), pathOf(v.jsonObject["path"])) },
                    triTransitionsM = c["tri_transitions_m"]?.takeIf { it !is JsonNull }?.jsonArray?.map { it.d()!! },
                ),
            )
            val e = c["expected"]!!.jsonObject
            assertEquals(e["laps"]!!.jsonArray.map { lapKey(it.jsonObject) }, got.laps.map(::lapKey), name)
            assertEquals(e["seconds"].d()!!, got.seconds, 1e-6, name)
            assertEquals(e["target_hr_ceiling"].i(), got.targetHrCeiling, name)
            assertEquals(e["climb_target_w_per_kg"].d(), got.climbTargetWPerKg, name)
            assertEquals(e["technicality_label"].s(), got.technicalityLabel, name)
            e["tri_courses"]?.takeIf { it !is JsonNull }?.jsonObject?.let { tc ->
                val got2 = TriCourse.legCourses(segmentsOf(c["course_segments"]), pathOf(c["course_path"]),
                    c["tri_leg_courses"]?.takeIf { it !is JsonNull }?.jsonObject.orEmpty()
                        .mapValues { (_, v) -> TriCourse.LegFile(segmentsOf(v.jsonObject["segments"]), pathOf(v.jsonObject["path"])) },
                    c["tri_transitions_m"]?.takeIf { it !is JsonNull }?.jsonArray?.map { it.d()!! },
                    splits ?: SportPlan.inferTriathlonSplits(c["distance_m"].d()!!))
                assertEquals(tc["transitions_m"]?.takeIf { it !is JsonNull }?.jsonArray?.map { it.d()!! }, got2.transitionsM, name)
                for ((leg, want) in tc["legs"]!!.jsonObject) {
                    val w = want.jsonObject
                    val g = got2.legs.getValue(leg)
                    assertEquals(w["source"].s(), g.source, "$name $leg")
                    assertEquals(w["path"]!!.jsonArray.size, g.path.size, "$name $leg path")
                    assertEquals(segmentsOf(w["segments"]), g.segments.orEmpty(), "$name $leg segments")
                }
            }
            val legs = e["triathlon_legs"]?.takeIf { it !is JsonNull }?.jsonArray?.map { it.jsonObject }.orEmpty()
            assertEquals(legs.map { listOf(it["leg"].s(), it["distance_m"].d(), it["target_pace"].s(), it["target_watts"].i()) },
                got.legs.map { listOf(it.leg, it.distanceM, it.targetPace, it.targetWatts) }, name)
            for ((want, leg) in legs.zip(got.legs)) {
                assertEquals(want["predicted_seconds"].d()!!, leg.seconds, 1e-6, "$name ${leg.leg}")
                val wantLaps = want["lap_paces"]?.takeIf { it !is JsonNull }?.jsonArray?.map { lapKey(it.jsonObject) }
                assertEquals(wantLaps, leg.laps?.map(::lapKey), "$name ${leg.leg}")
            }
        }
    }
}
