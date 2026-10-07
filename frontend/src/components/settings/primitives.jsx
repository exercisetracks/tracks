// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Shared building blocks for the Settings page sections.
//
// These are presentational primitives + one small hook with NO coupling to any
// particular section's state — every Settings section imports from here so the
// "card with save status", field layout, and zone table look identical
// everywhere. Kept in one file because they are tiny and always used together.
import InfoTooltip from "../ui/InfoTooltip";
import { useEffect, useRef, useState } from "react";
import { Section as SharedSection } from "../ui/Section";
import Tabs from "../ui/Tabs";

// Text inputs and selects are the kit's .field (src/design/kit.js); these
// names stay so the sections read as before.
export const INPUT = "field";
export const SELECT = "field";

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

// A settings group: title above the card with the save status at its right,
// as the phone's SettingsCard — the shared Section, with the settings card's
// own spacing between rows.
// `info` is the section's explanation, behind a "?" beside the title rather
// than a paragraph at the top of the card: Settings had grown a line of help
// text over nearly every card, and read as a manual instead of a form.
export function Section({ title, status, children, dataTour, info }) {
  const heading = info
    ? <span className="inline-flex items-center gap-1.5">{title}<InfoTooltip label={`About ${typeof title === "string" ? title : "this"}`}>{info}</InfoTooltip></span>
    : title;
  return (
    <SharedSection title={heading} action={<SaveStatusText status={status} />} dataTour={dataTour}>
      <div className="card space-y-4">{children}</div>
    </SharedSection>
  );
}

// Labelled field row with an optional dimmed hint after the label.
export function FieldRow({ label, hint, children }) {
  return (
    <div>
      <label className="field-label">
        {label}
        {hint && <span className="ml-1.5 text-slate-400 font-normal text-xs">{hint}</span>}
      </label>
      {children}
    </div>
  );
}

// Two-way Auto/Manual choice — the shared segmented Tabs, small.
export function ModeToggle({ value, onChange, disabled, labels = { auto: "Auto", manual: "Manual" } }) {
  return (
    <div className={`w-fit ${disabled ? "opacity-50 pointer-events-none" : ""}`}>
      <Tabs size="sm" value={value} onChange={onChange}
        tabs={[{ key: "auto", label: labels.auto }, { key: "manual", label: labels.manual }]} />
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
