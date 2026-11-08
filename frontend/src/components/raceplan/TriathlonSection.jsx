// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Triathlon race plan: per-leg (swim / bike / run) summary cards, the total
// time including transitions, and a graph of each leg's target. Legs come
// from plan.weather_snapshot.triathlon_legs; fully driven by props.

import ElevationChart from "./ElevationChart";
import { TRI_LEG_STYLES } from "./constants";

export default function TriathlonSection({ plan, imperial }) {
  const legs = plan.weather_snapshot?.triathlon_legs ?? [];
  if (!legs.length) return null;

  return (
    <div className="space-y-4">
      {/* Summary row */}
      <div className="flex flex-wrap gap-3">
        {legs.map(leg => {
          const s = TRI_LEG_STYLES[leg.leg] ?? TRI_LEG_STYLES.run;
          return (
            <div key={leg.leg} className={`flex-1 min-w-[120px] rounded-lg ${s.card} px-2.5 py-1.5`}>
              <div className="text-xs text-slate-500 dark:text-slate-400 mb-0.5">{s.label}</div>
              <div className={`text-lg font-bold font-mono ${s.text}`}>{leg.predicted_time}</div>
              {leg.target_pace && (
                <div className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">{leg.target_pace}</div>
              )}
            </div>
          );
        })}
      </div>

      {/* Total */}
      <div className="flex items-center gap-2">
        <span className="text-xl font-bold font-mono text-accent-600 dark:text-accent-400">
          {plan.predicted_time}
        </span>
        <span className="text-sm text-slate-500 dark:text-slate-400">total (incl. transitions)</span>
      </div>

      {/* A graph per leg: the swim's pace per 100 m, the bike's power, the
          run's pace — the lap tables they replaced ran to ninety rows on a 70.3. */}
      {legs.map(leg => {
        const s = TRI_LEG_STYLES[leg.leg] ?? TRI_LEG_STYLES.run;
        // The swim's pace is the leg's own — CSS at the race's intensity — or,
        // on a plan generated before legs carried it, CSS itself.
        const swimPerKm = leg.target_sec_per_km ?? (plan.css_sec_per_100m ? plan.css_sec_per_100m * 10 : null);
        const laps = leg.leg === "swim"
          ? (swimPerKm ? [{ lap: 1, distance_m: leg.distance_m, target_sec_per_km: swimPerKm }] : [])
          : (leg.lap_paces ?? []);
        if (!laps.length) return null;
        return (
          <div key={leg.leg}>
            <h3 className="section-title">{s.label}</h3>
            <ElevationChart laps={laps} sport={leg.leg === "bike" ? "cycling" : "running"}
              metric={leg.leg === "swim" ? "swim" : undefined} imperial={imperial} height={120} />
          </div>
        );
      })}
    </div>
  );
}
