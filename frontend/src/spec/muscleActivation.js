// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Muscle activation over the generated table (spec/muscle_groups.yaml ->
 * src/spec/muscleGroups.js).
 *
 * Hand-written, unlike the table it walks. This module IS the original
 * frontend/src/utils/muscleGroups.js — spec/fixtures/muscle_groups.json was
 * baselined against it before this move, so it stays the reference
 * implementation while backend/app/calculators/muscle_activation.py and
 * mobile/core's spec/MuscleGroups.kt are new ports of it. What keeps the
 * three honest is that shared fixture corpus: an implementation that
 * diverges fails its own test suite.
 *
 * frontend/src/utils/muscleGroups.js is now a thin re-export of this module,
 * matching the sportUtils.js/spec/taxonomy.js split.
 */
import { CATEGORY_MUSCLES, MUSCLE_LABELS, CATEGORY_LABELS } from "./muscleGroups";

export { CATEGORY_MUSCLES };

const PRIMARY_WEIGHT = 1.0;
const SECONDARY_WEIGHT = 0.4;

/**
 * Given the parsed sets array (from API), compute a 0..1 activation score
 * per muscle key. Weighted by (sets x reps) when available so that a 5x5
 * squat session loads quads/glutes more than a single isolated curl.
 *
 * Returns { activation: { muscleKey: 0..1 }, totals: { muscleKey: rawScore },
 *           categoryCounts: { category: setCount } }.
 */
export function computeMuscleActivation(sets) {
  const totals = {};
  const categoryCounts = {};

  const activeSets = (sets || []).filter((s) => s.set_type === "active");

  for (const set of activeSets) {
    const cat = (set.exercise_category || "").toLowerCase();
    if (!cat || cat === "65534" || cat === "unknown") continue;

    const map = CATEGORY_MUSCLES[cat];
    if (!map) continue;

    categoryCounts[cat] = (categoryCounts[cat] || 0) + 1;

    // Workload weight: reps if available, else 1 per set. Cap each set's
    // contribution so very high-rep sets (e.g. 100-rep core sets) don't
    // dominate a session that also included heavy compounds.
    const reps = Math.max(1, Math.min(40, set.repetitions || 10));

    for (const m of map.primary)   totals[m] = (totals[m] || 0) + reps * PRIMARY_WEIGHT;
    for (const m of map.secondary) totals[m] = (totals[m] || 0) + reps * SECONDARY_WEIGHT;
  }

  const max = Math.max(0, ...Object.values(totals));
  const activation = {};
  if (max > 0) {
    for (const [k, v] of Object.entries(totals)) {
      activation[k] = v / max;
    }
  }

  return { activation, totals, categoryCounts };
}

export function muscleLabel(key) {
  return MUSCLE_LABELS[key] || key;
}

export function categoryLabel(key) {
  if (!key) return "Unknown";
  return CATEGORY_LABELS[key] || key.replace(/_/g, " ").replace(/\b\w/g, (c) => c.toUpperCase());
}
