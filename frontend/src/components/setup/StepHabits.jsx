// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Setup step — how often you train each sport (`activity_frequency`; see
// lib/frequency.js for what reads it). Every row is optional: an unanswered
// sport keeps the generator's default start, which is a real choice. Tapping
// the chosen level again clears it.
import { FREQUENCY_LEVELS, FREQUENCY_SPORTS } from "../../lib/frequency";

export default function StepHabits({ data, onChange, onNext, onBack }) {
  function pick(sport, level) {
    const next = { ...data };
    if (next[sport] === level) delete next[sport]; else next[sport] = level;
    onChange(next);
  }

  return (
    <div className="space-y-4">
      <p className="text-xs text-slate-400 dark:text-slate-500">
        Sets where your first plan for each sport starts, until Tracks has your own history.
        Strength sets how hard strength sessions begin. Skip any you don't do.
      </p>
      {FREQUENCY_SPORTS.map(({ value: sport, label }) => (
        <div key={sport}>
          <p className="text-sm font-medium text-slate-700 dark:text-slate-300 mb-1">{label}</p>
          <div className="flex flex-wrap gap-1.5">
            {FREQUENCY_LEVELS.map(({ value, label: levelLabel }) => (
              <button key={value} type="button" className="chip"
                aria-pressed={data[sport] === value}
                onClick={() => pick(sport, value)}>
                {levelLabel}
              </button>
            ))}
          </div>
        </div>
      ))}
      <div className="flex gap-3 pt-1">
        <button type="button" onClick={onBack} className="btn btn-neutral flex-1">Back</button>
        <button type="button" onClick={onNext} className="btn btn-primary flex-1">Next</button>
      </div>
    </div>
  );
}
