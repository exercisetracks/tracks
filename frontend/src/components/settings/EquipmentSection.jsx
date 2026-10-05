// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Strength equipment section: checkbox list of equipment the user has access to;
// drives which exercises appear in generated strength plans. Bodyweight is always
// required and can't be unchecked.
//
// Note: this section manages its save status inline (shorter timers) rather than
// via the shared useSaveStatus hook — kept as-is to preserve exact behavior.
import { useEffect, useState } from "react";
import { api } from "../../api/client";
import { EQUIPMENT_OPTIONS } from "../../lib/equipment";
import { Section } from "./primitives";

export default function EquipmentSection({ settings, onSaved }) {
  const initial = settings?.equipment_available ?? ["bodyweight", "dumbbell"];
  const [selected, setSelected] = useState(initial);
  const [status,   setStatus]   = useState(null); // null | "saving" | "saved" | "error"

  useEffect(() => {
    setSelected(settings?.equipment_available ?? ["bodyweight", "dumbbell"]);
  }, [settings?.equipment_available]);

  async function toggle(value) {
    if (value === "bodyweight") return; // always required
    const next = selected.includes(value)
      ? selected.filter(v => v !== value)
      : [...selected, value];
    setSelected(next);
    setStatus("saving");
    try {
      await api.updateSettings({ equipment_available: next });
      await onSaved();
      setStatus("saved");
      setTimeout(() => setStatus(null), 1500);
    } catch {
      setStatus("error");
      setTimeout(() => setStatus(null), 3000);
    }
  }

  return (
    <Section title="Strength Equipment" status={status}>
      <p className="text-xs text-slate-500 dark:text-slate-400 mb-3">
        Select all equipment you have access to. This determines which exercises
        appear in your generated strength plans.
      </p>
      <div className="grid sm:grid-cols-2 gap-2">
        {EQUIPMENT_OPTIONS.map(({ value, label, description }) => {
          const checked  = selected.includes(value);
          const required = value === "bodyweight";
          return (
            <button
              key={value}
              type="button"
              disabled={required}
              onClick={() => toggle(value)}
              className={`flex items-center justify-between gap-2 text-left p-2 rounded-lg border transition-colors ${
                checked
                  ? "bg-accent-500 border-accent-500 text-white"
                  : "border-slate-200 dark:border-slate-700 hover:border-accent-400 dark:hover:border-accent-500"
              } ${required ? "cursor-default opacity-90" : ""}`}
            >
              <div className="min-w-0">
                <p className="text-sm font-medium">{label}</p>
                <p className={`text-xs ${checked ? "text-white/80" : "text-slate-400 dark:text-slate-500"}`}>{description}</p>
              </div>
              {checked && (
                <svg className="w-4 h-4 shrink-0" fill="none" stroke="currentColor" strokeWidth={3} viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                </svg>
              )}
            </button>
          );
        })}
      </div>
    </Section>
  );
}
