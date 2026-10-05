// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// One row in a workout's step list. Pure presentational — renders a single
// structure step (walk / run / interval set / fartlek / strength / mobility, or
// a bare note) based on `step.type`. Driven entirely by the `step` prop plus the
// `imperial` unit flag.

import { PACE_LABELS } from "./constants";
import { fmtWeight } from "../../lib/weight";

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
    const label = t === "warmup" ? "Warm-up"
                : t === "cooldown" ? "Cool-down"
                : (PACE_LABELS[step.pace] ?? step.pace ?? "Run");
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
            {step.rest_sec}s recovery
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
  if (t === "strength_exercise") {
    const { name, sets, reps, weight_kg, target_rpe, primary_muscles, cues, tempo, superset_group } = step;
    const weightLabel = fmtWeight(weight_kg, imperial);
    const muscles = (primary_muscles || []).slice(0, 2).map(m => m.replace(/_/g, " ")).join(", ");
    const cue = (cues || [])[0];
    // Superset badge: A/B/C… so paired accessories read as one giant set.
    const supBadge = superset_group != null ? String.fromCharCode(65 + superset_group) : null;
    return (
      <div className="flex items-start gap-3 py-1.5">
        <div className="w-2 h-2 rounded-full bg-violet-400 dark:bg-violet-500 mt-1.5 shrink-0" />
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2 flex-wrap">
            {supBadge && (
              <span className="text-[10px] font-bold px-1 py-0.5 rounded bg-violet-100 dark:bg-violet-900/40 text-violet-700 dark:text-violet-300">
                Superset {supBadge}
              </span>
            )}
            <span className="text-sm font-medium text-slate-700 dark:text-slate-200">{name}</span>
          </div>
          <div className="flex gap-2 mt-0.5 flex-wrap">
            <span className="text-xs text-slate-500 dark:text-slate-400 tabular-nums">{sets}×{reps}</span>
            <span className="text-xs text-slate-400 dark:text-slate-500 tabular-nums">{weightLabel}</span>
            {tempo && <span className="text-xs text-slate-400 dark:text-slate-500">tempo {tempo}</span>}
            {target_rpe && <span className="text-xs text-slate-400 dark:text-slate-500">Perceived Exertion {target_rpe}</span>}
            {muscles && <span className="text-xs text-slate-400 dark:text-slate-500">{muscles}</span>}
          </div>
          {cue && <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5 italic">{cue}</p>}
        </div>
      </div>
    );
  }
  if (t === "mobility_exercise") {
    const { name, duration_seconds, sets, each_side, cues, breath_cue } = step;
    const durLabel = duration_seconds ? `${duration_seconds}s${each_side ? " each side" : ""}` : "";
    const setsLabel = sets > 1 ? ` × ${sets}` : "";
    const cue = (cues || [])[0] || breath_cue;
    return (
      <div className="flex items-start gap-3 py-1.5">
        <div className="w-2 h-2 rounded-full bg-teal-400 dark:bg-teal-500 mt-1.5 shrink-0" />
        <div className="min-w-0">
          <span className="text-sm font-medium text-slate-700 dark:text-slate-200">{name}</span>
          {(durLabel || setsLabel) && (
            <span className="text-xs text-slate-400 dark:text-slate-500 ml-2">
              {durLabel}{setsLabel}
            </span>
          )}
          {cue && <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5 italic">{cue}</p>}
        </div>
      </div>
    );
  }
  if (step.note) {
    return (
      <div className="flex items-start gap-3 py-1.5">
        <div className="w-2 h-2 rounded-full bg-slate-300 dark:bg-slate-600 mt-1.5 shrink-0" />
        <p className="text-sm text-slate-600 dark:text-slate-300">{step.note}</p>
      </div>
    );
  }
  return null;
}
