// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.plan

import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.spec.SpecFixtures
import com.tracks.core.strengthplan.dyn
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Replays spec/fixtures/moved_workouts.json: what a regeneration does with
 * the workouts the user moved, as the server decides it. The phone
 * regenerates offline, so any disagreement is a moved workout that keeps its
 * day on one device and snaps back (or doubles up) on another.
 */
class MovedWorkoutsFixtureTest {
    private val corpus = SpecFixtures.load("moved_workouts")

    private fun date(s: Any?) = (s as String).split("-").let { CivilDate(it[0].toInt(), it[1].toInt(), it[2].toInt()) }

    @Test
    fun the_role_table_is_the_servers() {
        @Suppress("UNCHECKED_CAST")
        assertEquals(dyn(corpus["roles"]!!) as Map<String, String>, MovedWorkouts.ROLES)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun every_case_matches_the_server() {
        for (case in corpus["cases"]!!.jsonArray) {
            val c = dyn(case.jsonObject) as Map<String, Any?>
            val generated = (c["generated"] as List<Map<String, Any?>>).map { g ->
                LinkedHashMap(g).also { it["scheduled_date"] = date(g["scheduled_date"]) } as MutableMap<String, Any?>
            }
            val moved = (c["moved"] as List<Map<String, Any?>>).map {
                MovedWorkouts.Moved(it["uid"] as String, date(it["scheduled_date"]), it["sport"] as String?,
                    it["workout_type"] as String?, it["is_complete"] == true)
            }
            val got = MovedWorkouts.keep(generated, moved, date(c["today"]))
            val expect = c["expect"] as Map<String, Any?>
            val kept = generated.indices.filter { i -> got.remaining.any { it === generated[i] } }.map { it.toLong() }
            assertEquals(expect["kept"], kept, "${c["name"]}: kept")
            assertEquals(expect["refresh"], got.refresh, "${c["name"]}: refresh")
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun every_completed_case_matches_the_server() {
        for (case in corpus["completed_cases"]!!.jsonArray) {
            val c = dyn(case.jsonObject) as Map<String, Any?>
            val generated = (c["generated"] as List<Map<String, Any?>>).map { g ->
                LinkedHashMap(g).also { it["scheduled_date"] = date(g["scheduled_date"]) } as MutableMap<String, Any?>
            }
            val completed = (c["completed"] as List<Map<String, Any?>>).map {
                MovedWorkouts.Completed(it["uid"] as String, date(it["scheduled_date"]), it["sport"] as String?,
                    it["workout_type"] as String?)
            }
            val remaining = MovedWorkouts.keepCompleted(generated, completed, date(c["today"]))
            val kept = generated.indices.filter { i -> remaining.any { it === generated[i] } }.map { it.toLong() }
            assertEquals((c["expect"] as Map<String, Any?>)["kept"], kept, "${c["name"]}: kept")
        }
    }
}
