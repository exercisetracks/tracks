// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.spec

/**
 * Equipment vocabulary and experience-level lookups over the generated tables
 * (spec/strength.yaml -> StrengthData.kt).
 *
 * Mirrors backend/app/calculators/strength_plan/leveling.py, the authoritative
 * implementation — see that module's docstring for the full rationale
 * (endurance vs strength-primary tiering, why history always wins over the
 * stage floor). No mobile caller needs these yet; they are ported now so the
 * four table-driven functions exist in all three languages the way the spec
 * itself does, and so spec/fixtures/strength.json can hold all three to the
 * same answer from day one rather than after the first drift.
 */

/** One equipment option: the bare value plus its display label/description. */
data class EquipmentOption(val value: String, val label: String, val description: String)

/** One experience level's full row: UI copy plus the four table-driven knobs
 * it feeds (default tier, difficulty ceiling, periodization stage floor,
 * cold-start weight factor). */
data class ExperienceEntry(
    val label: String,
    val blurb: String,
    val defaultTierEndurance: Int,
    val defaultTierStrength: Int,
    val maxDifficulty: Int?,
    val stageFloor: Int,
    val startingWeightFactor: Double,
)

val equipmentOptions: List<EquipmentOption> get() = EQUIPMENT
val validEquipment: Set<String> get() = VALID_EQUIPMENT
val experienceLevels: List<String> get() = EXPERIENCE_LEVELS
val experienceTable: Map<String, ExperienceEntry> get() = EXPERIENCE_TABLE

/**
 * The experience level the generator uses: [explicit] if set (an accepted
 * coach-note suggestion, or an answer from before onboarding asked frequency
 * instead), else the one how often the user strength-trains implies —
 * `activity_frequency["strength"]` through spec/strength.yaml's
 * experience_from_frequency. The server's twin is leveling.effective_experience.
 */
fun effectiveExperience(explicit: String?, frequencies: Map<String, Any?>): String? =
    explicit ?: (frequencies["strength"] as? String)?.let { EXPERIENCE_FROM_FREQUENCY[it] }

/**
 * Suggested strength_tier for a new goal. Endurance-primary goals keep
 * strength supplementary regardless of lifting pedigree — only a brand-new
 * lifter gets the lower endurance tier (1); every other level uses 2.
 * Strength-primary goals scale sessions/week with experience (2-5). An
 * experience not in [EXPERIENCE_TABLE] (including null) always returns
 * [UNKNOWN_FALLBACK_TIER], regardless of `enduranceGoal`.
 */
fun experienceDefaultTier(experience: String?, enduranceGoal: Boolean): Int {
    val entry = experience?.let { EXPERIENCE_TABLE[it] } ?: return UNKNOWN_FALLBACK_TIER
    return if (enduranceGoal) entry.defaultTierEndurance else entry.defaultTierStrength
}

/** Difficulty ceiling from experience, applied on top of the tier cap (min of
 * the two). Null = no extra cap. */
fun experienceMaxDifficulty(experience: String?): Int? =
    experience?.let { EXPERIENCE_TABLE[it]?.maxDifficulty }

/** Floor on the cumulative-session count used to pick the periodization
 * stage. Logged history beyond the floor takes over naturally. */
fun experienceStageFloor(experience: String?): Int =
    experience?.let { EXPERIENCE_TABLE[it]?.stageFloor } ?: 0

/** Extra conservatism on cold-start loads for someone who has never trained. */
fun startingWeightFactor(experience: String?): Double =
    experience?.let { EXPERIENCE_TABLE[it]?.startingWeightFactor } ?: 1.0
