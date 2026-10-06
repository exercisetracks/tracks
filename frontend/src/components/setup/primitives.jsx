// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Shared building blocks for the first-run Setup wizard.
//
// These are tiny presentational primitives + the shared input class strings and
// the ordered step list. They have NO coupling to any single step's state, so
// every step file imports from here to keep the wizard's chrome (step dots,
// field rows, segmented toggles) identical throughout.

import Switch from "../ui/Switch";
import Tabs from "../ui/Tabs";

// Ordered wizard steps — drives the StepIndicator dots and labels.
export const STEPS = ["Account", "Body", "Zones", "Strength", "GPS", "Data", "Look", "AI"];

// Text inputs and selects are the kit's .field (src/design/kit.js).
export const INPUT = "field";
export const SELECT = "field";

// Progress dots across the top of the wizard. `current` is the active step index.
export function StepIndicator({ current }) {
  return (
    <div className="flex items-center justify-center gap-0 mb-4 overflow-x-auto">
      {STEPS.map((label, i) => (
        <div key={label} className="flex items-center shrink-0">
          <div className="flex flex-col items-center gap-0.5">
            <div className={`w-5 h-5 rounded-full flex items-center justify-center text-[10px] font-bold transition-colors ${
              i < current  ? "bg-accent-600 text-white"
              : i === current ? "bg-accent-600 text-white ring-2 ring-accent-200 dark:ring-accent-800"
              : "bg-slate-200 dark:bg-slate-700 text-slate-500 dark:text-slate-400"
            }`}>
              {i < current ? (
                <svg className="w-2.5 h-2.5" fill="none" stroke="currentColor" strokeWidth={3} viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                </svg>
              ) : i + 1}
            </div>
            <span className={`text-[9px] font-medium ${i === current ? "text-accent-600 dark:text-accent-400" : "text-slate-400 dark:text-slate-500"}`}>
              {label}
            </span>
          </div>
          {i < STEPS.length - 1 && (
            <div className={`w-5 h-0.5 mb-3 mx-0.5 transition-colors ${i < current ? "bg-accent-600" : "bg-slate-200 dark:bg-slate-700"}`} />
          )}
        </div>
      ))}
    </div>
  );
}

// Self-contained feature-toggle card: icon + title + on/off switch share one
// header row, with the description and optional ± bullet points underneath —
// replaces the old pattern of a description card followed by a disconnected
// toggle row below it. `points` is an array of ["+"|"-", text] tuples.
export function ToggleCard({ icon, title, description, hint, checked, onChange, points }) {
  return (
    <div className={`rounded-lg border p-3 transition-colors ${
      checked
        ? "border-accent-200 dark:border-accent-800 bg-accent-50/50 dark:bg-accent-900/10"
        : "border-slate-200 dark:border-slate-700"
    }`}>
      <div className="flex items-start gap-2.5">
        <div className="shrink-0 mt-0.5 text-accent-500">{icon}</div>
        <div className="min-w-0 flex-1">
          <div className="flex items-center justify-between gap-2">
            <p className="text-sm font-medium text-slate-800 dark:text-slate-200">{title}</p>
            <Switch checked={checked} onChange={onChange} label={title} />
          </div>
          <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5 leading-snug">{description}</p>
          {points && (
            <div className="mt-1.5 space-y-1 text-xs">
              {points.map(([sign, text], i) => (
                <div key={i} className="flex items-start gap-1.5">
                  <span className={`font-bold shrink-0 ${sign === "+" ? "text-accent-500" : "text-amber-500"}`}>{sign}</span>
                  <span className="text-slate-600 dark:text-slate-400">{text}</span>
                </div>
              ))}
            </div>
          )}
          {hint && <p className="text-[11px] text-slate-400 dark:text-slate-500 mt-1.5">{hint}</p>}
        </div>
      </div>
    </div>
  );
}

// Labelled field row with an optional dimmed hint after the label.
export function FieldRow({ label, hint, children }) {
  return (
    <div>
      <label className="field-label">
        {label}
        {hint && <span className="ml-1 text-slate-400 font-normal text-xs">{hint}</span>}
      </label>
      {children}
    </div>
  );
}

// Two-way Auto-detect/Manual choice (the Zones step) — the shared segmented
// Tabs, as Settings uses for the same choice.
export function ModeToggle({ value, onChange }) {
  return (
    <div className="w-fit">
      <Tabs size="sm" value={value} onChange={onChange}
        tabs={[{ key: "auto", label: "Auto-detect" }, { key: "manual", label: "Manual" }]} />
    </div>
  );
}
