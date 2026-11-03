// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.fuel

import com.tracks.core.fit.daysFromCivil
import com.tracks.core.spec.BANDS
import com.tracks.core.spec.HEAT_THRESHOLD_C
import com.tracks.core.spec.HUMIDITY_MIN_TEMP_C
import com.tracks.core.spec.HUMIDITY_THRESHOLD_PCT
import com.tracks.core.spec.VERY_HOT_THRESHOLD_C
import com.tracks.core.spec.defaultCarbsPerHour
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Race fuelling: targets, a what-to-take-when timeline, and gut training.
 *
 * A port of backend/app/calculators/fuel_plan.py, held to it by
 * spec/fixtures/fuel_plan.json. Pure arithmetic over synced rows with no clock
 * and no randomness, so the phone and the web tell an athlete to eat the same
 * thing. See the Python for the reasoning behind each rule; rounding is
 * half-up there and here.
 */
object FuelPlan {

    val FLUID_ML_PER_HOUR = mapOf("normal" to 500, "hot" to 650, "very_hot" to 800)
    val SODIUM_MG_PER_HOUR = mapOf("normal" to 400, "hot" to 600, "very_hot" to 800)
    const val DEFAULT_INTERVAL_MIN = 20

    /** Potassium follows the fluid drunk, at mid-range sweat potassium. See the Python. */
    const val POTASSIUM_MG_PER_ML = 0.2

    /** The fuel gauges' full scale, per hour: an elite ultra-endurance athlete's. Display only. */
    data class Ceilings(val carbsGPerH: Int, val sodiumMgPerH: Int, val potassiumMgPerH: Int, val fluidMlPerH: Int)
    val CEILINGS = Ceilings(carbsGPerH = 120, sodiumMgPerH = 1500, potassiumMgPerH = 375, fluidMlPerH = 1200)

    const val LONG_SESSION_MIN = 75
    const val GUT_START_G_PER_H = 40
    const val GUT_STEP_G_PER_H = 10
    const val GUT_FLOOR_G_PER_H = 30
    const val GUT_WINDOW_DAYS = 84
    const val GUT_REHEARSAL_DAYS = 14
    private val NOT_ENDURANCE = listOf("strength", "flexib", "mobility", "yoga", "pilates")

    data class Targets(
        val carbsGPerH: Int,
        val fluidMlPerH: Int,
        val sodiumMgPerH: Int,
        val intervalMin: Int,
        val band: String,
        val customCarbs: Boolean,
        val customFluid: Boolean,
        val customSodium: Boolean,
        val customInterval: Boolean,
        val potassiumMgPerH: Int = 0,
        val customPotassium: Boolean = false,
    )

    data class Product(
        val uid: String?,
        val name: String?,
        val carbsG: Double,
        val sodiumMg: Int = 0,
        val fluidMl: Int = 0,
        val caffeineMg: Int = 0,
        val potassiumMg: Int = 0,
    )

    data class Lap(val distanceM: Double, val targetSecPerKm: Double)

    data class Item(
        val minute: Int,
        val distanceM: Int?,
        val productUid: String?,
        val name: String?,
        val carbsG: Double,
        val sodiumMg: Int,
        val fluidMl: Int,
        val caffeineMg: Int,
        val potassiumMg: Int = 0,
    )

    data class Amounts(val carbsG: Int, val sodiumMg: Int, val fluidMl: Int, val caffeineMg: Int, val potassiumMg: Int = 0)

    /** [mix] is the homemade mix's sip when it was carried. */
    data class Timeline(val items: List<Item>, val totals: Amounts, val perHour: Amounts, val mix: Mix? = null)

    data class Workout(
        val uid: String,
        val scheduledDate: String?,
        val sport: String?,
        val workoutType: String?,
        val durationMinutes: Double?,
        val fuelCarbsPerHour: Int?,
    )

    data class Log(
        val uid: String?,
        val plannedWorkoutUid: String?,
        val date: String?,
        val comfort: Int?,
        val carbsG: Double?,
        val durationMin: Double?,
    )

    private fun halfUp(x: Double): Int = floor(x + 0.5).toInt()

    fun heatBand(temperatureC: Double?, humidityPct: Double?): String {
        val hot = temperatureC != null && temperatureC >= HEAT_THRESHOLD_C
        val veryHot = temperatureC != null && temperatureC >= VERY_HOT_THRESHOLD_C
        val humid = humidityPct != null && humidityPct >= HUMIDITY_THRESHOLD_PCT &&
            (temperatureC ?: HUMIDITY_MIN_TEMP_C.toDouble()) >= HUMIDITY_MIN_TEMP_C
        return when {
            veryHot -> "very_hot"
            hot || humid -> "hot"
            else -> "normal"
        }
    }

