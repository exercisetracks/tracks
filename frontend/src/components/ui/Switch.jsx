// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The app's on/off switch — the phone's TracksSwitch, drawn by .switch in
// src/design/kit.js. Use it for a setting that takes effect immediately; a
// choice that is only submitted with a form is a <Checkbox>.
export default function Switch({ checked, onChange, label, disabled = false, small = false, className = "" }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={!!checked}
      aria-label={label}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      className={`switch ${small ? "switch-sm" : ""} ${className}`}
    >
      <span />
    </button>
  );
}

/** A switch with its label, as a settings row: text on the left, switch on the right. */
export function SwitchRow({ checked, onChange, label, hint, disabled = false }) {
  return (
    <div className="flex items-center justify-between gap-3">
      <div className="min-w-0">
        <p className="text-sm font-medium text-slate-800 dark:text-slate-200">{label}</p>
        {hint && <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">{hint}</p>}
      </div>
      <Switch checked={checked} onChange={onChange} label={label} disabled={disabled} />
    </div>
  );
}
