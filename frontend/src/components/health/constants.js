// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Colour palettes and lookup tables for the Health page: sleep-stage colours,
// injury severity/timeline colour ramps, and the injury-form option lists +
// empty-draft factory. Centralised so the sub-components stay presentational.

import { isoToday } from "./helpers";

// Sleep-stage colours (shared by the sleep chart, tooltip and breakdown row).
export const SLEEP_COLORS = {
  deep:  "#6366f1",
  rem:   "#a855f7",
  light: "#38bdf8",
  other: "#94a3b8",
};

// Severity badge colours indexed by severity 1–10 (index 0 unused).
export const SEV_COLORS = [
  "", // 0 unused
  "bg-accent-100 text-accent-700 dark:bg-accent-900 dark:text-accent-300",
  "bg-accent-100 text-accent-700 dark:bg-accent-900 dark:text-accent-300",
  "bg-yellow-100 text-yellow-700 dark:bg-yellow-900 dark:text-yellow-300",
  "bg-yellow-100 text-yellow-700 dark:bg-yellow-900 dark:text-yellow-300",
  "bg-orange-100 text-orange-700 dark:bg-orange-900 dark:text-orange-300",
  "bg-orange-100 text-orange-700 dark:bg-orange-900 dark:text-orange-300",
  "bg-red-100 text-red-700 dark:bg-red-900 dark:text-red-300",
  "bg-red-100 text-red-700 dark:bg-red-900 dark:text-red-300",
  "bg-red-200 text-red-800 dark:bg-red-800 dark:text-red-100",
  "bg-red-200 text-red-800 dark:bg-red-800 dark:text-red-100",
];

// Cycled bar colours for the injury timeline (one per injury, mod length).
export const TIMELINE_COLORS = [
  "bg-red-400 dark:bg-red-500",
  "bg-orange-400 dark:bg-orange-500",
  "bg-amber-400 dark:bg-amber-500",
  "bg-pink-400 dark:bg-pink-500",
  "bg-rose-400 dark:bg-rose-500",
];

export const BODY_PARTS = [
  "Knee", "Hip", "Ankle", "Shin", "Calf", "Hamstring", "Quadricep",
  "IT Band", "Plantar Fascia", "Achilles", "Back", "Shoulder", "Foot", "Other",
];
export const INJURY_TYPES = [
  "Strain", "Sprain", "Tendinopathy", "Stress fracture", "Bursitis",
  "DOMS", "Contusion", "Overuse", "Other",
];

export const EMPTY_FORM = {
  body_part:   BODY_PARTS[0],
  injury_type: INJURY_TYPES[0],
  severity:    5,
  start_date:  isoToday(),
  end_date:    "",
  notes:       "",
};