    fun targets(
        durationMin: Double,
        temperatureC: Double? = null,
        humidityPct: Double? = null,
        carbs: Int? = null,
        fluid: Int? = null,
        sodium: Int? = null,
        interval: Int? = null,
        potassium: Int? = null,
    ): Targets {
        val band = heatBand(temperatureC, humidityPct)
        check(band in BANDS)
        val fluidMl = fluid ?: FLUID_ML_PER_HOUR.getValue(band)
        return Targets(
            carbsGPerH = carbs ?: defaultCarbsPerHour(durationMin / 60.0),
            fluidMlPerH = fluidMl,
            sodiumMgPerH = sodium ?: SODIUM_MG_PER_HOUR.getValue(band),
            intervalMin = if (interval != null && interval != 0) interval else DEFAULT_INTERVAL_MIN,
            band = band,
            customCarbs = carbs != null,
            customFluid = fluid != null,
            customSodium = sodium != null,
            customInterval = interval != null && interval != 0,
            potassiumMgPerH = potassium ?: halfUp(fluidMl * POTASSIUM_MG_PER_ML),
            customPotassium = potassium != null,
        )
    }

    fun distanceAt(minute: Double, laps: List<Lap>?): Int? {
        if (laps.isNullOrEmpty()) return null
        var t = 0.0
        var d = 0.0
        val target = minute * 60.0
        for (lap in laps) {
            val dur = lap.distanceM / 1000.0 * lap.targetSecPerKm
            if (dur <= 0) continue
            if (t + dur >= target) return halfUp(d + lap.distanceM * (target - t) / dur)
            t += dur
            d += lap.distanceM
        }
        return halfUp(d)
    }

    private fun item(minute: Int, laps: List<Lap>?, p: Product) = Item(minute, distanceAt(minute.toDouble(), laps),
        p.uid, p.name, p.carbsG, p.sodiumMg, p.fluidMl, p.caffeineMg, p.potassiumMg)

    /** The products' stops — `_schedule` in the Python. */
    private fun schedule(durationMin: Double, tg: Targets, products: List<Product>, laps: List<Lap>?, plain: Boolean): List<Item> {
        val rate = tg.carbsGPerH.toDouble()
        val interval = tg.intervalMin
        val usable = products.filter { it.carbsG > 0 }
        val items = mutableListOf<Item>()
        var taken = 0.0
        var minute = interval
        while (minute < durationMin) {
            val owed = rate * minute / 60.0 - taken
            if (usable.isEmpty()) {
                val grams = halfUp(rate * interval / 60.0)
                if (plain && grams > 0) {
                    items += item(minute, laps, Product(null, null, grams.toDouble()))
                    taken += grams
                }
            } else {
                var best: Product? = null
                var bestGap = 0.0
                for (p in usable) {
                    val gap = abs(owed - p.carbsG)
                    if (best == null || gap < bestGap) {
                        best = p
                        bestGap = gap
                    }
                }
                val p = best!!
                if (owed >= p.carbsG / 2.0) {
                    items += item(minute, laps, p)
                    taken += p.carbsG
                }
            }
            minute += interval
        }
        return items
    }

    /** Carbs (a float, summed in order), sodium, fluid, caffeine, potassium. */
    private data class Sums(val carbs: Double, val sodium: Int, val fluid: Int, val caffeine: Int, val potassium: Int)

    private fun sums(items: List<Item>): Sums {
        var carbs = 0.0
        var sodium = 0; var fluid = 0; var caffeine = 0; var potassium = 0
        for (i in items) {
            carbs += i.carbsG; sodium += i.sodiumMg; fluid += i.fluidMl; caffeine += i.caffeineMg; potassium += i.potassiumMg
        }
        return Sums(carbs, sodium, fluid, caffeine, potassium)
    }

