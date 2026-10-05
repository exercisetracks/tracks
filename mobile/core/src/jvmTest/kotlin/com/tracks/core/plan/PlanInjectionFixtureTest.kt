// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.plan

import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.spec.SpecFixtures
import com.tracks.core.strengthplan.PlanLibrary
import com.tracks.core.strengthplan.StrengthGoal
import com.tracks.core.strengthplan.StretchPlanning
import com.tracks.core.strengthplan.dyn
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A plan the phone generates is the server's plan: endurance sessions plus the
 * strength, mobility and stretch-flow sessions the server injects after them,
 * in the same order, over the same bundled library.
 *
 * Without this, a phone-generated plan was endurance-only — a user who asked
 * for strength got none until a server regenerated the plan, and the two plans
 * differed in which days carried a stretch flow.
 */
class PlanInjectionFixtureTest {
    private val corpus = SpecFixtures.load("plan_injection")

    private fun date(v: Any?): CivilDate? =
        (v as String?)?.split("-")?.let { CivilDate(it[0].toInt(), it[1].toInt(), it[2].toInt()) }

    private fun plain(v: Any?): Any? = when (v) {
        is CivilDate -> v.isoformat()
        is Int -> v.toLong()
        is Map<*, *> -> v.entries.associate { (k, x) -> k to plain(x) }
        is List<*> -> v.map(::plain)
        is Set<*> -> v.map(::plain)
        else -> v
    }

    @Suppress("UNCHECKED_CAST")
    private fun workouts(e: JsonElement?): MutableList<MutableMap<String, Any?>> =
        (dyn(e!!) as List<Map<String, Any?>>).map { w ->
            LinkedHashMap(w).also { it["scheduled_date"] = date(w["scheduled_date"]) }
        }.toMutableList()

    @Suppress("UNCHECKED_CAST")
    private fun strings(c: JsonObject, key: String): Set<String> = (dyn(c[key]!!) as List<String>).toSet()

    /**
     * The bundled library passes the same stretches through the animation gate
     * as the server's does — the candidate pool is where a missing manifest
     * entry or preference rule would first show.
     */
    @Test
    fun the_stretch_candidate_pool_matches_the_server() {
        for (e in corpus["cases"]!!.jsonArray) {
            val c = e.jsonObject
            val got = StretchPlanning.buildCandidates(
                PlanLibrary.stretches, emptyList(), { _, _ -> null },
                strings(c, "stretch_preferred"), strings(c, "stretch_excluded"),
            ).map { it.name }
            assertEquals(dyn(c["candidates"]!!), got, c["name"].toString())
        }
    }

    @Test
    fun the_whole_assembled_plan_matches_the_server() {
        for (e in corpus["cases"]!!.jsonArray) {
            val c = e.jsonObject
            val name = c["name"].toString()
            val candidates = StretchPlanning.buildCandidates(
                PlanLibrary.stretches, emptyList(), { _, _ -> null },
                strings(c, "stretch_preferred"), strings(c, "stretch_excluded"),
            )
            val strength = if (dyn(c["include_strength"]!!) == true) StrengthInputs(
                goal = StrengthGoal(
                    strengthTier = (dyn(c["tier"]!!) as Long).toInt(),
                    strengthDaysPerWeek = (dyn(c["strength_days"]!!) as Long?)?.toInt(),
                    eventDate = date(dyn(c["event"]!!)),
                ),
                eventSport = dyn(c["sport"]!!) as String,
                equipment = (dyn(c["equipment"]!!) as List<*>).map { it as String },
                library = PlanLibrary.exercises,
                units = dyn(c["units"]!!) as String,
                preferred = strings(c, "preferred"),
                excluded = strings(c, "excluded"),
                experience = dyn(c["experience"]!!) as String?,
            ) else null
            val got = PlanInjection.inject(
                workouts(c["endurance"]), strength, candidates,
                today = date(dyn(c["today"]!!))!!,
                regenSalt = dyn(c["salt"]!!) as String,
            )
            val want = dyn(c["expected"]!!) as List<*>
            assertEquals(want.size, got.size, "$name: workout count")
            want.zip(got).forEachIndexed { i, (w, g) -> assertEquals(w, plain(g), "$name: workout $i") }
        }
    }
}
