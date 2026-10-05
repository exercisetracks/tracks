// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Lookup tables for the Workouts tab: the workout-level tag list + labels,
// the per-exercise weight-prescription methods, and the mapping from
// MuscleMap picker keys to the exercise-library muscle keys. Centralised so
// the tab and its sub-components stay presentational.

export const VALID_TAGS = [
  "upper_body", "lower_body", "core", "push", "pull", "aerobic", "full_body", "mobility",
];

export const TAG_LABELS = {
  upper_body: "Upper Body", lower_body: "Lower Body", core: "Core",
  push: "Push", pull: "Pull", aerobic: "Aerobic",
  full_body: "Full Body", mobility: "Mobility",
};

export const WEIGHT_METHODS = [
  { key: "percentage_e1rm", label: "% of e1RM" },
  { key: "rpe",             label: "Perceived Exertion" },
  { key: "fixed",           label: "Fixed Weight" },
];

// MuscleMap picker key → exercise-library muscle key (many picker keys collapse
// onto a single library muscle, e.g. all delts → "shoulders").
export const MUSCLE_TO_LIBRARY = {
  chest: "chest", front_delts: "shoulders", side_delts: "shoulders",
  rear_delts: "shoulders", biceps: "biceps", triceps: "triceps",
  forearms: "forearms", traps: "upper_back", lats: "lats", mid_back: "upper_back",
  lower_back: "lower_back", abs: "core", obliques: "obliques",
  hip_flexors: "hip_flexors", glutes: "glutes", quads: "quads",
  hamstrings: "hamstrings", calves_front: "calves", calves_back: "calves",
  adductors: "hip_adductors", neck: "neck",
  ankles: "calves", hands: "hands", feet: "feet",
};
