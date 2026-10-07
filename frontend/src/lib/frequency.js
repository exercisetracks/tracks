// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * "How often do you train?" — the onboarding answers stored as
 * `activity_frequency`, a map of sport family to level.
 *
 * The plan generator reads a sport's answer only while there is no history
 * of it, to decide where a first plan starts (calculators/plan/starting.py);
 * the strength answer also stands in for lifting experience
 * (spec/strength.yaml experience_from_frequency). Asked once, at setup, and
 * not shown in Settings afterwards: history replaces it as soon as there is
 * any. The phone asks the same list (ProfileForms.kt FrequencyForm).
 */
export const FREQUENCY_LEVELS = [
  { value: "never",      label: "Never" },
  { value: "occasional", label: "Now and then" },
  { value: "1_2",        label: "1–2× a week" },
  { value: "3_4",        label: "3–4× a week" },
  { value: "5_plus",     label: "5+× a week" },
];

/** Sport families (calculators/training_plan _sport_family) a plan can be built for, plus strength. */
export const FREQUENCY_SPORTS = [
  { value: "running",         label: "Running" },
  { value: "cycling",         label: "Cycling" },
  { value: "swimming",        label: "Swimming" },
  { value: "strength",        label: "Strength training" },
  { value: "mountain_biking", label: "Mountain biking" },
  { value: "hiking",          label: "Hiking" },
  { value: "rowing",          label: "Rowing" },
  { value: "nordic_skiing",   label: "XC skiing" },
  { value: "climbing",        label: "Climbing" },
];
