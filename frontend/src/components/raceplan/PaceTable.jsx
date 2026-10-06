// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Km/mile-by-km/mile target table for the Race Plan page. Renders pace targets
// (running) or power + %FTP (cycling), plus optional grade / GAP, per-lap HR
// ceiling, and weather-aware drink markers. Fully driven by props — used both by
// the main running/cycling view and per-leg inside the triathlon breakdown.

import { fmtDist, gradientColor, fmtGradient } from "./format";

export default function PaceTable({ laps, hasCourse, imperial, hrMode, maxHr, sport = "running", ftp, perLapDrinks = null }) {
  if (!laps?.length) return null;
  const isCycling = sport === "cycling";
  const hasDrinks = perLapDrinks?.some(d => d !== null) ?? false;

  // For cycling: rank by watts (higher = harder = "fastest"); for running: rank by pace
  const fastest = isCycling
    ? laps.reduce((b, l) => (l.target_watts ?? 0) > (b.target_watts ?? 0) ? l : b, laps[0]).lap
    : laps.reduce((b, l) => l.target_sec_per_km < b.target_sec_per_km ? l : b, laps[0]).lap;
  const slowest = isCycling
    ? laps.reduce((b, l) => (l.target_watts ?? 0) < (b.target_watts ?? 0) ? l : b, laps[0]).lap
    : laps.reduce((b, l) => l.target_sec_per_km > b.target_sec_per_km ? l : b, laps[0]).lap;

  const lapLabel = imperial ? "Mile" : "Km";
  const showHr   = hrMode === "pace_hr" && maxHr;

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="section-title border-b border-slate-200 dark:border-slate-700">
            <th className="pb-1.5 text-left">{lapLabel}</th>
            {isCycling ? (
              <>
                <th className="pb-1.5 text-right">Target power</th>
                <th className="pb-1.5 text-right">% FTP</th>
              </>
            ) : (
              <th className="pb-1.5 text-right">Target pace</th>
            )}
            {hasCourse && <th className="pb-1.5 text-right">Grade</th>}
            {hasCourse && <th className="pb-1.5 text-right">{isCycling ? "Flat equiv." : "GAP"}</th>}
            {showHr    && <th className="pb-1.5 text-right">HR ceiling</th>}
            {hasDrinks && <th className="pb-1.5 text-right">Drink</th>}
          </tr>
        </thead>
        <tbody>
          {laps.map((lap, i) => (
            <tr key={lap.lap} className={`border-b border-slate-100 dark:border-slate-800 ${
              i % 2 === 0 ? "" : "bg-slate-50/50 dark:bg-slate-800/20"
            }`}>
              <td className="py-1 pr-2.5">
                <span className="font-mono text-slate-700 dark:text-slate-300">{lap.lap}</span>
                {lap.distance_m < (imperial ? 1500 : 900) && (
                  <span className="ml-1 text-[10px] text-slate-400">
                    ({fmtDist(lap.distance_m, imperial)})
                  </span>
                )}
              </td>
              {isCycling ? (
                <>
                  <td className={`py-1 text-right font-mono font-semibold ${
                    lap.lap === fastest ? "text-accent-600 dark:text-accent-400" :
                    lap.lap === slowest ? "text-orange-500 dark:text-orange-400" :
                    "text-slate-700 dark:text-slate-300"
                  }`}>
                    {lap.target_watts != null ? `${lap.target_watts}W` : lap.target_pace}
                  </td>
                  <td className="py-1 text-right font-mono text-xs text-slate-500 dark:text-slate-400">
                    {lap.target_watts_pct_ftp != null ? `${lap.target_watts_pct_ftp}%` : "—"}
                  </td>
                </>
              ) : (
                <td className={`py-1 text-right font-mono font-semibold ${
                  lap.lap === fastest ? "text-accent-600 dark:text-accent-400" :
                  lap.lap === slowest ? "text-orange-500 dark:text-orange-400" :
                  "text-slate-700 dark:text-slate-300"
                }`}>
                  {lap.target_pace}
                </td>
              )}
              {hasCourse && (
                <td className={`py-1 text-right font-mono text-xs ${gradientColor(lap.gradient)}`}>
                  {fmtGradient(lap.gradient)}
                </td>
              )}
              {hasCourse && (
                <td className="py-1 text-right font-mono text-slate-600 dark:text-slate-400 text-xs">
                  {isCycling ? (lap.target_pace ?? "—") : lap.grade_adj_pace}
                </td>
              )}
              {showHr && (
                <td className="py-1 text-right font-mono text-xs text-rose-600 dark:text-rose-400">
                  ≤ {lap.hr_ceiling ?? maxHr}
                </td>
              )}
              {hasDrinks && (
                <td className={`py-1 text-right font-mono text-xs font-semibold ${
                  perLapDrinks[i] ? "text-amber-600 dark:text-amber-400" : "text-slate-300 dark:text-slate-600"
                }`}>
                  {perLapDrinks[i] ? `Sip ${perLapDrinks[i].sipMl} ml` : "—"}
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
