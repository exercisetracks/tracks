// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import React from "react";

/*
 * The two questions the watch asks when a workout is saved, read back in the
 * watch's own words. The FIT session stores them scaled — feel 0–100 in
 * quarters, effort ×10 — so the raw numbers ("75", "60") would read as
 * nothing a person answered. The phone's copy is feelLabel/effortLabel in
 * mobile/app/.../ui/activity/Cards.kt.
 */

const FEEL = ["Very weak", "Weak", "Normal", "Strong", "Very strong"];

export function feelLabel(feel) {
  if (feel == null || feel < 0 || feel > 100) return null;
  return FEEL[Math.floor((feel + 12) / 25)];
}

// Zero is the watch's "skipped", not "no effort".
export function effortLabel(rpe) {
  if (rpe == null || rpe < 1 || rpe > 100) return null;
  const score = Math.max(1, Math.floor((rpe + 5) / 10));
  const band = score <= 2 ? "Very light"
    : score <= 4 ? "Light"
    : score <= 6 ? "Moderate"
    : score <= 8 ? "Hard"
    : score === 9 ? "Very hard"
    : "Maximum";
  return { score: `${score}/10`, band };
}

export function HowItFelt({ activity }) {
  const feel = feelLabel(activity?.workout_feel);
  const effort = effortLabel(activity?.workout_rpe);
  if (!feel && !effort) return null;

  return (
    <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 p-3.5 mt-3.5">
      <h3 className="text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider mb-3">
        How it felt
      </h3>
      <div className="flex flex-wrap gap-8">
        {feel && (
          <div>
            <div className="text-lg font-semibold text-slate-900 dark:text-white">{feel}</div>
            <div className="text-xs text-slate-500 dark:text-slate-400 uppercase tracking-wider">Feel</div>
          </div>
        )}
        {effort && (
          <div>
            <div className="text-lg font-semibold text-slate-900 dark:text-white tabular-nums">
              {effort.score} <span className="text-sm font-normal text-slate-500 dark:text-slate-400">{effort.band}</span>
            </div>
            <div className="text-xs text-slate-500 dark:text-slate-400 uppercase tracking-wider">Effort</div>
          </div>
        )}
      </div>
    </div>
  );
}
