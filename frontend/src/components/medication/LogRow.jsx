// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// One entry in the medication history list: medication name, a short date/time
// label, and a colour-coded status (taken / skipped / as-needed). Purely
// presentational — the parent resolves `medName` from the log entry's med id.

import { clsx } from "./constants";

export default function LogRow({ entry, medName }) {
  const dt = new Date(entry.logged_at);
  const label = dt.toLocaleDateString(undefined, { month: "short", day: "numeric" }) + " " +
                dt.toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" });
  return (
    <div className="flex items-center justify-between py-1 border-b border-slate-100 dark:border-slate-800 last:border-0 text-xs">
      <div>
        <span className="font-medium text-slate-700 dark:text-slate-300">{medName}</span>
        <span className="text-slate-400 ml-2">{label}</span>
      </div>
      <span className={clsx(
        "font-medium",
        entry.status === "taken"    && "text-accent-600 dark:text-accent-400",
        entry.status === "skipped"  && "text-slate-400",
        entry.status === "as_needed" && "text-blue-500",
      )}>
        {entry.status === "taken" ? "✓ Taken" : entry.status === "skipped" ? "Skipped" : "As needed"}
      </span>
    </div>
  );
}
