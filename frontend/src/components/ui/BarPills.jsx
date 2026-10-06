// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The selector that sits beside a page title: the Dashboard period, the
// Health range, the Activities sort. The phone's BarPill (Components.kt) —
// small pills in the header rather than a dropdown or a segmented track,
// because the window governs everything below it and belongs where it is
// visible without opening anything. Shared so the pages cannot drift into
// looking like different mechanisms, which is what had happened: a <select>
// on one page, a full-width Tabs track on the next.
//
// `options` are { value, label }.
export default function BarPills({ options, value, onChange, label, className = "", dataTour }) {
  return (
    <div role="group" aria-label={label} data-tour={dataTour} className={`flex flex-wrap items-center gap-1 ${className}`}>
      {options.map(o => (
        <button
          key={o.value}
          type="button"
          aria-pressed={o.value === value}
          onClick={() => onChange(o.value)}
          className="bar-pill"
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}
