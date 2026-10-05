// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.plan

import com.tracks.core.plan.PlanBase.hrZone

/**
 * Hiking builders — a port of `backend/app/calculators/plan/hiking.py`:
 * vertical, incline intervals, descent conditioning, the loaded long hike and
 * back-to-back days, with the reasoning and sources there.
 */
internal object Hiking {

    private fun fmtVert(metres: Long, imperial: Boolean): String =
        if (imperial) "${(metres * 3.28084).toLong() / 50 * 50} ft" else "$metres m"

    private fun fmtPack(kg: Long, imperial: Boolean): String =
        if (imperial) "${kg * 22 / 10} lb" else "$kg kg"

    fun easy(durationMin: Long): List<Step> = listOf(
        step("type" to "activity", "duration_min" to durationMin, "intensity" to "easy",
            "note" to "Easy hike or brisk walk · rolling terrain · conversational"),
    )

    fun vert(durationMin: Long, buildIdx: Long, imperial: Boolean): List<Step> {
        val climb = maxOf(30L, durationMin)
        val rate = 350 + 25 * minOf(buildIdx, 8L)
        val vert = climb * rate / 60 / 50 * 50
        return listOf(
            step("type" to "warmup", "duration_min" to 10L, "intensity" to "easy", "note" to "Easy flat walking"),
            step("type" to "activity", "duration_min" to climb, "intensity" to "endurance",
                "note" to "Climb steadily · aim for ~${fmtVert(vert, imperial)} of ascent" +
                    " · aerobic: breathing hard enough to notice, not to stop talking"),
            step("type" to "cooldown", "duration_min" to 10L, "intensity" to "easy",
                "note" to "Easy descent · short steps, poles if you use them"),
        )
    }

    private data class Incline(val label: String, val what: String, val reps: Long, val each: Long, val rest: Long)

    private val INCLINE_MODES = listOf(
        Incline("Incline", "treadmill at 12-15% incline, brisk walk, no handrails", 5, 4, 2),
        Incline("Stairs", "stair climbing, every step, steady rhythm; walk down to recover", 6, 3, 2),
        Incline("Hill", "outdoor hill repeats at a hard hiking pace; walk down to recover", 4, 6, 4),
        Incline("Step-ups", "step-ups onto a knee-high box with your pack, alternating legs", 4, 5, 2),
    )

    fun inclineIntervals(buildIdx: Long, variation: Long, lthr: Long?): List<Step> {
        val m = INCLINE_MODES[(variation % 4).toInt()]
        val reps = m.reps + minOf(buildIdx / 2, 3L)
        return listOf(
            step("type" to "warmup", "duration_min" to 10L, "intensity" to "easy",
                "note" to "Easy walking, the last 3 min on a gentle incline"),
            step("type" to "effort_set", "reps" to reps, "duration_min_each" to m.each, "rest_min" to m.rest,
                "intensity" to "threshold", "label" to m.label,
                "note" to "$reps× ${m.each} min ${m.what} · ${hrZone(0.94, 1.00, lthr)} · hard but steady" +
                    " · ${m.rest} min easy"),
            step("type" to "cooldown", "duration_min" to 8L, "intensity" to "easy", "note" to "Easy walking"),
        )
    }

    fun descent(buildIdx: Long, variation: Long): List<Step> {
        val reps = minOf(8L, 2 + maxOf(variation, buildIdx))
        return listOf(
            step("type" to "warmup", "duration_min" to 10L, "intensity" to "easy", "note" to "Easy walking to the hill"),
            step("type" to "effort_set", "reps" to reps, "duration_min_each" to 4L, "rest_min" to 4L,
                "intensity" to "endurance", "label" to "Descend",
                "note" to "$reps× 4 min controlled descent on a steep path or stairs · soft knees, short" +
                    " quick steps, don't brake with straight legs · walk back up easy"),
            step("type" to "activity", "duration_min" to 8L, "intensity" to "easy",
                "note" to "Step-downs: 3× 10 slow (3 s) lowerings each leg from a 20-30 cm step"),
        )
    }

    private fun packKg(phase: String, buildIdx: Long): Long =
        if (phase == "base" || phase == "taper") 5L else minOf(15L, 7 + buildIdx)

    fun long(durationMin: Long, phase: String, buildIdx: Long, imperial: Boolean): List<Step> {
        val kg = packKg(phase, buildIdx)
        val vert = durationMin * 250 / 60 / 50 * 50
        return listOf(step("type" to "activity", "duration_min" to durationMin, "intensity" to "endurance",
            "note" to "Long hike with a ${fmtPack(kg, imperial)} pack · ~${fmtVert(vert, imperial)}" +
                " of climbing · steady all day: eat and drink every hour"))
    }

    fun backToBack(durationMin: Long, phase: String, buildIdx: Long, imperial: Boolean): List<Step> {
        val minutes = maxOf(90L, durationMin * 2)
        val kg = maxOf(5L, packKg(phase, buildIdx) - 3)
        return listOf(step("type" to "activity", "duration_min" to minutes, "intensity" to "endurance",
            "note" to "Day 1 of 2 · $minutes min hike with a ${fmtPack(kg, imperial)} pack" +
                " · keep it easy: tomorrow's long hike is the point"))
    }
}
