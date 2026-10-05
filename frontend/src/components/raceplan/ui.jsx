// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Small self-contained presentational primitives for the Race Plan page: the
// titled card shell, the pill-style radio group, and the watch-sync status
// badge. None of these touch page state — they take everything via props.

// Titled card shell wrapping a block of plan content.
export function Section({ title, children }) {
  return (
    <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 p-4 space-y-4">
      {title && <h2 className="text-sm font-semibold text-slate-700 dark:text-slate-300 uppercase tracking-wide">{title}</h2>}
      {children}
    </div>
  );
}

// Pill-style radio group. `options` are { value, label, desc? }.
export function RadioGroup({ options, value, onChange, name }) {
  return (
    <div className="flex flex-wrap gap-2">
      {options.map(opt => (
        <label key={opt.value} title={opt.desc}
          className={`flex items-center gap-2 px-2.5 py-1.5 rounded-lg border text-sm cursor-pointer transition-colors ${
            value === opt.value
              ? "border-violet-500 bg-violet-50 dark:bg-violet-900/20 text-violet-700 dark:text-violet-300"
              : "border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-400 hover:border-slate-300"
          }`}
        >
          <input type="radio" name={name} value={opt.value} checked={value === opt.value}
            onChange={() => onChange(opt.value)} className="sr-only" />
          {opt.label}
        </label>
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
