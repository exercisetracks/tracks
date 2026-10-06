// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// A checkbox with its label. The box itself is styled globally (index.css),
// so a bare <input type="checkbox"> is already right; this only saves the
// label row, which pages had written six different ways.
export default function Checkbox({ checked, onChange, children, hint, disabled = false, className = "" }) {
  return (
    <label className={`flex items-start gap-2.5 cursor-pointer select-none ${disabled ? "opacity-50 cursor-not-allowed" : ""} ${className}`}>
      <input
        type="checkbox"
        className="mt-0.5"
        checked={!!checked}
        disabled={disabled}
        onChange={e => onChange(e.target.checked)}
      />
      <span className="min-w-0">
        <span className="block text-sm text-slate-700 dark:text-slate-200">{children}</span>
        {hint && <span className="block text-xs text-slate-500 dark:text-slate-400 mt-0.5">{hint}</span>}
      </span>
    </label>
  );
}
