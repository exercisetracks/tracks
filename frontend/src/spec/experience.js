// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Experience-level UI options and the default-tier lookup, over the
 * generated table (spec/strength.yaml -> src/spec/strength.js).
 *
 * Hand-written, unlike the table it reads. Mirrors
 * backend/app/calculators/strength_plan/leveling.experience_default_tier —
 * see that module's docstring for the full rationale. This is the original
 * frontend/src/lib/experienceLevels.js's own logic, just sourced from the
 * shared table instead of a literal object; frontend/src/lib/experienceLevels.js
 * is now a thin re-export of this module, matching the
 * sportUtils.js/spec/taxonomy.js split.
 *
 * `experienceDefaultTier` returning `null` for an unrecognised experience
 * (rather than the backend's UNKNOWN_FALLBACK_TIER=3) is the ORIGINAL
 * frontend behaviour, preserved deliberately: the two callers differ (the
 * backend always needs *a* tier to generate a plan; the frontend uses null to
 * mean "don't show a suggestion yet"). Collapsing that difference would be a
 * behaviour change, not a refactor.
 */
import { EXPERIENCE_FROM_FREQUENCY, EXPERIENCE_LEVELS, EXPERIENCE_TABLE } from "./strength";

export const EXPERIENCE_OPTIONS = EXPERIENCE_LEVELS.map((value) => ({
  value,
  label: EXPERIENCE_TABLE[value].label,
  blurb: EXPERIENCE_TABLE[value].blurb,
}));

export const EXPERIENCE_LABEL = Object.fromEntries(
  EXPERIENCE_OPTIONS.map((o) => [o.value, o.label]),
);

export function experienceDefaultTier(experience, isEndurance) {
  const entry = EXPERIENCE_TABLE[experience];
  if (!entry) return null;
  return isEndurance ? entry.default_tier.endurance : entry.default_tier.strength;
}

// The level a plan is built from: an explicit `strength_experience` if set
// (an accepted coach-note suggestion, or an answer from before onboarding
// asked frequency instead), else the one how often the user strength-trains
// implies. Mirrors backend leveling.effective_experience.
export function effectiveExperience(settings) {
  return settings?.strength_experience
    ?? EXPERIENCE_FROM_FREQUENCY[settings?.activity_frequency?.strength]
    ?? null;
}
