// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Shared UI primitives + Tailwind class constants used across the Goals page
// (form controls, section panels, pills). Centralised so styling stays
// consistent between the new-goal form and the goal cards.

// Reusable input/label class strings.
export const INPUT = "w-full rounded-lg border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-900 dark:text-white px-2.5 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-accent-500 placeholder-slate-400 disabled:opacity-50";
export const LABEL = "block text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider mb-1.5";

// Card shell with a titled header and optional right-aligned action.
export function Section({ title, action, children }) {
  return (
    <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 overflow-hidden">
      <div className="px-4 py-2.5 border-b border-slate-100 dark:border-slate-800 flex items-center justify-between">
        <h2 className="text-sm font-semibold text-slate-700 dark:text-slate-300 uppercase tracking-wide">{title}</h2>
        {action}
      </div>
      <div className="p-4">{children}</div>
    </div>
  );
}

// Small coloured status/label chip.
export function Pill({ children, tone = "slate" }) {
  const tones = {
    slate:   "bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-300",
    accent: "bg-accent-100 dark:bg-accent-900/30 text-accent-700 dark:text-accent-400",
    blue:    "bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-400",
    amber:   "bg-amber-100 dark:bg-amber-900/30 text-amber-700 dark:text-amber-400",
    red:     "bg-red-100 dark:bg-red-900/30 text-red-700 dark:text-red-400",
  };
  return <span className={`text-xs font-medium rounded-full px-1.5 py-0.5 ${tones[tone]}`}>{children}</span>;
}
