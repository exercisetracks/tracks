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
 * Replays spec/fixtures/goal_planning.json: the recommended event date and
 * the rules for which edits rebuild a plan, as the server decides them. The
 * web shows the server's date and this phone computes its own; a mismatch
 * is a goal form that suggests one Sunday on the phone and another in the
 * browser, with different reasons behind the same "?".
 */
class GoalPlanningFixtureTest {
    private val corpus = SpecFixtures.load("goal_planning")

    private fun date(s: Any?) = (s as String).split("-").let { CivilDate(it[0].toInt(), it[1].toInt(), it[2].toInt()) }

    private fun num(v: Any?): Double? = (v as Number?)?.toDouble()

    @Test
    @Suppress("UNCHECKED_CAST")
    fun every_recommended_date_matches_the_server() {
        for (case in corpus["event_date"]!!.jsonArray) {
            val c = dyn(case.jsonObject) as Map<String, Any?>
            val got = EventDate.recommend(
                c["sport"] as String?, num(c["distance_m"]), date(c["today"]),
                num(c["ctl"]), num(c["sport_tss"])!!, num(c["total_tss"])!!,
            )
            val expect = c["expect"] as Map<String, Any?>
            val label = "${c["sport"]} ${c["distance_m"]} ctl=${c["ctl"]} ${c["today"]}"
            assertEquals(expect["date"], got.date.isoformat(), "$label: date")
            assertEquals((expect["weeks"] as Number).toInt(), got.weeks, "$label: weeks")
            assertEquals(expect["basis"], got.basis, "$label: basis")
            assertEquals(expect["reasons"], got.reasons, "$label: reasons")
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun which_training_counts_toward_an_event_is_the_servers() {
        for (case in corpus["counts_toward"]!!.jsonArray) {
            val c = dyn(case.jsonObject) as Map<String, Any?>
            assertEquals(c["expect"], EventDate.countsToward(c["event"] as String?, c["activity"] as String?), "$c")
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun the_staleness_rules_are_the_servers() {
        assertEquals(dyn(corpus["non_plan_goal_fields"]!!) as List<String>, PlanStaleness.NON_PLAN_GOAL_FIELDS.sorted())
        assertEquals(dyn(corpus["plan_settings"]!!) as List<String>, PlanStaleness.PLAN_SETTINGS.sorted())
        for (case in corpus["goal_edits"]!!.jsonArray) {
            val c = dyn(case.jsonObject) as Map<String, Any?>
            val changed = c["changed"] as Map<String, Any?>
            assertEquals(c["expect"], PlanStaleness.goalEditStalesPlan(changed), "$changed")
        }
    }
}
