// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The segmented control (.segmented in src/design/kit.js): in-page views
// (Exercises / Workouts), and any one-of-a-few setting (Units, Colour scheme,
// Auto / Manual). Supports single-select and multi-select (chip toggles,
// optionally with a leading colour dot) against the same look. Selected is
// the accent container, as on the phone. Settings had grown a second design
// for the same control — a bordered strip with a filled accent cell — in
// six places; they are this now.
export default function Tabs({ tabs, value, onChange, multi = false, stretch = false, size = "md", className = "", dataTour, label }) {
  const isActive = key => (multi ? value.includes(key) : value === key);

  return (
    <div
      role="group"
      aria-label={label}
      data-tour={dataTour}
      className={`segmented ${size === "sm" ? "segmented-sm" : ""} ${stretch ? "" : "flex-wrap w-fit"} ${className}`}
    >
      {tabs.map(t => {
        const active = isActive(t.key);
        return (
          <button
            key={t.key}
            type="button"
            aria-pressed={active}
            onClick={() => onChange(t.key)}
            className={stretch ? "flex-1" : ""}
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
