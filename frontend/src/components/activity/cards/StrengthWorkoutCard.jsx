// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useState } from "react";

const MUSCLE_COLORS = {
  quads:         "bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-300",
  hamstrings:    "bg-indigo-100 text-indigo-700 dark:bg-indigo-900/40 dark:text-indigo-300",
  glutes:        "bg-purple-100 text-purple-700 dark:bg-purple-900/40 dark:text-purple-300",
  calves:        "bg-teal-100 text-teal-700 dark:bg-teal-900/40 dark:text-teal-300",
  hip_flexors:   "bg-cyan-100 text-cyan-700 dark:bg-cyan-900/40 dark:text-cyan-300",
  lower_back:    "bg-orange-100 text-orange-700 dark:bg-orange-900/40 dark:text-orange-300",
  upper_back:    "bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300",
  lats:          "bg-yellow-100 text-yellow-700 dark:bg-yellow-900/40 dark:text-yellow-300",
  chest:         "bg-accent-100 text-accent-700 dark:bg-accent-900/40 dark:text-accent-300",
  shoulders:     "bg-lime-100 text-lime-700 dark:bg-lime-900/40 dark:text-lime-300",
  biceps:        "bg-sky-100 text-sky-700 dark:bg-sky-900/40 dark:text-sky-300",
  triceps:       "bg-violet-100 text-violet-700 dark:bg-violet-900/40 dark:text-violet-300",
  core:          "bg-rose-100 text-rose-700 dark:bg-rose-900/40 dark:text-rose-300",
  forearms:      "bg-pink-100 text-pink-700 dark:bg-pink-900/40 dark:text-pink-300",
  obliques:      "bg-fuchsia-100 text-fuchsia-700 dark:bg-fuchsia-900/40 dark:text-fuchsia-300",
  hip_abductors: "bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300",
};

const RPE_COLOR = {
  5: "text-accent-600 dark:text-accent-400",
  6: "text-accent-600 dark:text-accent-400",
  7: "text-amber-600 dark:text-amber-400",
  8: "text-orange-600 dark:text-orange-400",
  9: "text-red-600 dark:text-red-400",
};

function MusclePill({ muscle }) {
  const cls = MUSCLE_COLORS[muscle] ?? "bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-300";
  const label = muscle.replace(/_/g, " ");
  return (
    <span className={`inline-block px-1 py-0.5 rounded text-xs font-medium ${cls}`}>
      {label}
    </span>
  );
}

function ExerciseRow({ step }) {
  const { name, sets, reps, weight_kg, target_rpe, primary_muscles } = step;
  const weightLabel = weight_kg > 0 ? `${weight_kg} kg` : "BW";
  const rpeColor = RPE_COLOR[target_rpe] ?? "text-slate-500";

  return (
    <div className="flex items-start gap-3 py-1.5 border-b border-slate-100 dark:border-slate-700/50 last:border-0">
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2 flex-wrap">
          <span className="text-sm font-medium text-slate-800 dark:text-slate-200">{name}</span>
        </div>
        {primary_muscles?.length > 0 && (
          <div className="flex flex-wrap gap-1 mt-1">
            {primary_muscles.map(m => <MusclePill key={m} muscle={m} />)}
          </div>
        )}
      </div>
      <div className="shrink-0 text-right space-y-0.5">
        <p className="text-sm font-semibold text-slate-800 dark:text-slate-100 tabular-nums">
          {sets} × {reps}
        </p>
        <p className="text-xs text-slate-500 dark:text-slate-400 tabular-nums">{weightLabel}</p>
        <p className={`text-xs font-medium ${rpeColor} tabular-nums`}>Perceived Exertion {target_rpe}</p>
      </div>
    </div>
  );
}

export default function StrengthWorkoutCard({ workout, compact = false }) {
  const { title, description, duration_minutes, steps = [] } = workout;
  const [descExpanded, setDescExpanded] = useState(false);

  const exercises = steps.filter(s => s.type === "strength_exercise");
  const stretches  = steps.filter(s => s.type === "mobility_exercise");

  return (
    <div className="rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 overflow-hidden">
      {/* Header */}
      <div className="px-3.5 pt-2.5 pb-1.5 border-b border-slate-100 dark:border-slate-700 flex items-start justify-between gap-2">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2 flex-wrap">
            <span className="inline-block px-1.5 py-0.5 rounded text-xs font-semibold bg-violet-100 text-violet-700 dark:bg-violet-900/40 dark:text-violet-300">
              Strength
            </span>
            <span className="text-sm font-semibold text-slate-800 dark:text-slate-100 truncate">{title}</span>
          </div>
          {description && !compact && (
            <button
              onClick={() => setDescExpanded(e => !e)}
              className="text-left w-full mt-1 group"
            >
              <p className={`text-xs text-slate-500 dark:text-slate-400 ${descExpanded ? "" : "line-clamp-2"}`}>
                {description}
              </p>
              <span className="text-xs text-accent-600 dark:text-accent-400 group-hover:underline">
                {descExpanded ? "Show less ↑" : "Show more ↓"}
              </span>
            </button>
          )}
        </div>
        {duration_minutes && (
          <span className="shrink-0 text-xs text-slate-400 dark:text-slate-500 tabular-nums">{duration_minutes} min</span>
        )}
      </div>

      {/* Exercise list */}
      {!compact && exercises.length > 0 && (
        <div className="px-3.5 py-1">
          {exercises.map((s, i) => <ExerciseRow key={i} step={s} />)}
        </div>
      )}

      {compact && exercises.length > 0 && (
        <div className="px-3.5 py-1.5">
          <p className="text-xs text-slate-500 dark:text-slate-400">
            {exercises.length} exercises — {[...new Set(exercises.flatMap(e => e.primary_muscles ?? []))].slice(0, 3).join(", ")}
          </p>
        </div>
      )}

      {/* Stretches footer */}
      {!compact && stretches.length > 0 && (
        <div className="px-3.5 pb-2.5 pt-1 border-t border-slate-100 dark:border-slate-700/50">
          <p className="text-xs font-medium text-slate-500 dark:text-slate-400 mb-1">Post-workout stretches</p>
          <div className="flex flex-wrap gap-1.5">
            {stretches.map((s, i) => (
              <span key={i} className="text-xs px-1.5 py-0.5 rounded-full bg-teal-50 dark:bg-teal-900/30 text-teal-700 dark:text-teal-300 border border-teal-200 dark:border-teal-700">
                {s.name} {s.duration_seconds ? `(${s.duration_seconds}s)` : ""}
              </span>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
