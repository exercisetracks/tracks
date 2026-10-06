// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Swimming race plan (simple view — no lap table). Shows the predicted finish
// time and a single target pace derived from Critical Swim Speed (CSS), or a
// prompt to set CSS when it's missing. Fully driven by the `plan` prop.

export default function SwimmingSection({ plan }) {
  const cssStr = plan.css_sec_per_100m
    ? (() => {
        const s = plan.css_sec_per_100m;
        return `${Math.floor(s / 60)}:${String(Math.round(s % 60)).padStart(2, "0")} / 100m`;
      })()
    : null;

  return (
    <div className="space-y-4">
      {plan.predicted_time && (
        <div>
          <div className="text-xs text-slate-400 dark:text-slate-500 mb-1">Predicted finish time</div>
          <div className="text-3xl font-bold font-mono text-violet-600 dark:text-violet-400">
            {plan.predicted_time}
          </div>
        </div>
      )}
      {plan.swim_target_pace && (
        <div className="rounded-lg bg-slate-50 dark:bg-slate-800/60 px-3.5 py-2.5">
          <p className="section-title mb-1">
            Target pace
          </p>
          <p className="text-xl font-bold font-mono text-slate-800 dark:text-slate-200">
            {plan.swim_target_pace}
          </p>
          {cssStr && (
            <p className="text-xs text-slate-400 dark:text-slate-500 mt-1">
              CSS {cssStr} — sustaining this pace for the full distance
            </p>
          )}
        </div>
      )}
      {!plan.css_sec_per_100m && (
        <p className="text-sm text-amber-700 dark:text-amber-400 bg-amber-50 dark:bg-amber-900/20 rounded-lg px-2.5 py-1.5">
          Set your Critical Swim Speed (CSS) in Settings to generate a swim plan.
        </p>
      )}
    </div>
  );
}
