// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.plan

import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.spec.SpecFixtures
import com.tracks.core.strengthplan.dyn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The plan's fatigue step answers exactly as the server's does
 * (spec/make_plan_adapt_fixtures.py): the same sessions shortened, moved or
 * dropped, with the same words. A plan rebuilt on the phone replaces the
 * server's, so a difference here is a different week depending on which
 * device rebuilt it last.
 */
class PlanAdaptFixtureTest {

    private val corpus = SpecFixtures.load("plan_adapt")

    private fun date(v: Any?): CivilDate =
        (v as String).split("-").let { CivilDate(it[0].toInt(), it[1].toInt(), it[2].toInt()) }

    private fun plain(v: Any?): Any? = when (v) {
        is CivilDate -> v.isoformat()
        is Int -> v.toLong()
        is Map<*, *> -> v.entries.associate { (k, x) -> k to plain(x) }
        is List<*> -> v.map(::plain)
        else -> v
    }

    @Test
    fun every_situation_is_answered_as_the_server_answers_it() {
        val cases = (corpus["cases"] as JsonArray).map { it.jsonObject }
        var adjusted = 0
        for (c in cases) {
            val name = dyn(c["name"]!!) as String
            @Suppress("UNCHECKED_CAST")
            val workouts = (dyn(c["workouts"]!!) as List<Map<String, Any?>>).map { w ->
                LinkedHashMap(w).also { it["scheduled_date"] = date(w["scheduled_date"]) }
            }
            @Suppress("UNCHECKED_CAST")
            val recent = (dyn(c["recent"]!!) as List<List<Any?>>).map {
                PlanAdapt.Recent(date(it[0]), it[1] as String?, (it[2] as Number).toDouble())
            }
            val got = PlanAdapt.adaptToFatigue(
                workouts, date(dyn(c["today"]!!)),
                (dyn(c["ctl"]!!) as Number).toDouble(), (dyn(c["atl"]!!) as Number).toDouble(),
                recent, dyn(c["imperial"]!!) as Boolean,
            )
            @Suppress("UNCHECKED_CAST")
            val want = dyn(c["expect"]!!) as List<Any?>
            val have = plain(got) as List<Any?>
            assertEquals(want.size, have.size, "$name: workout count")
            for (i in want.indices) assertEquals(want[i], have[i], "$name: workout $i")
            if (want != plain(workouts)) adjusted++
        }
        assertTrue(adjusted >= 10, "the corpus should adjust plenty of plans, did $adjusted")
    }
}
