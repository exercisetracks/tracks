// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// One row in a workout's step list. Pure presentational — renders a single
// structure step (walk / run / warmup / cooldown / interval set / fartlek)
// based on `step.type`. Driven entirely by the `step` prop plus the `imperial`
// unit flag; returns null for unrecognised step types.

import { PACE_LABELS } from "./constants";

export default function StepRow({ step, imperial = false }) {
  const t = step.type;
  if (t === "walk") {
    return (
      <div className="flex items-start gap-3 py-1.5">
        <div className="w-2 h-2 rounded-full bg-slate-200 dark:bg-slate-700 mt-1.5 shrink-0" />
        <div className="min-w-0">
          <span className="text-sm font-medium text-slate-500 dark:text-slate-400">Walk</span>
          <span className="text-sm text-slate-400 dark:text-slate-500 ml-2">{step.duration_min} min</span>
          {step.note && <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5">{step.note}</p>}
        </div>
      </div>
    );
  }
  if (t === "run" || t === "warmup" || t === "cooldown") {
    // Warm-up / cool-down get fixed labels; a plain "run" is labelled by pace zone.
    const label = t === "warmup" ? "Warm-up" : t === "cooldown" ? "Cool-down" : PACE_LABELS[step.pace] ?? step.pace ?? "Run";
    return (
      <div className="flex items-start gap-3 py-1.5">
        <div className="w-2 h-2 rounded-full bg-slate-300 dark:bg-slate-600 mt-1.5 shrink-0" />
        <div className="min-w-0">
          <span className="text-sm font-medium text-slate-700 dark:text-slate-200">{label}</span>
          {step.duration_min && <span className="text-sm text-slate-500 dark:text-slate-400 ml-2">{step.duration_min} min</span>}
          {step.note && <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5">{step.note}</p>}
        </div>
      </div>
    );
  }
  if (t === "interval_set") {
    return (
      <div className="flex items-start gap-3 py-1.5">
        <div className="w-2 h-2 rounded-full bg-red-400 dark:bg-red-500 mt-1.5 shrink-0" />
        <div className="min-w-0">
          <span className="text-sm font-medium text-slate-700 dark:text-slate-200">
            {step.reps}×{imperial ? `${Math.round(step.distance_m * 1.09361)} yd` : `${step.distance_m} m`}
          </span>
          <span className="text-sm text-slate-500 dark:text-slate-400 ml-2">
            {step.rest_sec}s recovery jog
          </span>
          {step.note && <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5">{step.note}</p>}
        </div>
      </div>
    );
  }
  if (t === "fartlek") {
    return (
      <div className="flex items-start gap-3 py-1.5">
        <div className="w-2 h-2 rounded-full bg-purple-400 dark:bg-purple-500 mt-1.5 shrink-0" />
        <div className="min-w-0">
          <span className="text-sm font-medium text-slate-700 dark:text-slate-200">
            Fartlek {step.duration_min} min
          </span>
          {step.note && <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5">{step.note}</p>}
        </div>
      </div>
    );
  }
  return null;
}
