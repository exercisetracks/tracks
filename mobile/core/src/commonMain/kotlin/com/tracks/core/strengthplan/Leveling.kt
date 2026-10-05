// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.strengthplan

import com.tracks.core.spec.experienceLevels

/** A suggested change of experience level, which the user always confirms. */
data class ExperienceSuggestion(val suggested: String, val reason: String)

/**
 * `infer_experience_suggestion` from `strength_plan/leveling.py`. The rest of
 * that module — default tier, difficulty ceiling, stage floor, starting-weight
 * factor — already lives in `com.tracks.core.spec`, generated from
 * spec/strength.yaml.
 *
 * Deliberately conservative, as in the Python: up at most one level and only
 * with twelve sessions in twelve weeks and a non-falling trend; down only
 * after eight idle weeks, and never below "returning".
 */
object Leveling {
    fun inferSuggestion(
        current: String?,
        sessions12wk: Long = 0,
        e1rmTrend: String = "flat",
        weeksSinceLast: Long = 0,
    ): ExperienceSuggestion? {
        val order = experienceLevels
        val cur = if (current in order) current!! else "returning"
        val idx = order.indexOf(cur)
        if (weeksSinceLast >= 8 && idx > 1) {
            return ExperienceSuggestion(
                order[idx - 1],
                "No strength sessions in $weeksSinceLast weeks — ease back in a level down.",
            )
        }
        if (idx < order.size - 1 && sessions12wk >= 12 && e1rmTrend != "falling") {
            return ExperienceSuggestion(
                order[idx + 1],
                "$sessions12wk strength sessions logged this block with steady progress — ready to level up.",
            )
        }
        return null
    }
}
