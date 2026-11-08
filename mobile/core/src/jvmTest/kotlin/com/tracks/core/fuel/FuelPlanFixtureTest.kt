// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.fuel

import com.tracks.core.fit.WorkoutFit
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
 * The phone plans an athlete's race food exactly as the server does — the
 * same targets, the same gel at the same minute, the same gut-training ramp,
 * and the same watch file byte for byte. Without it a phone and the web could
 * tell someone to eat different things on race day.
 */
class FuelPlanFixtureTest {

    private val fx = SpecFixtures.load("fuel_plan")

    private fun JsonElement?.d(): Double? = if (this == null || this is JsonNull) null else jsonPrimitive.doubleOrNull
    private fun JsonElement?.i(): Int? = if (this == null || this is JsonNull) null else jsonPrimitive.intOrNull
    private fun JsonElement?.s(): String? = if (this == null || this is JsonNull) null else jsonPrimitive.content

    private fun targetsOf(o: JsonObject) = FuelPlan.Targets(
        carbsGPerH = o["carbs_g_per_h"].i()!!, fluidMlPerH = o["fluid_ml_per_h"].i()!!,
        sodiumMgPerH = o["sodium_mg_per_h"].i()!!, intervalMin = o["interval_min"].i()!!,
        band = o["band"].s()!!,
        customCarbs = o["custom"]!!.jsonObject["carbs"]!!.jsonPrimitive.booleanOrNull!!,
        customFluid = o["custom"]!!.jsonObject["fluid"]!!.jsonPrimitive.booleanOrNull!!,
        customSodium = o["custom"]!!.jsonObject["sodium"]!!.jsonPrimitive.booleanOrNull!!,
        customInterval = o["custom"]!!.jsonObject["interval"]!!.jsonPrimitive.booleanOrNull!!,
        potassiumMgPerH = o["potassium_mg_per_h"].i()!!,
        customPotassium = o["custom"]!!.jsonObject["potassium"]!!.jsonPrimitive.booleanOrNull!!,
    )

    private fun productsOf(e: JsonElement) = e.jsonArray.map {
        val p = it.jsonObject
        FuelPlan.Product(p["uid"].s(), p["name"].s(), p["carbs_g"].d()!!, p["sodium_mg"].i() ?: 0,
            p["fluid_ml"].i() ?: 0, p["caffeine_mg"].i() ?: 0, p["potassium_mg"].i() ?: 0)
    }

    private fun lapsOf(e: JsonElement?) = if (e == null || e is JsonNull) null else e.jsonArray.map {
        FuelPlan.Lap(it.jsonObject["distance_m"].d()!!, it.jsonObject["target_sec_per_km"].d()!!)
    }

    @Test
    fun targets_match_the_servers() {
        for (c in fx["targets"]!!.jsonArray.map { it.jsonObject }) {
            val o = c["override"]!!.jsonObject
            val got = FuelPlan.targets(
                c["duration_min"].d()!!, c["temperature_c"].d(), c["humidity_pct"].d(),
                o["carbs"].i(), o["fluid"].i(), o["sodium"].i(), o["interval"].i(), o["potassium"].i(),
            )
            assertEquals(targetsOf(c["expected"]!!.jsonObject), got, c.toString())
        }
    }

    @Test
    fun the_timeline_takes_the_same_thing_at_the_same_minute() {
        for (c in fx["timeline"]!!.jsonArray.map { it.jsonObject }) {
            val got = FuelPlan.timeline(c["duration_min"].d()!!, targetsOf(c["targets"]!!.jsonObject),
                productsOf(c["products"]!!), lapsOf(c["laps"]))
            val e = c["expected"]!!.jsonObject
            val want = e["items"]!!.jsonArray.map {
                val o = it.jsonObject
                FuelPlan.Item(o["minute"].i()!!, o["distance_m"].i(), o["product_uid"].s(), o["name"].s(),
                    o["carbs_g"].d()!!, o["sodium_mg"].i()!!, o["fluid_ml"].i()!!, o["caffeine_mg"].i()!!,
                    o["potassium_mg"].i()!!)
            }
            fun amounts(o: JsonObject) = FuelPlan.Amounts(o["carbs_g"].i()!!, o["sodium_mg"].i()!!,
                o["fluid_ml"].i()!!, o["caffeine_mg"].i()!!, o["potassium_mg"].i()!!)
            assertEquals(want, got.items)
            assertEquals(amounts(e["totals"]!!.jsonObject), got.totals)
            assertEquals(amounts(e["per_hour"]!!.jsonObject), got.perHour)
        }
    }

