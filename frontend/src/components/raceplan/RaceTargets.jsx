// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// One line under the split slider: the whole-race numbers a cycling or MTB
// plan sets beside its lap targets — the average HR ceiling, and for a hill
// climb the W/kg to aim for (with the watts it means at the rider's weight).
// They used to fill a "Race-day strategy" card of their own, together with a
// carbs target that the fuel plan now owns; on their own they are one line.
// The phone shows the same line (RaceTargetsLine in RacePlansScreen.kt).

export function raceTargetsText(plan) {
  const parts = [];
  if (plan?.target_hr_ceiling) parts.push(`Average HR ≤ ${plan.target_hr_ceiling} bpm`);
  if (plan?.climb_target_w_per_kg) {
    const watts = plan.climb_target_watts ? ` (≈ ${plan.climb_target_watts} W)` : "";
    parts.push(`Climb at ${plan.climb_target_w_per_kg.toFixed(1)} W/kg${watts}`);
  }
  return parts.join(" · ");
}

export default function RaceTargets({ plan }) {
  const text = raceTargetsText(plan);
  if (!text) return null;
  return <p className="mt-3 text-sm tabular-nums text-slate-600 dark:text-slate-300">{text}</p>;
}
