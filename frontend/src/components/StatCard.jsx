// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
export default function StatCard({ label, value, unit, sub }) {
  return (
    <div className="card">
      <p className="text-xs font-medium text-slate-500 dark:text-slate-400 uppercase tracking-wide">{label}</p>
      <div className="mt-1 flex items-baseline gap-1">
        <span className="text-2xl font-bold text-slate-900 dark:text-white">
          {value ?? "—"}
        </span>
        {unit && <span className="text-sm text-slate-500 dark:text-slate-400">{unit}</span>}
      </div>
      {sub && <p className="mt-0.5 text-xs text-slate-400 dark:text-slate-500">{sub}</p>}
    </div>
  );
}
