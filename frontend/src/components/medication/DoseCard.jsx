// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// A single due-dose card for the "Today" tab. Shows the medication name, dose,
// scheduled time and an overdue badge; renders Taken/Skip actions until logged,
// then collapses to a status label. Stateless — the parent supplies the med and
// the onTake / onSkip handlers plus the `logging` in-flight flag.

import { BTN_GHOST } from "./constants";

export default function DoseCard({ med, onTake, onSkip, logging }) {
  const statusColors = {
    taken:   "bg-accent-50 dark:bg-accent-950 border-accent-200 dark:border-accent-800",
    skipped: "bg-slate-50 dark:bg-slate-800 border-slate-200 dark:border-slate-700",
  };
  const cardCls = med.status ? statusColors[med.status] : "bg-white dark:bg-slate-900 border-slate-200 dark:border-slate-800";

  return (
    <div className={`rounded-xl border p-2.5 flex items-center justify-between gap-3 transition-colors ${cardCls}`}>
      <div className="min-w-0">
        <div className="flex items-center gap-2 flex-wrap">
          <p className="font-medium text-sm text-slate-800 dark:text-slate-100">{med.medication_name}</p>
          {med.dose && (
            <span className="text-xs text-slate-400">{med.dose} {med.dose_unit ?? ""}</span>
          )}
          {med.is_overdue && !med.status && (
            <span className="text-[10px] font-semibold rounded-full px-1.5 py-0.5 bg-amber-100 text-amber-700 dark:bg-amber-900 dark:text-amber-300">
              Overdue
            </span>
          )}
        </div>
        <p className="text-xs text-slate-400 mt-0.5">{med.time_of_day}</p>
      </div>
      <div className="flex gap-1.5 shrink-0">
        {med.status ? (
          <span className={`text-xs font-medium ${med.status === "taken" ? "text-accent-600 dark:text-accent-400" : "text-slate-400"}`}>
            {med.status === "taken" ? "✓ Taken" : "Skipped"}
          </span>
        ) : (
          <>
            <button
              onClick={() => onTake(med)}
              disabled={!!logging}
              className="btn btn-primary btn-sm"
            >
              Taken
            </button>
            <button
              onClick={() => onSkip(med)}
              disabled={!!logging}
              className={BTN_GHOST}
            >
              Skip
            </button>
          </>
        )}
      </div>
    </div>
  );
}
