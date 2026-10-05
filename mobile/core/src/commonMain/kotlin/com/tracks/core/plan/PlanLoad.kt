// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.plan

import com.tracks.core.parse.Py
import com.tracks.core.plan.PlanBase.DEFAULT_RUN_PACES

/**
 * The load a planned workout carries, from its steps — a port of
 * `backend/app/calculators/plan/load.py` (the reasoning for each intensity
 * factor is there), held to it by the `load` section of spec/fixtures/plan.json.
 *
 * TSS = hours × IF² × 100 per step; recovery between reps at 0.55. The week
 * is sized until this estimate carries its target, so the arithmetic keeps
 * the server's order to the last operation.
 */
object PlanLoad {
    const val EASY_IF = 0.75
    private const val REST_IF = 0.55
    private const val RECOVERY_IF = 0.65

    private val IF = mapOf(
        "walk" to 0.55,
        "max_strength" to 0.70, "ut2" to 0.72, "plyometric" to 0.80,
        "recovery" to 0.65, "skills" to 0.65,
        "easy" to 0.75, "endurance" to 0.75,
        "aerobic" to 0.80,
        "marathon" to 0.87, "tempo" to 0.87, "descent_repeats" to 0.87,
        "race_pace" to 0.90,
        "sweet_spot" to 0.92,
        "threshold" to 1.00, "over_under" to 1.00, "test" to 1.00,
        "interval" to 1.05, "vo2" to 1.05, "micro_bursts" to 1.05,
        "repetition" to 1.10, "anaerobic" to 1.10, "neuromuscular" to 1.10, "fast" to 1.10,
        "sprint" to 1.10,
    )

    private val TIMED = setOf("run", "warmup", "cooldown", "walk", "ride", "swim", "activity")

    private fun Map<String, Any?>.num(key: String): Double = if (containsKey(key)) Py.num(this[key]) else 0.0

    /** `step_if`. */
    fun stepIf(step: Map<String, Any?>): Double {
        if (step["type"] == "walk") return IF.getValue("walk")
        // `step.get("pace") or step.get("intensity")`: an empty pace falls through.
        val pace = step["pace"]
        val tag = if (pace is String && pace.isNotEmpty()) pace else step["intensity"]
        return if (tag is String) IF[tag] ?: EASY_IF else EASY_IF
    }

    /** `step_tss`. */
    fun stepTss(step: Map<String, Any?>, paces: Map<String, Double>?): Double {
        val p = paces ?: DEFAULT_RUN_PACES
        val t = step["type"]
        val f = stepIf(step)
        var workH = 0.0
        var restH = 0.0
        if (t in TIMED) {
            workH = step.num("duration_min") / 60
        } else if (t == "interval_set" || t == "effort_set") {
            val reps = step.num("reps")
            if (step.containsKey("duration_min_each")) {
                workH = reps * step.num("duration_min_each") / 60
                restH = reps * step.num("rest_min") / 60
            } else if (step.containsKey("duration_sec_each")) {
                workH = reps * step.num("duration_sec_each") / 3600
                restH = reps * step.num("rest_sec") / 3600
            } else if (step.containsKey("sec_per_km")) {
                // A swim or erg piece at its own pace — see load.py for the
                // 5×-long swims timing it at running's pace caused.
                workH = reps * (step.num("distance_m") / 1000 * step.num("sec_per_km")) / 3600
                restH = reps * step.num("rest_sec") / 3600
            } else {
                val zone = (if (step.containsKey("pace")) step["pace"] else "interval") as String?
                val pace = zone?.let { p[it] } ?: (p["interval"] ?: 300.0)
                workH = reps * (step.num("distance_m") / 1000 * pace) / 3600
                restH = reps * step.num("rest_sec") / 3600
            }
        } else if (t == "fartlek") {
            val reps = step.num("reps")
            val hardH = reps * step.num("hard_min") / 60
            val easyH = reps * step.num("easy_min") / 60
            return (hardH * f * f + easyH * RECOVERY_IF * RECOVERY_IF) * 100
        }
        return (workH * f * f + restH * REST_IF * REST_IF) * 100
    }

    /** `workout_tss`. */
    fun workoutTss(steps: List<Map<String, Any?>>, paces: Map<String, Double>?): Double {
        var total = 0.0
        for (s in steps) total += stepTss(s, paces)
        return total
    }

    /** `tss_per_hour`. */
    fun tssPerHour(intensityFactor: Double): Double = intensityFactor * intensityFactor * 100
}