    /**
     * What to take when — `timeline` in the Python, which says why. With the
     * homemade [mix], the products are scheduled first and the mix carries
     * whatever they leave short of every target.
     */
    fun timeline(
        durationMin: Double,
        tg: Targets,
        products: List<Product>,
        laps: List<Lap>? = null,
        mix: Boolean = false,
    ): Timeline {
        var items = schedule(durationMin, tg, products, laps, plain = !mix)
        val hours = if (durationMin > 0) durationMin / 60.0 else 0.0
        var serving: Mix? = null
        if (mix) {
            val got = sums(items)
            fun ph(v: Double) = if (hours != 0.0) v / hours else 0.0
            serving = homemadeServing(tg, Delivered(ph(got.carbs), ph(got.sodium.toDouble()),
                ph(got.potassium.toDouble()), ph(got.fluid.toDouble())), durationMin)
            val sips = mutableListOf<Item>()
            var minute = serving.intervalMin
            while (minute < durationMin) {
                if (serving.product.fluidMl > 0) sips += item(minute, laps, serving.product)
                minute += serving.intervalMin
            }
            // A sip before a product at the same minute: drink, then eat.
            items = (sips + items).sortedWith(compareBy<Item>({ it.minute }, { it.productUid != HOMEMADE_UID }))
        }
        val t = sums(items)
        fun perHour(v: Double) = if (hours != 0.0) halfUp(v / hours) else 0
        return Timeline(
            items = items,
            totals = Amounts(halfUp(t.carbs), t.sodium, t.fluid, t.caffeine, t.potassium),
            perHour = Amounts(perHour(t.carbs), perHour(t.sodium.toDouble()), perHour(t.fluid.toDouble()),
                perHour(t.caffeine.toDouble()), perHour(t.potassium.toDouble())),
            mix = serving,
        )
    }

    // ── The homemade mix ─────────────────────────────────────────────────────
    // The Python's homemade_* functions, which (with
    // docs/race-fuelling-research.md) explain every rule and number.

    const val HOMEMADE_UID = "homemade"
    const val HOMEMADE_NAME = "Homemade mix"
    const val DEFAULT_BOTTLE_ML = 750
    val MIX_PRESETS = listOf("basic", "high_carb", "natural")

    private const val SUGAR_CARBS = 1.0
    private const val SUGAR_G_PER_TSP = 4.2
    private const val MALTO_CARBS = 0.95
    private const val MALTO_G_PER_TSP = 2.5
    private const val HONEY_CARBS = 0.82
    private const val HONEY_G_PER_TSP = 7.0
    private const val SALT_NA = 393.0
    private const val SALT_G_PER_TSP = 6.0
    private const val LITE_NA = 207.0
    private const val LITE_K = 250.0
    private const val LITE_G_PER_TSP = 5.6
    private const val CW_K = 2.5
    private const val CW_NA = 1.05
    private const val CW_CARBS = 0.037
    private const val CW_ML_PER_CUP = 240

    /** One sip of the mix, as a product, with the interval it is sipped at and its strength. */
    /**
     * One sip of the mix, as a product, with its interval and strength. A
     * strength over the heat band's ([overBand]) comes with the carbs an hour
     * food or gels would have to carry to bring it down ([extraCarbsGPerH]).
     */
    data class Mix(
        val product: Product,
        val intervalMin: Int,
        val concentrationPct: Double,
        val bandPct: Int = 0,
        val overBand: Boolean = false,
        val extraCarbsGPerH: Int = 0,
    )

    /** What the other products give per hour, which the mix makes up to the targets. */
    data class Delivered(val carbsG: Double = 0.0, val sodiumMg: Double = 0.0, val potassiumMg: Double = 0.0, val fluidMl: Double = 0.0)

    data class Ingredient(val key: String, val name: String, val grams: Double?, val ml: Double?, val measure: String)

    data class Recipe(
        val preset: String,
        val bottleMl: Int,
        val bottles: Int,
        val concentrationPct: Double,
        val bandPct: Int,
        val overBand: Boolean,
        val extraCarbsGPerH: Int,
        val sipMl: Int,
        val intervalMin: Int,
        val ingredients: List<Ingredient>,
        /** What one bottle actually carries: carbs g, sodium mg, potassium mg. */
        val perBottleCarbsG: Int,
        val perBottleSodiumMg: Int,
        val perBottlePotassiumMg: Int,
    )

    private fun tenth(x: Double): Double = floor(x * 10 + 0.5) / 10

    fun mixPreset(carbsGPerH: Int, chosen: String?): String =
        if (chosen in MIX_PRESETS) chosen!! else if (carbsGPerH <= 60) "basic" else "high_carb"

