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

/**
 * Replays `spec/fixtures/running_fitness.json`: today's running fitness
 * (calculators/plan/running_fitness.py), the VDOT every running pace on the
 * phone is built from. A phone-built plan replaces the server's, so the same
 * runs have to give the same paces on either.
 */
class RunningFitnessFixtureTest {
    private val corpus = SpecFixtures.load("running_fitness")

    private fun d(e: JsonElement?): Any? = e?.let { dyn(it) }
    private fun arr(e: JsonElement?): List<JsonElement> = (e as JsonArray).toList()
    private fun obj(e: JsonElement?): JsonObject = e!!.jsonObject
    private fun date(v: Any?): CivilDate =
        (v as String).split("-").let { CivilDate(it[0].toInt(), it[1].toInt(), it[2].toInt()) }
    private fun num(v: Any?): Double? = when (v) { null -> null; is Long -> v.toDouble(); else -> v as Double }

    @Test
    fun detraining_matches_the_server() {
        for (c in arr(corpus["detraining"])) {
            val o = obj(c)
            assertEquals(num(d(o["expect"])), RunningFitness.detrainingFactor(num(d(o["days"]))!!), "$o")
        }
    }

    @Test
    fun one_runs_heart_rate_estimate_matches_the_server() {
        for (c in arr(corpus["hr_run"])) {
            val o = obj(c)
            val got = RunningFitness.hrRunVdot(
                num(d(o["speed"]))!!, num(d(o["hr"]))!!, num(d(o["max"]))!!, num(d(o["rest"]))!!)
            assertEquals(num(d(o["expect"])), got, "$o")
        }
    }

    @Test
    fun the_profile_estimate_matches_the_server() {
        for (c in arr(corpus["profile"])) {
            val o = obj(c)
            @Suppress("UNCHECKED_CAST")
            val kw = d(o["kw"]) as Map<String, Any?>
            val got = RunningFitness.profileVdot(
                date(d(o["today"])),
                sex = kw["sex"] as String?, heightCm = num(kw["height_cm"]), weightKg = num(kw["weight_kg"]),
                birthYear = (kw["birth_year"] as Long?)?.toInt(), frequencies = kw["frequencies"],
            )
            assertEquals(num(d(o["expect"])), got, "$o")
        }
    }

    @Test
    fun every_estimate_matches_the_server() {
        for (c in arr(corpus["estimates"])) {
            val o = obj(c)
            val name = d(o["name"]) as String
            val efforts = arr(o["efforts"]).map {
                @Suppress("UNCHECKED_CAST") val e = d(it) as Map<String, Any?>
                RunningFitness.Effort(date(e["date"]), num(e["distance_m"])!!, num(e["speed_mps"])!!)
            }
            val runs = arr(o["runs"]).map {
                @Suppress("UNCHECKED_CAST") val r = d(it) as Map<String, Any?>
                RunningFitness.Run(
                    date(r["date"]), r["sport"] as String?, num(r["distance_m"]), num(r["duration_s"]),
                    num(r["avg_speed"]), num(r["avg_hr"]), num(r["ascent_m"]), num(r["vo2max"]),
                )
            }
            @Suppress("UNCHECKED_CAST")
            val kw = d(o["kw"]) as Map<String, Any?>
            val got = RunningFitness.estimate(
                efforts, runs, date(d(o["today"])),
                maxHr = num(kw["max_hr"]), restingHr = num(kw["resting_hr"]),
                sex = kw["sex"] as String?, heightCm = num(kw["height_cm"]), weightKg = num(kw["weight_kg"]),
                birthYear = (kw["birth_year"] as Long?)?.toInt(), frequencies = kw["frequencies"],
            )
            @Suppress("UNCHECKED_CAST")
            val want = d(o["expect"]) as Map<String, Any?>
            assertEquals(
                RunningFitness.Estimate(
                    num(want["vdot"])!!, want["source"] as String, want["measured"] as Boolean,
                    num(want["effort_vdot"]),
                ),
                got, name,
            )
        }
    }
}
