// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import React, { useState } from "react";
import { WeightDisplay } from "../pills/WeightDisplay";
import { DurationDisplay } from "../pills/DurationDisplay";

function formatWeight(kg, imperial) {
  if (kg == null || kg === 0) return "Bodyweight";
  if (imperial) return `${(kg * 2.20462).toFixed(1)} lbs`;
  return `${kg % 1 === 0 ? kg : kg.toFixed(1)} kg`;
}

/**
 * ExerciseCard - Displays an exercise with its sets
 */
export function ExerciseCard({ name, sets, summary, imperial = false }) {
  const [isOpen, setIsOpen] = useState(false);

  return (
    <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 overflow-hidden">
      <button
        className="w-full flex items-center justify-between px-3.5 py-2.5 text-left hover:bg-slate-50 dark:hover:bg-slate-800/50 transition-colors"
        onClick={() => setIsOpen(!isOpen)}
      >
        <div className="min-w-0 flex-1 flex items-center gap-3">
          <div>
            <p className="text-sm font-semibold text-slate-900 dark:text-white truncate">{name}</p>
            <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5">
              {summary?.sets} set{summary?.sets !== 1 ? "s" : ""}
              {summary?.reps > 0 && ` · ${summary.reps} reps`}
              {summary?.maxWeight > 0 && ` · up to ${formatWeight(summary.maxWeight, imperial)}`}
              {summary?.bestOneRM > 0 && ` · Estimated 1 rep maximum weight: ${formatWeight(summary.bestOneRM, imperial)}`}
            </p>
          </div>
        </div>
        <svg className={`w-4 h-4 text-slate-400 shrink-0 ml-3 transition-transform ${isOpen ? "rotate-180" : ""}`}
          fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" d="M19 9l-7 7-7-7" />
        </svg>
      </button>

      {isOpen && (
        <div className="border-t border-slate-100 dark:border-slate-800 divide-y divide-slate-50 dark:divide-slate-800/60">
          {sets.map((set, i) => (
            <div key={i} className="flex items-center gap-3 px-3.5 py-1.5">
              <span className="text-xs text-slate-400 dark:text-slate-500 w-6 shrink-0 tabular-nums">{i + 1}</span>
              <div className="flex-1 flex items-center gap-3 flex-wrap">
                {set.weight_kg != null && (
                  <span className="text-sm font-medium text-slate-700 dark:text-slate-200">
                    <WeightDisplay kg={set.weight_kg} imperial={imperial} />
                  </span>
                )}
                {set.repetitions != null && (
                  <span className="text-xs text-slate-500 dark:text-slate-400">
                    × {set.repetitions} rep{set.repetitions !== 1 ? "s" : ""}
                  </span>
                )}
                {set.duration_seconds != null && (
                  <span className="text-xs text-slate-400 dark:text-slate-500">
                    <DurationDisplay seconds={set.duration_seconds} format="compact" />
                  </span>
                )}
                {set.weight_kg > 0 && set.repetitions > 0 && (
                  <span className="text-xs text-slate-400 dark:text-slate-500 ml-auto">
                    Estimated 1 rep maximum weight: ~{formatWeight(set.weight_kg * (1 + set.repetitions / 30), imperial)}
                  </span>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}