// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Shared pill-track tab/segmented-control. Supports single-select (tabs,
// range pickers) and multi-select (chip toggles, optionally with a leading
// color dot) against the same visual language: a rounded gray track holding
// rounded white "active" pills.
export default function Tabs({ tabs, value, onChange, multi = false, stretch = false, size = "md", className = "", dataTour }) {
  const isActive = key => (multi ? value.includes(key) : value === key);
  const pad = size === "sm" ? "px-2.5 py-1.5 text-xs" : "px-2.5 py-1.5 text-sm";

  return (
    <div
      data-tour={dataTour}
      className={`flex ${stretch ? "" : "flex-wrap"} gap-1 p-1 rounded-xl bg-slate-100 dark:bg-slate-800 ${className}`}
    >
      {tabs.map(t => {
        const active = isActive(t.key);
        return (
          <button
            key={t.key}
            type="button"
            onClick={() => onChange(t.key)}
            className={`${stretch ? "flex-1" : ""} flex items-center justify-center gap-1.5 ${pad} font-medium rounded-lg transition-colors ${
              active
                ? "bg-white dark:bg-slate-700 text-slate-900 dark:text-white shadow-sm"
                : "text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200"
            }`}
          >
            {t.dot && (
              <span
                className="w-2 h-2 rounded-full shrink-0"
                style={{ background: active ? t.dot : "#cbd5e1" }}
              />
            )}
            {t.label}
          </button>
        );
      })}
    </div>
  );
}
