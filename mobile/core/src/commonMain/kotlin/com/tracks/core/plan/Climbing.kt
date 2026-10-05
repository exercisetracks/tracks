// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.plan

/**
 * Climbing builders — a port of `backend/app/calculators/plan/climbing.py`,
 * where the session types, the finger-tendon spacing rule and the sources are.
 */
internal object Climbing {

    private fun warmup(minutes: Long = 15): Step = step(
        "type" to "warmup", "duration_min" to minutes, "intensity" to "easy",
        "note" to "Easy traversing and big-hold climbing, then 3-4 problems building toward the day's effort",
    )

    private fun cooldown(minutes: Long = 10): Step = step(
        "type" to "cooldown", "duration_min" to minutes, "intensity" to "easy",
        "note" to "Easy climbing or traversing, then forearm and shoulder stretches",
    )

    fun arc(durationMin: Long, buildIdx: Long, variation: Long): List<Step> {
        val each = minOf(30L, 15 + 5 * minOf(buildIdx, 3L) + 5 * (variation % 2))
        val reps = maxOf(2L, minOf(3L, (durationMin - 5) / (each + 5)))
        return listOf(
            step("type" to "warmup", "duration_min" to 5L, "intensity" to "easy", "note" to "Easy traverse"),
            step("type" to "effort_set", "reps" to reps, "duration_min_each" to each, "rest_min" to each / 2,
                "intensity" to "endurance", "label" to "ARC",
                "note" to "$reps× $each min continuous easy climbing (traverse or up-and-down laps) ·" +
                    " never more than a light pump · shake out on good holds · ${each / 2} min rest"),
        )
    }

    private val TECHNIQUE_DRILLS = listOf(
        "silent feet: place every foot without a sound",
        "straight arms: move from the hips, arms as ropes",
        "flagging and drop-knees on every move that allows one",
        "downclimb every problem you climb",
        "hover hands: pause over each hold before taking it",
        "slab and smearing: trust the feet",
    )

    fun technique(durationMin: Long, variation: Long): List<Step> {
        val a = TECHNIQUE_DRILLS[(variation % 6).toInt()]
        val b = TECHNIQUE_DRILLS[((variation + 3) % 6).toInt()]
        val main = maxOf(20L, minOf(60L, durationMin - 15))
        return listOf(
            step("type" to "warmup", "duration_min" to 10L, "intensity" to "easy", "note" to "Easy traverse"),
            step("type" to "activity", "duration_min" to main, "intensity" to "easy",
                "note" to "Technique on easy problems or routes · $a · then $b · stop before tiredness" +
                    " makes the movement sloppy"),
            step("type" to "cooldown", "duration_min" to 5L, "intensity" to "easy", "note" to "Forearm stretches"),
        )
    }

    fun hangboard(buildIdx: Long, variation: Long): List<Step> {
        val sets = minOf(8L, 5 + buildIdx / 2 + variation % 2)
        return listOf(
            warmup(15),
            step("type" to "effort_set", "reps" to sets, "duration_sec_each" to 10L, "rest_sec" to 180L,
                "intensity" to "max_strength", "label" to "Max hang",
                "note" to "$sets× 10 s max hang, 20 mm edge, half-crimp or open hand · add or take off" +
                    " weight so 10 s is hard but clean · 3 min rest · stop at any finger pain"),
            step("type" to "effort_set", "reps" to 3L, "duration_sec_each" to 45L, "rest_sec" to 45L,
                "intensity" to "easy", "label" to "Antagonists",
                "note" to "3 rounds: 10 push-ups, 15 band external rotations, 15 reverse wrist curls"),
            cooldown(5),
        )
    }

    fun limitBouldering(buildIdx: Long): List<Step> {
        val problems = minOf(6L, 4 + buildIdx / 3)
        val attempts = problems * 3
        return listOf(
            warmup(20),
            step("type" to "effort_set", "reps" to attempts, "duration_sec_each" to 30L, "rest_sec" to 180L,
                "intensity" to "max_strength", "label" to "Limit attempt",
                "note" to "$problems limit problems × ~3 attempts ($attempts goes) · full effort on" +
                    " every go · 3 min rest, longer if you need it · stop when attempts get worse"),
            cooldown(),
        )
    }

    private data class Pe(val label: String, val what: String, val sets: Long, val on: Long, val off: Long)

    private val PE_SHAPES = listOf(
        Pe("4x4", "four problems 3-4 grades below your limit, back to back", 4, 240, 240),
        Pe("Circuit", "a 30-50 move circuit that pumps you out near the end", 3, 180, 180),
        Pe("Linked", "two hard problems linked without stepping off", 4, 120, 120),
    )

    fun powerEndurance(buildIdx: Long, variation: Long): List<Step> {
        val p = PE_SHAPES[(variation % 3).toInt()]
        val sets = p.sets + minOf(buildIdx / 2, 2L)
        return listOf(
            warmup(20),
            step("type" to "effort_set", "reps" to sets, "duration_sec_each" to p.on, "rest_sec" to p.off,
                "intensity" to "vo2", "label" to p.label,
                "note" to "$sets× ${p.what} · ~${p.on / 60} min on, ${p.off / 60} min rest · deep pump, clean movement"),
            cooldown(),
        )
    }

    fun long(durationMin: Long): List<Step> = listOf(
        step("type" to "activity", "duration_min" to durationMin, "intensity" to "endurance",
            "note" to "Volume day at the crag or gym · lots of climbing 2-3 grades below your" +
                " max · rest as long as you climb · a project attempt or two if fresh"),
    )

    fun shortQuality(): List<Step> = listOf(
        warmup(20),
        step("type" to "effort_set", "reps" to 6L, "duration_sec_each" to 30L, "rest_sec" to 240L,
            "intensity" to "max_strength", "label" to "Near limit",
            "note" to "6 goes on problems just below your limit · full rest · leave wanting more"),
        cooldown(),
    )
}
