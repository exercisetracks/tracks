// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Km-by-km (or mile-by-mile) pace/power results block for running & cycling on
// the Race Plan page: a header row (predicted finish, FTP for cycling, and the
// fastest/slowest colour legend), the lap PaceTable itself, and a methodology
// footnote. Purely presentational and prop-driven — the caller supplies the
// already-computed `laps` and `perLapDrinks` plus the plan/flags it needs.

import PaceTable from "./PaceTable";

export default function PaceResults({
  plan,
  laps,
  perLapDrinks,
  imperial,
  isCycling,
  hasCourse,
  hrMode,
  maxHr,
  sport,
}) {
  return (
    <>
      {/* Header: predicted finish + (cycling) FTP + fastest/slowest legend */}
      <div className="flex items-center justify-between mb-3 flex-wrap gap-2">
        <div>
          <span className="text-2xl font-bold font-mono text-violet-600 dark:text-violet-400">
            {plan.predicted_time}
          </span>
          <span className="ml-2 text-sm text-slate-500 dark:text-slate-400">predicted finish</span>
          {isCycling && plan.ftp && (
            <span className="ml-3 text-xs text-slate-400">FTP: {plan.ftp}W</span>
          )}
        </div>
        <div className="flex items-center gap-3 text-xs text-slate-400">
          <span className="flex items-center gap-1">
            <span className="w-2 h-2 rounded-full bg-accent-500 inline-block" />
            {isCycling ? "highest power" : "fastest"}
          </span>
          <span className="flex items-center gap-1">
            <span className="w-2 h-2 rounded-full bg-orange-400 inline-block" />
            {isCycling ? "lowest power" : "slowest"}
          </span>
          {hasCourse && !isCycling && <span>GAP = equal-effort flat equivalent</span>}
        </div>
      </div>

      <PaceTable
        laps={laps}
        hasCourse={hasCourse}
        imperial={imperial}
        hrMode={hrMode}
        maxHr={maxHr}
        sport={sport}
        ftp={plan?.ftp}
        perLapDrinks={perLapDrinks}
      />

      {/* Methodology footnote — differs for cycling (physics/W) vs running (VDOT) */}
      <p className="text-xs text-slate-400 dark:text-slate-500 mt-2">
        {isCycling
          ? `Accounts for FTP, freshness${plan.weather_snapshot ? ", race-day heat" : ""}${hasCourse ? ", and course grade (cycling physics model)" : ""}. Wind modelled aerodynamically (v² drag). Generated ${new Date(plan.generated_at).toLocaleString()}.`
          : `Accounts for VDOT fitness, freshness${plan.weather_snapshot ? ", race-day conditions" : ""}${hasCourse ? ", and course grade (Minetti 2002)" : ""}. Generated ${new Date(plan.generated_at).toLocaleString()}.`
        }
      </p>
    </>
  );
}
