// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Small self-contained presentational primitives for the Race Plan page: the
// titled card, the pick-one chip row, and the watch-sync status badge. None of
// these touch page state — they take everything via props.

import { Section as SharedSection } from "../ui/Section";

// A block of the plan: the app's section, its title above one card.
export function Section({ title, children }) {
  return (
    <SharedSection title={title}>
      <div className="card space-y-4">{children}</div>
    </SharedSection>
  );
}

// Pick-one row of options as chips. `options` are { value, label, desc? }.
export function RadioGroup({ options, value, onChange, name }) {
  return (
    <div role="radiogroup" aria-label={name} className="flex flex-wrap gap-2">
      {options.map(opt => (
        <button key={opt.value} type="button" role="radio" title={opt.desc}
          aria-checked={value === opt.value} onClick={() => onChange(opt.value)} className="chip">
          {opt.label}
        </button>
      ))}
    </div>
  );
}

// Watch-sync badge: "Synced to watch" once uploaded, otherwise "Will sync".
// Renders nothing until there are laps to sync.
export function SyncStatus({ watchUploadedAt, hasLaps }) {
  if (!hasLaps) return null;
  if (watchUploadedAt) {
    return (
      <span className="flex items-center gap-1.5 text-sm text-accent-600 dark:text-accent-400">
        <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
        </svg>
        Synced to watch
        <span className="text-slate-400 text-xs">· {new Date(watchUploadedAt).toLocaleDateString()}</span>
      </span>
    );
  }
  return (
    <span className="flex items-center gap-1.5 text-sm text-amber-600 dark:text-amber-400">
      <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
      </svg>
      Will sync on next connection
    </span>
  );
}
