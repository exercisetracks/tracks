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
    )

    data class Product(
        val uid: String?,
        val name: String?,
        val carbsG: Double,
        val sodiumMg: Int = 0,
        val fluidMl: Int = 0,
        val caffeineMg: Int = 0,
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
    )

    data class Amounts(val carbsG: Int, val sodiumMg: Int, val fluidMl: Int, val caffeineMg: Int)

    data class Timeline(val items: List<Item>, val totals: Amounts, val perHour: Amounts)

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
    ): Targets {
        val band = heatBand(temperatureC, humidityPct)
        check(band in BANDS)
        return Targets(
            carbsGPerH = carbs ?: defaultCarbsPerHour(durationMin / 60.0),
            fluidMlPerH = fluid ?: FLUID_ML_PER_HOUR.getValue(band),
            sodiumMgPerH = sodium ?: SODIUM_MG_PER_HOUR.getValue(band),
            intervalMin = if (interval != null && interval != 0) interval else DEFAULT_INTERVAL_MIN,
            band = band,
            customCarbs = carbs != null,
            customFluid = fluid != null,
            customSodium = sodium != null,
            customInterval = interval != null && interval != 0,
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

    fun timeline(durationMin: Double, tg: Targets, products: List<Product>, laps: List<Lap>? = null): Timeline {
        val rate = tg.carbsGPerH.toDouble()
        val interval = tg.intervalMin
        val usable = products.filter { it.carbsG > 0 }
        val items = mutableListOf<Item>()
        var taken = 0.0
        var carbs = 0.0
        var sodium = 0
        var fluid = 0
        var caffeine = 0
        var minute = interval
        while (minute < durationMin) {
            val owed = rate * minute / 60.0 - taken
            if (usable.isEmpty()) {
                val grams = halfUp(rate * interval / 60.0)
                if (grams > 0) {
                    items += Item(minute, distanceAt(minute.toDouble(), laps), null, null, grams.toDouble(), 0, 0, 0)
                    taken += grams
                    carbs += grams
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
                    items += Item(minute, distanceAt(minute.toDouble(), laps), p.uid, p.name, p.carbsG,
                        p.sodiumMg, p.fluidMl, p.caffeineMg)
                    taken += p.carbsG
                    carbs += p.carbsG
                    sodium += p.sodiumMg
                    fluid += p.fluidMl
                    caffeine += p.caffeineMg
                }
            }
            minute += interval
        }
        val hours = if (durationMin > 0) durationMin / 60.0 else 0.0
        fun perHour(v: Double) = if (hours != 0.0) halfUp(v / hours) else 0
        return Timeline(
            items = items,
            totals = Amounts(halfUp(carbs), sodium, fluid, caffeine),
            perHour = Amounts(perHour(carbs), perHour(sodium.toDouble()), perHour(fluid.toDouble()),
                perHour(caffeine.toDouble())),
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
