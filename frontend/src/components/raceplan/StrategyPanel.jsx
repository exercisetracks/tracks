// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Race-day strategy panel for the Race Plan page: an average-HR-ceiling card, a
// fueling-target card, and (for climbs) a W/kg climbing-target card. Purely
// presentational — reads only `targetHrCeiling` and `fuelingPlan` from props and
// renders whichever cards have data. The caller decides whether to show the
// wrapping Section at all (it only mounts this when at least one value exists).

export default function StrategyPanel({ targetHrCeiling, fuelingPlan }) {
  return (
    <div className="grid sm:grid-cols-2 gap-4 text-sm">
      {/* Sustainable average HR ceiling for the whole race */}
      {targetHrCeiling && (
        <div className="space-y-1">
          <p className="section-title">
            Average HR ceiling
          </p>
          <p className="text-2xl font-mono font-bold text-rose-600 dark:text-rose-400">
            ≤ {targetHrCeiling} bpm
          </p>
          <p className="text-xs text-slate-500 dark:text-slate-400">
            Sustained HR above this is unlikely to be repeatable for the full race.
          </p>
        </div>
      )}
      {/* Carbohydrate fueling target + reminder cadence */}
      {fuelingPlan && (
        <div className="space-y-1">
          <p className="section-title">
            Fueling target
          </p>
          <p className="text-2xl font-mono font-bold text-amber-600 dark:text-amber-400">
            {fuelingPlan.carbs_g_per_h} g/h carbs
          </p>
          <p className="text-xs text-slate-500 dark:text-slate-400">
            Reminder every {fuelingPlan.reminder_min} min. {fuelingPlan.notes}
          </p>
        </div>
      )}
      {/* Cycling climbs only: Coggan-based W/kg (and absolute watts) target */}
      {fuelingPlan?.target_w_per_kg && (
        <div className="space-y-1 sm:col-span-2 pt-1.5 border-t border-slate-200 dark:border-slate-800">
          <p className="section-title">
            Climbing target
          </p>
          <p className="text-2xl font-mono font-bold text-accent-600 dark:text-accent-400">
            {fuelingPlan.target_w_per_kg.toFixed(1)} W/kg
            {fuelingPlan.target_watts && (
              <span className="text-base font-normal text-slate-500 dark:text-slate-400 ml-2">
                ≈ {fuelingPlan.target_watts} W
              </span>
            )}
          </p>
          <p className="text-xs text-slate-500 dark:text-slate-400">
            Coggan "Good" amateur category for this climb duration.
          </p>
        </div>
      )}
    </div>
  );
}
