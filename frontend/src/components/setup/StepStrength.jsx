// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Setup step — Strength training. Two optional, skippable sub-questions:
// what equipment is available (drives which exercises can appear in
// generated strength plans) and self-declared lifting experience (drives
// starting difficulty/volume/progression). Both are editable later —
// equipment any time in Settings, experience via the adaptive "Coach note"
// suggestion once enough session history exists (see
// backend/app/calculators/strength_plan/leveling.py). Reads/writes
// `equipment_available`/`strength_experience` on the shared `strength`
// draft slice.
import { EQUIPMENT_OPTIONS } from "../../lib/equipment";
import { EXPERIENCE_OPTIONS } from "../../lib/experienceLevels";

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
                className={`flex items-center justify-between gap-2 px-2.5 py-1.5 rounded-lg border text-sm font-medium transition-colors ${
                  checked
                    ? "bg-accent-500 border-accent-500 text-white"
                    : "border-slate-200 dark:border-slate-700 hover:border-accent-400 dark:hover:border-accent-500 text-slate-700 dark:text-slate-200"
                } ${required ? "cursor-default opacity-90" : ""}`}
              >
                <span>{label}</span>
                {checked && (
                  <svg className="w-3.5 h-3.5 shrink-0" fill="none" stroke="currentColor" strokeWidth={3} viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                  </svg>
                )}
              </button>
            );
          })}
        </div>
      </div>

      <div>
        <p className="text-sm font-medium text-slate-700 dark:text-slate-300 mb-0.5">Strength training experience</p>
        <p className="text-xs text-slate-400 dark:text-slate-500 mb-2">Optional — sets the starting difficulty and volume of generated strength plans. Skip if you're not sure yet.</p>
        <div className="grid grid-cols-2 gap-1.5">
          {EXPERIENCE_OPTIONS.map(({ value, label, blurb }) => (
            <button
              key={value}
              type="button"
              onClick={() => onChange("strength_experience", data.strength_experience === value ? null : value)}
              className={`text-left px-2.5 py-1.5 rounded-lg border transition-colors ${
                data.strength_experience === value
                  ? "bg-accent-500 border-accent-500 text-white"
                  : "border-slate-200 dark:border-slate-700 hover:border-accent-400 dark:hover:border-accent-500 text-slate-700 dark:text-slate-200"
              }`}
            >
              <div className="text-sm font-medium">{label}</div>
              <div className={`text-[11px] leading-snug mt-0.5 ${data.strength_experience === value ? "text-white/80" : "text-slate-400 dark:text-slate-500"}`}>{blurb}</div>
            </button>
          ))}
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
