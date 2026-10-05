// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.strengthplan

import com.tracks.core.parse.PyMath
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.round

/**
 * Load math — a port of `strength_plan/loads.py`: 1RM estimation, %-of-1RM
 * targets, and snapping a weight to something that can actually be loaded.
 *
 * `kotlin.math.round` is used for Python's one-argument `round()` on purpose:
 * both round half to even, which is what keeps a 61.25 kg target landing on
 * the same plate as on the server.
 */
internal object Loads {
    private const val LB_PER_KG = 2.20462
    private val DUMBBELL_LB = listOf(5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 95, 100).map { it.toDouble() }
    private val DUMBBELL_KG = listOf(2.5, 5.0, 7.5, 10.0, 12.5, 15.0, 17.5, 20.0, 22.5, 25.0, 27.5, 30.0, 35.0, 40.0, 45.0, 50.0)
    private val MACHINE_LB = listOf(10, 25, 40, 55, 70, 85, 100, 115, 130, 145, 160, 175, 190, 205, 220, 235, 250).map { it.toDouble() }
    private val MACHINE_KG = (5..120 step 5).map { it.toDouble() }
    private val KETTLEBELL_KG = listOf(8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 48).map { it.toDouble() }

    /** Average of Epley, Brzycki and Wathan; null for no reps or no weight. */
    fun estimate1rm(weightKg: Double, reps: Int): Double? {
        if (reps < 1 || weightKg <= 0) return null
        if (reps == 1) return weightKg
        val r = minOf(reps, 10)
        val epley = weightKg * (1 + r / 30.0)
        val brzycki = if (r < 37) weightKg * (36.0 / (37.0 - r)) else weightKg * 1.33
        val wathan = (100 * weightKg) / (48.8 + 53.8 * exp(-0.075 * r))
        return PyMath.round((epley + brzycki + wathan) / 3.0, 1)
    }

    /** The target weight at [pct] of a 1RM, to the nearest 2.5 kg. */
    fun trainingWeight(estimated1rmKg: Double, pct: Double): Double = round(estimated1rmKg * pct / 2.5) * 2.5

    /** `min(options, key=abs distance)`: the first of any tie, as Python's min. */
    private fun nearest(value: Double, options: List<Double>): Double {
        var best = options[0]
        for (o in options) if (abs(o - value) < abs(best - value)) best = o
        return best
    }

    fun roundWeightForEquipment(weightKg: Double, equipment: List<String>?, units: String? = "metric"): Double {
        if (weightKg <= 0) return 0.0
        val eq = equipment.orEmpty().toSet()
        val imperial = (units ?: "metric").lowercase() == "imperial"
        if ("kettlebell" in eq && "barbell" !in eq && "dumbbell" !in eq) return nearest(weightKg, KETTLEBELL_KG)
        if ("machine" in eq && "barbell" !in eq && "dumbbell" !in eq) {
            return if (imperial) nearest(weightKg * LB_PER_KG, MACHINE_LB) / LB_PER_KG else nearest(weightKg, MACHINE_KG)
        }
        if ("dumbbell" in eq || "cable" in eq) {
            return if (imperial) nearest(weightKg * LB_PER_KG, DUMBBELL_LB) / LB_PER_KG else nearest(weightKg, DUMBBELL_KG)
        }
        if (imperial) return round(weightKg * LB_PER_KG / 5.0) * 5.0 / LB_PER_KG
        return round(weightKg / 2.5) * 2.5
    }

    /** Bodyweight is the only option; an empty list is unknown, not bodyweight. */
    fun isBodyweightOnly(equipment: List<String?>?): Boolean =
        equipment.orEmpty().filter { !it.isNullOrEmpty() }.toSet() == setOf("bodyweight")

    fun conservativeStartingWeight(movementPattern: Any?, equipment: List<String?>?): Double {
        if (isBodyweightOnly(equipment)) return 0.0
        if (movementPattern == "isometric" || movementPattern == "plyometric") return 0.0
        return when (movementPattern) {
            "squat", "hinge" -> 20.0
            "push" -> 10.0
            "pull" -> 0.0
            "rotation", "isolation" -> 5.0
            "carry" -> 10.0
            else -> 10.0
        }
    }
}