    fun homemadeServing(tg: Targets, delivered: Delivered = Delivered(), durationMin: Double? = null): Mix {
        val band = BANDS.getValue(tg.band)
        val shortCarbs = maxOf(0.0, tg.carbsGPerH - delivered.carbsG)
        val shortNa = maxOf(0.0, tg.sodiumMgPerH - delivered.sodiumMg)
        val shortK = maxOf(0.0, tg.potassiumMgPerH - delivered.potassiumMg)
        val shortFluid = maxOf(0.0, tg.fluidMlPerH - delivered.fluidMl)
        val interval = if (tg.customInterval) tg.intervalMin else band.sipIntervalMin
        // No sip at the start or the finish: the sips share the race's hours.
        val sips = if (durationMin != null && durationMin > 0) (interval until ceil(durationMin).toInt() step interval).count() else 0
        val share = if (sips > 0) (durationMin!! / 60.0) / sips else interval / 60.0
        val fluid = halfUp(shortFluid * share)
        val carbs: Double; val sodium: Int; val potassium: Int; val strength: Double
        if (fluid > 0) {
            carbs = tenth(shortCarbs * share)
            sodium = halfUp(shortNa * share)
            potassium = halfUp(shortK * share)
            strength = tenth(carbs / fluid * 100)
        } else {
            carbs = 0.0; sodium = 0; potassium = 0; strength = 0.0
        }
        val cap = band.concentrationPct
        val carried = if (fluid > 0) shortFluid * cap / 100.0 else 0.0
        val extra = if (strength > cap || fluid == 0) halfUp(maxOf(0.0, shortCarbs - carried)) else 0
        return Mix(
            Product(HOMEMADE_UID, HOMEMADE_NAME, carbs, sodium, fluid, 0, potassium),
            interval, strength, cap, strength > cap, extra,
        )
    }

    private val FRACTIONS = listOf("", "⅛", "¼", "⅜", "½", "⅝", "¾", "⅞")

    private fun eighths(x: Double): String {
        val n = halfUp(x * 8)
        val s = (if (n / 8 != 0) (n / 8).toString() else "") + FRACTIONS[n % 8]
        return s.ifEmpty { "0" }
    }

    fun spoonMeasure(grams: Double, gPerTsp: Double, stepEighths: Int): String {
        val tsp = grams / gPerTsp
        if (tsp >= 3) {
            val tbsp = halfUp(tsp / 3 * 2) / 2.0
            return "${eighths(tbsp)} tbsp"
        }
        val step = stepEighths / 8.0
        val q = halfUp(tsp / step) * step
        if (q == 0.0) return "a pinch"
        return "${eighths(q)} tsp"
    }

    private fun ingredient(key: String, name: String, grams: Double, gPerTsp: Double, stepEighths: Int, salt: Boolean) =
        Ingredient(key, name, if (salt) tenth(grams) else halfUp(grams).toDouble(), null,
            spoonMeasure(grams, gPerTsp, stepEighths))

