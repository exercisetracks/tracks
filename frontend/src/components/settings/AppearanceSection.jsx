// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Appearance section: color scheme (system/light/dark) + accent color.
// State lives in ThemeContext, so this section takes no props.
import { useTheme } from "../../context/ThemeContext";
import ColorPicker from "../ColorPicker";
import { Section } from "./primitives";

const THEME_OPTIONS = [
  ["system", "System", "Follows your device settings"],
  ["light", "Light", "Always light"],
  ["dark", "Dark", "Always dark"],
];

const ACCENT_OPTIONS = [
  ["emerald", "Emerald", "#10b981"],
  ["blue", "Blue", "#3b82f6"],
  ["violet", "Violet", "#8b5cf6"],
  ["rose", "Rose", "#f43f5e"],
  ["amber", "Amber", "#f59e0b"],
  ["sky", "Sky", "#0ea5e9"],
  ["teal", "Teal", "#14b8a6"],
];

export default function AppearanceSection() {
  const { colorScheme, setColorScheme, accent, setAccent } = useTheme();
  const isCustomAccent = /^#[0-9a-fA-F]{6}$/.test(accent);

  return (
    <Section title="Appearance">
      <div>
        <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-2">
          Color scheme
        </label>
        <div className="flex rounded-lg overflow-hidden border border-slate-300 dark:border-slate-700 text-sm w-fit">
          {THEME_OPTIONS.map(([v, label]) => (
            <button
              key={v}
              type="button"
              onClick={() => setColorScheme(v)}
              className={`px-2.5 py-1.5 font-medium transition-colors ${
                colorScheme === v
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
        <div className="flex flex-wrap items-center gap-2">
          {ACCENT_OPTIONS.map(([key, name, swatch]) => (
            <button
              key={key}
              type="button"
              onClick={() => setAccent(key)}
              title={name}
              className={`w-8 h-8 rounded-full transition-all ${
                accent === key
                  ? "ring-2 ring-offset-2 ring-accent-500 ring-offset-white dark:ring-offset-slate-900 scale-110"
                  : "hover:scale-105"
              }`}
              style={{ backgroundColor: swatch }}
            />
          ))}
          {/* Custom colour: show the active custom swatch (when set) + the picker. */}
          {isCustomAccent && (
            <span className="w-8 h-8 rounded-full ring-2 ring-offset-2 ring-accent-500 ring-offset-white dark:ring-offset-slate-900"
                  style={{ backgroundColor: accent }} title="Custom color" />
          )}
          <ColorPicker value={isCustomAccent ? accent : "#10b981"} onChange={setAccent}
                       size="w-8 h-8" placement="bottom" title="Custom accent color" />
        </div>
      </div>
    </Section>
  );
}
