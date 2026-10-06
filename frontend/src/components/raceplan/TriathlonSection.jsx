// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Triathlon race plan: per-leg (swim / bike / run) summary cards, the total
// time including transitions, and a PaceTable per leg that has lap paces. Legs
// come from plan.weather_snapshot.triathlon_legs; fully driven by props.

import PaceTable from "./PaceTable";
import { TRI_LEG_STYLES } from "./constants";

export default function TriathlonSection({ plan, imperial, hrMode, maxHr, hasCourse }) {
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

      {/* Per-leg lap tables */}
      {legs.filter(l => l.lap_paces?.length).map(leg => {
        const s = TRI_LEG_STYLES[leg.leg] ?? TRI_LEG_STYLES.run;
        return (
        <div key={leg.leg} className="space-y-2">
          <h3 className="section-title">
            {s.label} splits
          </h3>
          <PaceTable
            laps={leg.lap_paces}
            hasCourse={hasCourse && leg.leg !== "swim"}
            imperial={imperial}
            hrMode={hrMode}
            maxHr={maxHr}
            sport={leg.leg === "bike" ? "cycling" : "running"}
          />
        </div>
        );
      })}
    </div>
  );
}
