// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Coros-style split-strategy slider for the Race Plan page. Drives the
// positive/even/negative split value (-1..1) and previews the resulting
// fastest/slowest pace (or highest/lowest power for cycling) from the live
// recomputed laps. Self-contained: state lives in the parent, passed via props.

import { useMemo } from "react";
import { MAX_SPREAD, fmtPace } from "./format";

export default function SplitSlider({ value = 0, onChange, displayLaps, imperial, isCycling = false }) {
  const spread = Math.abs(value) * MAX_SPREAD;
  const label  = value < -0.05 ? "Positive split"
               : value >  0.05 ? "Negative split"
               : "Even pace";
  const labelClass = value < -0.05 ? "text-orange-500 dark:text-orange-400"
                   : value >  0.05 ? "text-accent-600 dark:text-accent-400"
                   : "text-slate-500 dark:text-slate-400";

  const summary = useMemo(() => {
    if (!displayLaps?.length) return null;
    if (isCycling && displayLaps[0]?.target_watts != null) {
      const sorted = [...displayLaps].sort((a, b) => (b.target_watts - a.target_watts));
      return { fastest: sorted[0].target_watts, slowest: sorted[sorted.length - 1].target_watts, unit: "W" };
    }
    const sorted = [...displayLaps].sort((a, b) => a.target_sec_per_km - b.target_sec_per_km);
    return { fastest: sorted[0].target_sec_per_km, slowest: sorted[sorted.length - 1].target_sec_per_km, unit: "pace" };
  }, [displayLaps, isCycling]);

  return (
    <div className="space-y-3">
      {/* Pace / power extremes preview */}
      <div className="flex items-center gap-3 h-6 text-sm flex-wrap">
        {summary ? (
          summary.unit === "W" ? (
            <>
              <span className="text-accent-600 dark:text-accent-400 font-mono font-semibold">{summary.fastest}W</span>
              <span className="text-slate-400 text-xs">highest</span>
              <span className="text-slate-300 dark:text-slate-600">·</span>
              <span className="text-orange-500 dark:text-orange-400 font-mono font-semibold">{summary.slowest}W</span>
              <span className="text-slate-400 text-xs">lowest</span>
            </>
          ) : (
            <>
              <span className="text-accent-600 dark:text-accent-400 font-mono font-semibold">
                {fmtPace(summary.fastest, imperial)}
              </span>
              <span className="text-slate-400 text-xs">fastest</span>
              <span className="text-slate-300 dark:text-slate-600">·</span>
              <span className="text-orange-500 dark:text-orange-400 font-mono font-semibold">
                {fmtPace(summary.slowest, imperial)}
              </span>
              <span className="text-slate-400 text-xs">slowest</span>
            </>
          )
        ) : (
          <span className="text-slate-400 text-xs">
            {spread > 0.001 ? `±${(spread * 100).toFixed(0)}% spread` : isCycling ? "constant power" : "constant pace"} — generate plan to preview targets
          </span>
        )}
      </div>

      {/* Slider */}
      <input
        type="range" min="-1" max="1" step="0.05" value={value}
        onChange={e => onChange(parseFloat(e.target.value))}
        className="w-full accent-violet-600 cursor-pointer"
      />
      <div className="flex justify-between text-xs text-slate-400 dark:text-slate-500 select-none">
        <div>
          <div>Positive split</div>
          <div className="text-[10px]">faster start</div>
        </div>
        <div className={`font-semibold ${labelClass}`}>{label}</div>
        <div className="text-right">
          <div>Negative split</div>
          <div className="text-[10px]">faster finish</div>
        </div>
      </div>
    </div>
  );
}
