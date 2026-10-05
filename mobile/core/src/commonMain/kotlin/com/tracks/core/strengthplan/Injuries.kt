// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.strengthplan

/** An active injury from the Health page: a body part and a 1–10 severity. */
data class ActiveInjury(val bodyPart: String?, val severity: Int)

/**
 * Injury adaptation — a port of `strength_plan/injuries.py`.
 *
 * minor (1–3) keeps the exercise at 70% load; moderate (4–7) drops it when the
 * injured muscles are primary or the pattern is banned; severe (8–10) drops it
 * when they are primary *or* secondary, or the pattern is banned.
 */
internal object Injuries {
    private val MUSCLES: Map<String, List<String>> = mapOf(
        "knee" to listOf("quads", "hamstrings"),
        "shoulder" to listOf("shoulders", "upper_back", "chest"),
        "lower_back" to listOf("lower_back", "hamstrings"),
        "hip_flexor" to listOf("hip_flexors", "quads"),
        "hamstring" to listOf("hamstrings"),
        "calf" to listOf("calves"),
        "ankle" to listOf("calves"),
        "elbow" to listOf("biceps", "triceps", "forearms"),
        "forearm" to listOf("forearms", "biceps"),
        "wrist" to listOf("forearms", "chest"),
        "neck" to listOf("upper_back", "shoulders"),
        "hip" to listOf("glutes", "hip_abductors", "hip_flexors"),
        "groin" to listOf("hip_flexors", "quads"),
        "shin" to listOf("calves"),
        "foot" to listOf("calves"),
    )
    private val BANNED_PATTERNS: Map<String, List<String>> = mapOf(
        "knee" to listOf("squat", "plyometric"),
        "lower_back" to listOf("hinge", "rotation"),
        "shoulder" to listOf("push", "pull"),
        "wrist" to listOf("push", "pull"),
        "elbow" to listOf("pull", "isolation"),
        "forearm" to listOf("pull", "isolation"),
        "hip_flexor" to listOf("squat", "hinge"),
        "hamstring" to listOf("hinge"),
        "calf" to listOf("plyometric"),
        "ankle" to listOf("plyometric"),
    )

    private fun band(severity: Int): String = when {
        severity <= 3 -> "minor"
        severity <= 7 -> "moderate"
        else -> "severe"
    }

    @Suppress("UNCHECKED_CAST")
    private fun muscles(ex: Dict, key: String): List<Any?> = ex.get(key, emptyList<Any?>()) as List<Any?>

    fun isSafe(exercise: Dict, injuries: List<ActiveInjury>): Boolean {
        if (injuries.isEmpty()) return true
        val primary = muscles(exercise, "primary_muscles")
        val secondary = muscles(exercise, "secondary_muscles")
        val pattern = exercise.get("movement_pattern", "")
        for (inj in injuries) {
            val part = (inj.bodyPart ?: "").lowercase()
            val protected = MUSCLES[part].orEmpty()
            val banned = BANNED_PATTERNS[part].orEmpty()
            when (band(inj.severity)) {
                "moderate" -> {
                    if (protected.any { it in primary }) return false
                    if (pattern in (banned as List<Any?>)) return false
                }
                "severe" -> {
                    if (protected.any { it in primary || it in secondary }) return false
                    if (pattern in (banned as List<Any?>)) return false
                }
            }
        }
        return true
    }

    /** 0.70 when a minor injury touches a primary muscle, else 1.0. */
    fun loadModifier(exercise: Dict, injuries: List<ActiveInjury>): Double {
        if (injuries.isEmpty()) return 1.0
        val primary = muscles(exercise, "primary_muscles")
        var worst = 1.0
        for (inj in injuries) {
            if (band(inj.severity) != "minor") continue
            val protected = MUSCLES[(inj.bodyPart ?: "").lowercase()].orEmpty()
            if (protected.any { it in primary }) worst = minOf(worst, 0.70)
        }
        return worst
    }
}