    /**
     * The homemade mix: the same sip, the same timeline around it, and the
     * same recipe to the gram and the spoon — a phone that said ¼ tsp of salt
     * where the web said ⅜ would be the kind of disagreement people notice.
     */
    @Test
    fun the_homemade_mix_is_sipped_and_made_as_the_server_says() {
        for (c in fx["homemade"]!!.jsonArray.map { it.jsonObject }) {
            val tg = targetsOf(c["targets"]!!.jsonObject)
            val tl = FuelPlan.timeline(c["duration_min"].d()!!, tg, productsOf(c["products"]!!), lapsOf(c["laps"]), mix = true)
            val mix = tl.mix!!
            val sv = c["serving"]!!.jsonObject
            assertEquals(sv["carbs_g"].d(), mix.product.carbsG, c["targets"].toString())
            assertEquals(sv["fluid_ml"].i(), mix.product.fluidMl)
            assertEquals(sv["sodium_mg"].i(), mix.product.sodiumMg)
            assertEquals(sv["potassium_mg"].i(), mix.product.potassiumMg)
            assertEquals(sv["interval_min"].i(), mix.intervalMin)
            assertEquals(sv["concentration_pct"].d(), mix.concentrationPct)
            assertEquals(sv["band_pct"].i(), mix.bandPct)
            assertEquals(sv["over_band"]!!.jsonPrimitive.booleanOrNull, mix.overBand)
            assertEquals(sv["extra_carbs_g_per_h"].i(), mix.extraCarbsGPerH)

            val e = c["timeline"]!!.jsonObject
            assertEquals(e["items"]!!.jsonArray.map { it.jsonObject["product_uid"].s() to it.jsonObject["minute"].i() },
                tl.items.map { it.productUid to it.minute })
            val ph = e["per_hour"]!!.jsonObject
            assertEquals(FuelPlan.Amounts(ph["carbs_g"].i()!!, ph["sodium_mg"].i()!!, ph["fluid_ml"].i()!!,
                ph["caffeine_mg"].i()!!, ph["potassium_mg"].i()!!), tl.perHour)
            val drunk = tl.items.filter { it.productUid == FuelPlan.HOMEMADE_UID }.sumOf { it.fluidMl }
            assertEquals(c["drunk_ml"].i(), drunk)
            assertEquals(c["default_preset"].s(), FuelPlan.mixPreset(tg.carbsGPerH, null))

            for (r in c["recipes"]!!.jsonArray.map { it.jsonObject }) {
                val got = FuelPlan.homemadeRecipe(r["preset"].s()!!, r["bottle_ml"].i()!!, mix, tg.carbsGPerH, drunk)
                val x = r["expected"]!!.jsonObject
                val want = x["ingredients"]!!.jsonArray.map {
                    val o = it.jsonObject
                    FuelPlan.Ingredient(o["key"].s()!!, o["name"].s()!!, o["grams"].d(), o["ml"].d(), o["measure"].s()!!)
                }
                assertEquals(want, got.ingredients, r.toString())
                val pb = x["per_bottle"]!!.jsonObject
                assertEquals(listOf(pb["carbs_g"].i(), pb["sodium_mg"].i(), pb["potassium_mg"].i()),
                    listOf(got.perBottleCarbsG, got.perBottleSodiumMg, got.perBottlePotassiumMg))
                assertEquals(x["bottles"].i(), got.bottles)
            }
        }
    }

    @Test
    fun gut_training_ramps_and_adapts_like_the_server() {
        for (c in fx["gut"]!!.jsonArray.map { it.jsonObject }) {
            val workouts = c["workouts"]!!.jsonArray.map {
                val o = it.jsonObject
                FuelPlan.Workout(o["uid"].s()!!, o["scheduled_date"].s(), o["sport"].s(), o["workout_type"].s(),
                    o["duration_minutes"].d(), o["fuel_carbs_per_hour"].i())
            }
            val logs = c["logs"]!!.jsonArray.map {
                val o = it.jsonObject
                FuelPlan.Log(o["uid"].s(), o["planned_workout_uid"].s(), o["date"].s(), o["comfort"].i(),
                    o["carbs_g"].d(), o["duration_min"].d())
            }
            val want = c["expected"]!!.jsonObject.mapValues { it.value.i()!! }
            assertEquals(want, FuelPlan.gutTraining(c["race_carbs"].i()!!, c["race_date"].s()!!, workouts, logs))
        }
    }

    @Test
    fun the_race_watch_file_is_byte_identical() {
        for (c in fx["race_fit"]!!.jsonArray.map { it.jsonObject }) {
            val laps = c["laps"]!!.jsonArray.map {
                val o = it.jsonObject
                WorkoutFit.RaceLap(o["lap"].i()!!, o["distance_m"].d()!!, o["target_sec_per_km"].d()!!,
                    o["hr_ceiling"].i(), o["label"].s())
            }
            val fuel = c["fuel_items"]!!.jsonArray.map {
                val o = it.jsonObject
                WorkoutFit.RaceFuel(o["distance_m"].i(), o["name"].s(), o["carbs_g"].d()!!)
            }
            val bytes = WorkoutFit.race(
                c["name"].s()!!, c["sport"].s()!!, laps, c["time_created_ms"]!!.jsonPrimitive.content.toLong(),
                paceCoaching = c["pace_coaching"]!!.jsonPrimitive.booleanOrNull!!,
                hrCoaching = c["hr_coaching"]!!.jsonPrimitive.booleanOrNull!!,
                maxHr = c["max_hr"].i(), fuel = fuel,
                workoutId = c["workout_id"]?.jsonPrimitive?.content?.toInt() ?: 0,
            )
            assertEquals(c["hex"].s(), bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') },
                c["name"].s())
        }
    }
}
