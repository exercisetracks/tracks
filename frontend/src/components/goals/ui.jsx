// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Class names and the status badge the Goals page shares between the new-goal
// form and the goal cards. The section shell is the app's (ui/Section).

// Inputs and labels are the kit's .field / .field-label (src/design/kit.js).
export const INPUT = "field";
export const LABEL = "field-label";

// Small coloured status badge — spec/design.yaml `pill`, as the phone's Pill.
export function Pill({ children, tone = "slate" }) {
  const tones = {
    slate:  "bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-300",
    accent: "bg-accent-100 text-accent-700",
    blue:   "bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-400",
    amber:  "bg-amber-100 dark:bg-amber-900/30 text-amber-700 dark:text-amber-400",
    red:    "bg-red-100 dark:bg-red-900/30 text-red-700 dark:text-red-400",
  };
  return <span className={`badge ${tones[tone]}`}>{children}</span>;
}
