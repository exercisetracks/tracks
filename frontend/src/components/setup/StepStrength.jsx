// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Setup step — Strength training: what equipment is available (drives which
// exercises can appear in generated strength plans), editable any time in
// Settings. How experienced a lifter is no longer asked separately: the next
// step's "how often do you strength train" answer stands in for it
// (spec/strength.yaml experience_from_frequency), and the adaptive "Coach
// note" suggestion refines it once there is history (leveling.py).
import { EQUIPMENT_OPTIONS } from "../../lib/equipment";

export default function StepStrength({ data, onChange, onNext, onBack }) {
  const selected = data.equipment_available;

  function toggleEquipment(value) {
    if (value === "bodyweight") return; // always required
    onChange("equipment_available",
      selected.includes(value) ? selected.filter(v => v !== value) : [...selected, value]);
  }

  return (
    <div className="space-y-4">
      <div>
        <p className="text-sm font-medium text-slate-700 dark:text-slate-300 mb-0.5">Equipment you have access to</p>
        <p className="text-xs text-slate-400 dark:text-slate-500 mb-2">Determines which exercises appear in generated strength plans. Change any time in Settings.</p>
        <div className="grid grid-cols-2 gap-1.5">
          {EQUIPMENT_OPTIONS.map(({ value, label }) => {
            const checked  = selected.includes(value);
            const required = value === "bodyweight";
            return (
              <button
                key={value}
                type="button"
                disabled={required}
                onClick={() => toggleEquipment(value)}
                aria-pressed={checked}
                className={`choice flex items-center justify-between gap-2 text-sm font-medium text-slate-800 dark:text-slate-100 ${required ? "cursor-default opacity-90" : ""}`}
              >
                <span>{label}</span>
                {checked && (
                  <svg className="w-3.5 h-3.5 shrink-0 text-accent-600" fill="none" stroke="currentColor" strokeWidth={3} viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                  </svg>
                )}
              </button>
            );
          })}
        </div>
      </div>

      <div className="flex gap-3 pt-1">
        <button type="button" onClick={onBack}
          className="btn btn-neutral flex-1">
          Back
        </button>
        <button type="button" onClick={onNext}
          className="btn btn-primary flex-1">
          Next
        </button>
      </div>
    </div>
  );
}
