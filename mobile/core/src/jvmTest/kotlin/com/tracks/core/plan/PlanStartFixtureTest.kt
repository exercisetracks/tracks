// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.plan

import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.spec.SpecFixtures
import com.tracks.core.strengthplan.dyn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Replays `spec/fixtures/plan_start.json`: where a plan starts for someone
 * with no history, from how often they said they do the sport
 * (calculators/plan/starting.py). A phone-built plan replaces the server's, so
 * a newcomer's first week has to be the same week on either.
 */
class PlanStartFixtureTest {
    private val corpus = SpecFixtures.load("plan_start")
    private val helpers = corpus["helpers"]!!.jsonObject

    private fun d(e: JsonElement?): Any? = e?.let { dyn(it) }
    private fun arr(e: JsonElement?): List<JsonElement> = (e as JsonArray).toList()
    private fun obj(e: JsonElement?): JsonObject = e!!.jsonObject
    private fun date(v: Any?): CivilDate? =
        (v as String?)?.split("-")?.let { CivilDate(it[0].toInt(), it[1].toInt(), it[2].toInt()) }
    private fun num(v: Any?): Double? = when (v) { null -> null; is Long -> v.toDouble(); else -> v as Double }
    private fun long(v: Any?): Long? = when (v) { null -> null; is Double -> v.toLong(); else -> v as Long }
    private fun plain(v: Any?): Any? = when (v) {
        is CivilDate -> v.isoformat()
        is Int -> v.toLong()
        is Map<*, *> -> v.entries.associate { (k, x) -> k to plain(x) }
        is List<*> -> v.map(::plain)
        else -> v
    }

    @Test
    fun the_levels_and_their_starting_points_match_the_server() {
        assertEquals(d(helpers["levels"]), PlanStart.LEVELS)
        assertEquals(d(helpers["ramp_weeks"]), PlanStart.RAMP_WEEKS.toLong())
        for (c in arr(helpers["starts"])) {
            val o = obj(c)
            val level = d(o["level"]) as String?
            val got = PlanStart.noHistoryStart(level)?.let {
                mapOf("weekly_km_share" to it.weeklyKmShare, "effective_weeks" to it.effectiveWeeks,
                    "ctl" to it.ctl, "intensity" to it.intensity)
            }
            @Suppress("UNCHECKED_CAST")
            val want = (d(o["expect"]) as Map<String, Any?>?)?.mapValues { num(it.value) }
            assertEquals(want, got, "level=$level")
        }
    }

    @Test
    fun a_family_reads_its_own_answer_or_borrows_a_related_one() {
        for (c in arr(helpers["frequency_for"])) {
            val o = obj(c)
            @Suppress("UNCHECKED_CAST")
            val map = d(o["map"]) as? Map<String, Any?>
            val family = d(o["family"]) as String
            assertEquals(d(o["expect"]), PlanStart.frequencyFor(map, family), "map=$map family=$family")
        }
    }

    @Test
    fun the_first_weeks_shorten_and_ease_back_like_the_server() {
        for (c in arr(helpers["intensity"])) {
            val o = obj(c)
            val level = d(o["level"]) as String?
            val week = long(d(o["week"]))!!
            assertEquals(num(d(o["expect"])), PlanStart.startIntensity(PlanStart.noHistoryStart(level), week),
                "level=$level week=$week")
        }
    }

    @Test
    fun event_plans_with_no_history_start_where_the_server_starts_them() {
        for (c in arr(corpus["plans"])) {
            val o = obj(c)
            val name = d(o["name"])
            @Suppress("UNCHECKED_CAST")
            val g = d(o["goal"]) as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val kw = d(o["kw"]) as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val history = (d(o["history"]) as List<Map<String, Any?>>).map {
                HistoryActivity(it["sport"] as String?, date(it["started"]), num(it["distance"]))
            }
            val got = PlanGenerator.generateTrainingPlan(
                PlanGoal(
                    eventDate = date(g["event_date"]),
                    eventSport = g["event_sport"] as String?,
                    eventDistanceMeters = num(g["event_distance_meters"]),
                    daysPerWeek = long(g["days_per_week"]),
                    planIntensity = num(g["plan_intensity"]),
                ),
                history, emptyList(), date(d(o["today"]))!!,
                baseEffectiveWeeks = num(kw["base_effective_weeks"]),
                imperial = kw["imperial"] as Boolean? ?: false,
                activityFrequency = d(o["level"]) as String?,
            )
            assertWorkouts(d(o["workouts"]), got, "$name")
        }
    }

    @Test
    fun fitness_plans_with_no_load_start_where_the_server_starts_them() {
        for (c in arr(corpus["fitness"])) {
            val o = obj(c)
            val name = d(o["name"])
            @Suppress("UNCHECKED_CAST")
            val g = d(o["goal"]) as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val sports = g["fitness_sports"] as List<String>?
            @Suppress("UNCHECKED_CAST")
            val frequencies = d(o["frequencies"]) as Map<String, Any?>?
            val got = FitnessPlan.generate(
                PlanGoal(
                    eventDate = null,
                    eventSport = g["event_sport"] as String?,
                    daysPerWeek = long(g["days_per_week"]),
                    ctlRampPerWeek = num(g["ctl_ramp_per_week"]),
                    fitnessSports = sports,
                ),
                emptyList(), date(d(o["today"]))!!, num(d(o["ctl"]))!!, num(d(o["atl"]))!!,
                activityFrequency = d(o["level"]) as String?,
                activityFrequencies = frequencies,
            )
            assertWorkouts(d(o["workouts"]), got, "$name")
        }
    }

    /** The corpus is only worth replaying if the answer moves the plan. */
    @Test
    fun a_newcomer_and_a_regular_get_different_first_weeks() {
        val plans = arr(corpus["plans"]).associate { d(obj(it)["name"]) to d(obj(it)["workouts"]) }
        assertNotEquals(plans["run_10k_never"], plans["run_10k_5_plus"])
        assertNotEquals(plans["run_10k_never"], plans["run_10k_None"])
    }

    private fun assertWorkouts(wantAny: Any?, got: PlanGenerator.Generated, name: String) {
        @Suppress("UNCHECKED_CAST")
        val want = wantAny as List<Any?>
        val have = plain(got.workouts) as List<Any?>
        assertEquals(want.size, have.size, "$name: workout count")
        for (i in want.indices) assertEquals(want[i], have[i], "$name: workout $i")
    }
}
