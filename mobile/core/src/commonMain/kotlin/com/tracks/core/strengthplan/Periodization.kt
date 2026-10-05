// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.strengthplan

/**
 * Reps, intensity, rest and session length — a port of
 * `strength_plan/periodization.py`. The tables are the Python's, value for
 * value; the reasoning behind them (endurance athletes get heavy low-rep
 * neural work, strength-primary athletes the full hypertrophy-to-power range)
 * is documented there.
 */
internal object Periodization {

    /** The working scheme for a stage and week; deloads are the generator's job. */
    fun prescription(stage: String?, weekInCycle: Int, endurance: Boolean): Triple<Long, Long, Double> {
        val phase = weekInCycle.mod(3)
        if (endurance) {
            if (stage == "linear") return Triple(6, 7, 0.72)
            if (stage == "weekly_undulating") return when (phase) {
                0 -> Triple(6, 7, 0.78)
                1 -> Triple(4, 8, 0.85)
                else -> Triple(3, 8, 0.88)
            }
            return when (phase) {
                0 -> Triple(6, 7, 0.75)
                1 -> Triple(4, 8, 0.85)
                else -> Triple(3, 8, 0.88)
            }
        }
        if (stage == "linear") return Triple(8, 7, 0.70)
        if (stage == "weekly_undulating") return when (phase) {
            0 -> Triple(10, 8, 0.70)
            1 -> Triple(6, 8, 0.80)
            else -> Triple(4, 7, 0.85)
        }
        return when (phase) {
            0 -> Triple(10, 8, 0.70)
            1 -> Triple(5, 8, 0.82)
            else -> Triple(3, 9, 0.88)
        }
    }

    private val ENDURANCE_FAMILIES = setOf(
        "running", "cycling", "mountain_biking", "hiking", "rowing",
        "climbing", "paddling", "triathlon", "swimming",
        "nordic_skiing", "alpine_skiing",
    )

    fun isEnduranceFamily(sportFamily: String): Boolean = sportFamily in ENDURANCE_FAMILIES

    val TIER_SETS: Map<Int, Long> = mapOf(5 to 5L, 4 to 4L, 3 to 3L, 2 to 2L, 1 to 2L)

    private val REST = mapOf(
        "squat" to 180L, "hinge" to 180L, "push" to 120L, "pull" to 120L, "plyometric" to 90L,
        "rotation" to 90L, "isometric" to 60L, "isolation" to 60L, "carry" to 120L,
    )
    private val REST_SUPP = mapOf(
        "squat" to 90L, "hinge" to 90L, "push" to 75L, "pull" to 75L, "plyometric" to 60L,
        "rotation" to 60L, "isometric" to 45L, "isolation" to 45L, "carry" to 75L,
    )

    fun restSeconds(pattern: Any?, tier: Int): Long =
        if (tier <= 2) REST_SUPP[pattern as? String] ?: 75L else REST[pattern as? String] ?: 120L

    private val DURATION_CAPS = mapOf(5 to 75L, 4 to 60L, 3 to 50L, 2 to 20L, 1 to 15L)

    /** Estimated card duration: sets × (reps·5 s + rest) per exercise, plus ten minutes. */
    fun sessionDurationMinutes(
        exercises: List<String>,
        library: Map<String, Dict>,
        sets: Any,
        avgReps: Any,
        tier: Int,
        maxMinutes: Int?,
    ): Long {
        if (exercises.isEmpty()) return 0
        var total = 0.0
        for (name in exercises) {
            val pattern = library[name].orEmpty().get("movement_pattern", "push")
            val setTime = num(avgReps) * 5 + restSeconds(pattern, tier)
            total += num(sets) * setTime
        }
        // `round(10 + total / 60)`: true division, then Python's half-even round.
        val minutes = kotlin.math.round(10 + total / 60).toLong()
        if (maxMinutes != null) return minOf(minutes, maxMinutes.toLong())
        return minOf(minutes, DURATION_CAPS[tier] ?: 45L)
    }
}
