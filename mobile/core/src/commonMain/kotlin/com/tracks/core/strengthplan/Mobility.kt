// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.strengthplan

/**
 * The weekly standalone mobility session — a port of `strength_plan/mobility.py`.
 * Whole-body coverage with the sport's tight spots pulled to the front, drawn
 * from the same selector as the post-workout flows and rotated per week.
 */
object Mobility {
    fun targetMuscles(sportFamily: String): List<String> {
        val sport = Flexibility.sportTargetMuscles(sportFamily, fallback = emptyList())
        val ordered = ArrayList<String>()
        for (m in sport.take(3) + Flexibility.FULL_BODY_MOBILITY_MUSCLES) if (m !in ordered) ordered += m
        return ordered
    }

    internal fun steps(
        sportFamily: String,
        candidates: List<StretchCandidate>,
        weekNum: Int,
        regenSalt: String,
    ): List<MutableMap<String, Any?>> {
        if (candidates.isEmpty()) return emptyList()
        val archetype = Archetypes.selectFlow("weekly_mobility", sportFamily, null, weekNum)
        val selected = Flexibility.selectStretchFlow(
            candidates, targetMuscles(sportFamily),
            count = 8,
            minCount = 6,
            seed = "mobility-$sportFamily-$weekNum-$regenSalt",
            orderByPosition = true,
            closerMuscles = archetype?.closerMuscles,
        )
        return selected.map(Flexibility::toStep)
    }

    fun description(sportFamily: String): String {
        val note = when (sportFamily) {
            "running" -> "Targets hip flexors, hamstrings, and calves — key running recovery areas."
            "cycling" -> "Counters hip flexor shortening and thoracic rounding from bike position."
            "climbing" -> "Forearm decompression, shoulder health, and hip opening."
            "paddling" -> "Thoracic rotation and shoulder mobility for efficient paddling."
            "mountain_biking" -> "Hip and thoracic mobility to counter an aggressive bike position."
            "hiking" -> "Calf, hip, and lower-back recovery after trail demands."
            else -> "Full-body mobility to support your training."
        }
        return "25 min active recovery and mobility session. $note " +
            "Focus on breath and staying relaxed — this session aids adaptation " +
            "from the week's training."
    }
}
