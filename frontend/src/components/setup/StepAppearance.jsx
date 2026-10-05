// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Setup step 6 — Appearance. Color scheme (system/light/dark) and accent color
// (preset swatches plus a custom ColorPicker). Writes to the `look` draft slice
// AND applies the choice live via ThemeContext so the wizard re-themes instantly.
import { useTheme } from "../../context/ThemeContext";
import ColorPicker from "../ColorPicker";

const ACCENT_OPTIONS = [
  ["emerald", "Emerald", "#10b981"],
  ["blue", "Blue", "#3b82f6"],
  ["violet", "Violet", "#8b5cf6"],
  ["rose", "Rose", "#f43f5e"],
  ["amber", "Amber", "#f59e0b"],
  ["sky", "Sky", "#0ea5e9"],
  ["teal", "Teal", "#14b8a6"],
];

export default function StepAppearance({ data, onChange, onNext, onBack }) {
  const { setColorScheme, setAccent } = useTheme();
  const isCustomAccent = /^#[0-9a-fA-F]{6}$/.test(data.accent || "");

  function handleColorScheme(v) {
    onChange("colorScheme", v);
    setColorScheme(v);
  }

  function handleAccent(v) {
    onChange("accent", v);
    setAccent(v);
  }

  return (
    <div className="space-y-4">
      <div>
        <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
          Color scheme
        </label>
        <div className="flex rounded-lg overflow-hidden border border-slate-300 dark:border-slate-700 text-sm w-fit">
          {[["system", "System"], ["light", "Light"], ["dark", "Dark"]].map(([v, label]) => (
            <button
              key={v}
              type="button"
              onClick={() => handleColorScheme(v)}
              className={`px-3.5 py-1.5 font-medium transition-colors ${
                data.colorScheme === v
                  ? "bg-accent-600 text-white"
                  : "bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-700"
              }`}
            >
              {label}
            </button>
          ))}
        </div>
      </div>

      <div>
        <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-2">
          Accent color
        </label>
        <div className="flex flex-wrap items-center gap-2.5">
          {ACCENT_OPTIONS.map(([key, name, swatch]) => (
            <button
              key={key}
              type="button"
              onClick={() => handleAccent(key)}
              title={name}
              className={`w-9 h-9 rounded-full transition-all ${
                data.accent === key
                  ? "ring-2 ring-offset-2 ring-accent-500 ring-offset-white dark:ring-offset-slate-900 scale-110"
                  : "hover:scale-105"
              }`}
              style={{ backgroundColor: swatch }}
            />
          ))}
          {isCustomAccent && (
            <span className="w-9 h-9 rounded-full ring-2 ring-offset-2 ring-accent-500 ring-offset-white dark:ring-offset-slate-900"
                  style={{ backgroundColor: data.accent }} title="Custom color" />
          )}
          <ColorPicker value={isCustomAccent ? data.accent : "#10b981"} onChange={handleAccent}
                       size="w-9 h-9" placement="bottom" title="Custom accent color" />
        </div>
      </div>

      <div className="flex gap-3 pt-1">
        <button type="button" onClick={onBack}
          className="btn btn-neutral flex-1">
          Back
        </button>
        <button type="button" onClick={onNext}
          className="btn btn-primary flex-1">
          Continue
        </button>
      </div>
    </div>
  );
}
