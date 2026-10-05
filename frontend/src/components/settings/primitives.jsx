// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Shared building blocks for the Settings page sections.
//
// These are presentational primitives + one small hook with NO coupling to any
// particular section's state — every Settings section imports from here so the
// "card with save status", field layout, and zone table look identical
// everywhere. Kept in one file because they are tiny and always used together.
import { useEffect, useRef, useState } from "react";

// Shared Tailwind class strings for text inputs / selects.
export const INPUT = "w-full rounded-lg border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-900 dark:text-white px-2.5 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-accent-500 placeholder-slate-400 disabled:opacity-50";
export const SELECT = INPUT + " cursor-pointer";

// Transient "Saving… / Saved ✓ / Error" status with auto-clearing timers.
// A section calls startSave() before an API write, then markSaved()/markError().
export function useSaveStatus() {
  const [status, setStatus] = useState(null); // null | "saving" | "saved" | "error"
  const timerRef = useRef(null);
  useEffect(() => () => clearTimeout(timerRef.current), []);

  function startSave() {
    clearTimeout(timerRef.current);
    setStatus("saving");
  }
  function markSaved() {
    setStatus("saved");
    timerRef.current = setTimeout(() => setStatus(null), 2000);
  }
  function markError() {
    setStatus("error");
    timerRef.current = setTimeout(() => setStatus(null), 3000);
  }
  return { status, startSave, markSaved, markError };
}

// Inline save-status text for UI that isn't wrapped in a Section card
// (e.g. the expandable config panels inside Privacy & Connectivity).
export function SaveStatusText({ status }) {
  return (
    <span className={`text-xs transition-opacity duration-300 ${status ? "opacity-100" : "opacity-0"} ${
      status === "saved"  ? "text-accent-500" :
      status === "saving" ? "text-slate-400"   : "text-red-500"
    }`}>
      {status === "saving" ? "Saving…" : status === "saved" ? "Saved ✓" : "Error saving"}
    </span>
  );
}

// Card wrapper with a title and an inline save-status indicator (top right).
export function Section({ title, status, children }) {
  return (
    <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 overflow-hidden">
      <div className="px-4 py-2.5 border-b border-slate-100 dark:border-slate-800 flex items-center justify-between">
        <h2 className="text-sm font-semibold text-slate-700 dark:text-slate-300 uppercase tracking-wide">{title}</h2>
        <SaveStatusText status={status} />
      </div>
      <div className="p-4 space-y-4">{children}</div>
    </div>
  );
}

// Labelled field row with an optional dimmed hint after the label.
export function FieldRow({ label, hint, children }) {
  return (
    <div>
      <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1">
        {label}
        {hint && <span className="ml-1.5 text-slate-400 font-normal text-xs">{hint}</span>}
      </label>
      {children}
    </div>
  );
}

// Two-button Auto/Manual segmented toggle.
export function ModeToggle({ value, onChange, disabled }) {
  return (
    <div className="flex rounded-md overflow-hidden border border-slate-300 dark:border-slate-700 text-xs w-fit">
      {[["auto", "Auto"], ["manual", "Manual"]].map(([v, l]) => (
        <button key={v} type="button"
          disabled={disabled}
          onClick={() => onChange(v)}
          className={`px-2.5 py-1 font-medium transition-colors ${
            value === v
              ? "bg-accent-600 text-white"
              : "bg-white dark:bg-slate-800 text-slate-500 dark:text-slate-400 hover:bg-slate-50 dark:hover:bg-slate-700"
          } disabled:opacity-50`}>
          {l}
        </button>
      ))}
    </div>
  );
}

// Small red helper text; renders nothing when there's no message.
export function InlineError({ msg }) {
  if (!msg) return null;
  return <p className="text-xs text-red-600 dark:text-red-400 mt-1">{msg}</p>;
}

// Renders HR/power training zones as a compact table. `unit` is "bpm" or "W".
export function ZonesTable({ zones, unit }) {
  if (!zones?.length) return null;
  return (
    <div className="mt-2 rounded-lg border border-slate-200 dark:border-slate-700 overflow-hidden text-xs">
      <table className="w-full">
        <thead>
          <tr className="bg-slate-50 dark:bg-slate-800/60 text-slate-500 dark:text-slate-400">
            <th className="text-left px-2.5 py-1.5 font-medium">Zone</th>
            <th className="text-left px-2.5 py-1.5 font-medium">Name</th>
            <th className="text-right px-2.5 py-1.5 font-medium">Range</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
          {zones.map(z => (
            <tr key={z.number} className="text-slate-700 dark:text-slate-300">
              <td className="px-2.5 py-1 font-semibold text-slate-500">Z{z.number}</td>
              <td className="px-2.5 py-1">{z.name}</td>
              <td className="px-2.5 py-1 text-right tabular-nums">
                {z.min_bpm != null
                  ? `${z.min_bpm}${z.max_bpm != null ? `–${z.max_bpm}` : "+"} ${unit}`
                  : z.min_watts != null
                    ? `${z.min_watts}${z.max_watts != null ? `–${z.max_watts}` : "+"} ${unit}`
                    : "—"
                }
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
