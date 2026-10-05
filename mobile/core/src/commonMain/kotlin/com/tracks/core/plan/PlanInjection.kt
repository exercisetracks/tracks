// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.plan

import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.strengthplan.ActiveInjury
import com.tracks.core.strengthplan.ExistingWorkout
import com.tracks.core.strengthplan.StrengthGoal
import com.tracks.core.strengthplan.StrengthPlan
import com.tracks.core.strengthplan.StretchCandidate
import com.tracks.core.strengthplan.StretchPlanning

/** Everything `_inject_strength_workouts` reads from the database, as plain values. */
data class StrengthInputs(
    val goal: StrengthGoal,
    val eventSport: String?,
    val equipment: List<String>,
    val library: Map<String, Map<String, Any?>>,
    val strengthRecords: Map<String, Map<String, Any?>> = emptyMap(),
    val injuries: List<ActiveInjury> = emptyList(),
    val sessionsCount: Int = 0,
    val units: String = "metric",
    val preferred: Set<String> = emptySet(),
    val excluded: Set<String> = emptySet(),
    val sessionMaxMinutes: Int? = null,
    val confirmedNo: Set<String> = emptySet(),
    val experience: String? = null,
    val anchorDate: CivilDate? = null,
    val customs: List<PlanAssembly.CustomWorkout> = emptyList(),
    val recentlyUsed: Set<Any> = emptySet(),
)

/**
 * What the server adds to an endurance plan after generating it — strength and
 * weekly mobility sessions, the user's own workouts, and a stretch flow after
 * each training day — in the server's order (`api/training_plan/generation.py`:
 * field tests, then `_inject_strength_workouts`, then `_inject_stretch_flows`).
 *
 * Pure: the phone gathers the inputs from its replica and bundled library, and
 * `PlanInjectionFixtureTest` holds the whole assembled plan to the server's.
 */
object PlanInjection {

    fun inject(
        workouts: MutableList<MutableMap<String, Any?>>,
        strength: StrengthInputs?,
        stretchCandidates: List<StretchCandidate>,
        today: CivilDate,
        regenSalt: String,
    ): MutableList<MutableMap<String, Any?>> {
        var out = workouts
        // A goal without include_strength, or an empty library, gets none —
        // the server's two early returns.
        if (strength != null && strength.library.isNotEmpty()) {
            val family = PlanBase.sportFamily((strength.eventSport ?: "running").lowercase())
            val generated = StrengthPlan.generate(
                goal = strength.goal,
                sportFamily = family,
                today = today,
                existingWorkouts = out.map {
                    ExistingWorkout(it["scheduled_date"] as CivilDate?, it["workout_type"] as String?)
                },
                equipment = strength.equipment,
                library = strength.library,
                strengthRecords = strength.strengthRecords,
                injuries = strength.injuries,
                sessionsCount = strength.sessionsCount,
                units = strength.units,
                preferred = strength.preferred.ifEmpty { null },
                excluded = strength.excluded.ifEmpty { null },
                sessionMaxMinutes = strength.sessionMaxMinutes,
                regenSalt = regenSalt,
                confirmedNo = strength.confirmedNo.ifEmpty { null },
                stretchCandidates = stretchCandidates,
                experience = strength.experience,
                anchorDate = strength.anchorDate,
            )
            out = PlanAssembly.attachCustomWorkouts(out, generated, strength.customs, strength.recentlyUsed)
        }
        if (stretchCandidates.isEmpty()) return out
        return PlanAssembly.injectStretchFlows(out) { req ->
            val flow = StretchPlanning.postActivityFlow(
                sport = req.sport,
                candidates = stretchCandidates,
                primaryMuscles = req.primaryMuscles,
                regenSalt = regenSalt,
                varietyKey = req.varietyKey,
                isStrength = req.isStrength,
                cooldownTheme = req.cooldownTheme as String?,
            )
            PlanAssembly.FlowMeta(flow.steps, flow.title, flow.tagline)
        }
    }
}
