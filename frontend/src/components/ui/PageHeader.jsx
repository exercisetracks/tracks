// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The top of a page: its title, an optional line under it, and whatever
// controls govern the whole page (BarPills, a primary action) on the right.
// Pages had drifted to three title sizes and two weights.
export default function PageHeader({ title, subtitle, children, dataTour }) {
  return (
    <div data-tour={dataTour} className="flex items-center justify-between gap-3 flex-wrap">
      <div className="min-w-0">
        <h1 className="text-xl font-bold text-slate-900 dark:text-white truncate">{title}</h1>
        {subtitle && <p className="mt-0.5 text-sm text-slate-500 dark:text-slate-400">{subtitle}</p>}
      </div>
      {children && <div className="flex items-center gap-2 flex-wrap">{children}</div>}
    </div>
  );
}
