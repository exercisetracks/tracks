// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.plan

import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.spec.SpecFixtures
import com.tracks.core.strengthplan.dyn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Replays `spec/fixtures/race_day.json`: a running race's race-day fitness
 * and form (calculators/plan/race_day.py). The phone's race plan, its goal
 * card and the guided race are predicted from these, and the training
 * plan's pace progression is the same step — so they have to land on the
 * server's numbers exactly, or the phone and the web race different races.
 */
class RaceDayFixtureTest {
    private val corpus = SpecFixtures.load("race_day")

    private fun d(e: JsonElement?): Any? = e?.let { dyn(it) }
    private fun arr(e: JsonElement?): List<JsonElement> = (e as JsonArray).toList()
    @Suppress("UNCHECKED_CAST")
    private fun obj(e: JsonElement?): Map<String, Any?> = d(e) as Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    private fun maps(v: Any?): List<Map<String, Any?>> = v as List<Map<String, Any?>>
    private fun date(v: Any?): CivilDate =
        (v as String).split("-").let { CivilDate(it[0].toInt(), it[1].toInt(), it[2].toInt()) }
    private fun num(v: Any?): Double? = when (v) { null -> null; is Long -> v.toDouble(); else -> v as Double }

    @Suppress("UNCHECKED_CAST")
    private fun byDay(v: Any?): Map<Long, Double> =
        (v as Map<String, Any?>).entries.associate { (k, t) -> date(k).epochDay to num(t)!! }

    @Test
    fun the_constants_are_the_servers() {
        val c = obj(corpus["constants"])
        assertEquals(num(c["weekly_gain"]), RaceDay.WEEKLY_GAIN)
        assertEquals(num(c["max_gain"]), RaceDay.MAX_GAIN)
        assertEquals((c["adherence_days"] as Long).toInt(), RaceDay.ADHERENCE_DAYS)
        assertEquals(num(c["prior_minutes"]), RaceDay.PRIOR_MINUTES)
        assertEquals(num(c["manual_credit"]), RaceDay.MANUAL_CREDIT)
        @Suppress("UNCHECKED_CAST")
        val transfer = (c["transfer"] as Map<String, Any?>).mapValues { num(it.value)!! }
        assertEquals(transfer, RaceDay.TRANSFER)
    }

    @Test
    fun the_race_day_vdot_is_the_plans_race_week() {
        for (e in arr(corpus["projection"])) {
            val c = obj(e)
            val today = date(c["today"])
            val race = date(c["race"])
            assertEquals(c["weeks"] as Long, RaceDay.planWeeks(today, race), "$c")
            assertEquals(num(c["step"]), RaceDay.vdotStep(RaceDay.planWeeks(today, race)), "$c")
            assertEquals(num(c["expect"]), RaceDay.raceDayVdot(num(c["vdot"])!!, today, race, num(c["adherence"])!!), "$c")
        }
    }

    @Test
    fun adherence_matches_the_server_whatever_order_the_rows_come_in() {
        val rng = Random(7)
        for (e in arr(corpus["adherence"])) {
            val c = obj(e)
            val planned = maps(c["planned"]).map {
                RaceDay.Session(date(it["date"]), it["sport"] as String?, it["workout_type"] as String?,
                    num(it["minutes"]), it["complete"] as Boolean, it["recorded"] as Boolean)
            }
            val done = maps(c["done"]).map { RaceDay.Done(date(it["date"]), it["sport"] as String?, num(it["minutes"])) }
            val today = date(c["today"])
            val want = num(c["expect"])
            assertEquals(want, RaceDay.adherence(planned, done, today), c["name"] as String)
            // The replica returns rows in its own order, never the server's.
            assertEquals(want, RaceDay.adherence(planned.shuffled(rng), done.shuffled(rng), today), "${c["name"]} shuffled")
        }
    }

    @Test
    fun a_days_planned_load_is_the_same_sum_in_any_order() {
        val rng = Random(11)
        for (e in arr(corpus["load"])) {
            val c = obj(e)
            @Suppress("UNCHECKED_CAST")
            val sessions = (c["sessions"] as List<List<Any?>>).map { date(it[0]) to num(it[1])!! }
            val want = byDay(c["expect"])
            assertEquals(want, RaceDay.plannedLoadByDay(sessions))
            assertEquals(want, RaceDay.plannedLoadByDay(sessions.shuffled(rng)))
        }
    }

    @Test
    fun race_morning_form_matches_the_server() {
        for (e in arr(corpus["form"])) {
            val c = obj(e)
            val got = RaceDay.raceDayTsb(byDay(c["history"]), byDay(c["planned"]), date(c["today"]), date(c["race"]))
            assertEquals(num(c["expect"]), got, c["name"] as String)
        }
    }

    @Test
    fun freshness_matches_the_server() {
        for (e in arr(corpus["freshness"])) {
            val c = obj(e)
            assertEquals(num(c["expect"]), RaceDay.freshnessFactor(num(c["tsb"])), "$c")
        }
    }
}