    fun homemadeRecipe(preset: String, bottleMl: Int, mix: Mix, carbsGPerH: Int, raceFluidMl: Int): Recipe {
        val serving = mix.product
        val perMl = if (serving.fluidMl != 0) 1.0 / serving.fluidMl else 0.0
        val carbs = serving.carbsG * perMl * bottleMl
        val sodium = serving.sodiumMg * perMl * bottleMl
        val potassium = serving.potassiumMg * perMl * bottleMl
        val out = mutableListOf<Ingredient>()
        var gotCarbs = 0.0
        var gotNa = 0.0
        var gotK = 0.0
        if (preset == "natural") {
            var cw = minOf(bottleMl.toDouble(), potassium / CW_K)
            cw = (halfUp(cw / 10) * 10).toDouble()
            gotK += cw * CW_K
            gotNa += cw * CW_NA
            gotCarbs += cw * CW_CARBS
            val honey = maxOf(0.0, carbs - gotCarbs) / HONEY_CARBS
            val salt = maxOf(0.0, sodium - gotNa) / SALT_NA
            if (honey > 0) {
                out += ingredient("honey", "Honey", honey, HONEY_G_PER_TSP, 4, false)
                gotCarbs += out.last().grams!! * HONEY_CARBS
            }
            if (cw > 0) {
                val cups = cw / CW_ML_PER_CUP
                out += Ingredient("coconut_water", "Coconut water", null, cw, "${eighths(halfUp(cups * 4) / 4.0)} cup")
            }
            if (salt > 0) {
                out += ingredient("salt", "Table salt", salt, SALT_G_PER_TSP, 1, true)
                gotNa += out.last().grams!! * SALT_NA
            }
        } else {
            val lite = minOf(potassium / LITE_K, sodium / LITE_NA)
            val salt = maxOf(0.0, sodium - lite * LITE_NA) / SALT_NA
            val sugar: Double
            if (preset == "high_carb") {
                val share = if (carbsGPerH <= 90) 1.0 / 3 else 1.0 / 9
                val malto = carbs * share / MALTO_CARBS
                sugar = carbs * (1 - share) / SUGAR_CARBS
                out += ingredient("maltodextrin", "Maltodextrin", malto, MALTO_G_PER_TSP, 4, false)
                gotCarbs += out.last().grams!! * MALTO_CARBS
            } else {
                sugar = carbs / SUGAR_CARBS
            }
            if (sugar > 0) {
                out += ingredient("sugar", "Table sugar", sugar, SUGAR_G_PER_TSP, 4, false)
                gotCarbs += out.last().grams!! * SUGAR_CARBS
            }
            if (salt > 0) {
                out += ingredient("salt", "Table salt", salt, SALT_G_PER_TSP, 1, true)
                gotNa += out.last().grams!! * SALT_NA
            }
            if (lite > 0) {
                out += ingredient("lite_salt", "Lite salt", lite, LITE_G_PER_TSP, 1, true)
                gotNa += out.last().grams!! * LITE_NA
                gotK += out.last().grams!! * LITE_K
            }
        }
        return Recipe(
            preset = preset, bottleMl = bottleMl,
            bottles = if (bottleMl != 0) ceil(raceFluidMl.toDouble() / bottleMl).toInt() else 0,
            concentrationPct = mix.concentrationPct, bandPct = mix.bandPct, overBand = mix.overBand,
            extraCarbsGPerH = mix.extraCarbsGPerH, sipMl = serving.fluidMl, intervalMin = mix.intervalMin,
            ingredients = out,
            perBottleCarbsG = halfUp(gotCarbs), perBottleSodiumMg = halfUp(gotNa), perBottlePotassiumMg = halfUp(gotK),
        )
    }

    private fun isLong(w: Workout): Boolean {
        val sport = (w.sport ?: "").lowercase()
        val kind = (w.workoutType ?: "").lowercase()
        if (NOT_ENDURANCE.any { it in sport || it in kind }) return false
        return (w.durationMinutes ?: 0.0) >= LONG_SESSION_MIN
    }

    /** Days from [a] to [b], both ISO dates. */
    private fun daysBetween(a: String, b: String): Long = daysFromCivil(b) - daysFromCivil(a)

    /** Carbs-per-hour target for each long session before the race; see the Python. */
    fun gutTraining(raceCarbsGPerH: Int, raceDate: String, workouts: List<Workout>, logs: List<Log>): Map<String, Int> {
        val byWorkout = mutableMapOf<String, Log>()
        for (log in logs.sortedWith(compareBy({ it.date ?: "" }, { it.uid ?: "" }))) {
            log.plannedWorkoutUid?.takeIf { it.isNotEmpty() }?.let { byWorkout[it] = log }
        }
        val sessions = workouts
            .filter {
                isLong(it) && !it.scheduledDate.isNullOrEmpty() &&
                    daysBetween(it.scheduledDate, raceDate).let { d -> d > 0 && d <= GUT_WINDOW_DAYS }
            }
            .sortedWith(compareBy({ it.scheduledDate!! }, { it.uid }))
        val out = LinkedHashMap<String, Int>()
        var level = GUT_START_G_PER_H
        for (w in sessions) {
            val target = when {
                w.fuelCarbsPerHour != null -> w.fuelCarbsPerHour
                daysBetween(w.scheduledDate!!, raceDate) <= GUT_REHEARSAL_DAYS -> raceCarbsGPerH
                else -> minOf(level, raceCarbsGPerH)
            }
            out[w.uid] = target
            val log = byWorkout[w.uid]
            if (log == null) {
                level = minOf(raceCarbsGPerH, target + GUT_STEP_G_PER_H)
                continue
            }
            val comfort = log.comfort ?: 3
            val minutes = log.durationMin?.takeIf { it != 0.0 } ?: w.durationMinutes ?: 0.0
            val takenRate = if (minutes != 0.0) (log.carbsG ?: 0.0) * 60.0 / minutes else 0.0
            level = when {
                comfort <= 2 -> maxOf(GUT_FLOOR_G_PER_H, target - GUT_STEP_G_PER_H)
                comfort >= 4 && takenRate >= 0.9 * target -> minOf(raceCarbsGPerH, target + GUT_STEP_G_PER_H)
                else -> target
            }
        }
        return out
    }
}
