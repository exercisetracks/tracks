// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// One row in the left-hand exercise-library list. The leading "+" button adds
// the exercise to the workout being edited (`onAdd`); clicking the body opens
// the exercise detail popup (`onViewDetail`). Purely presentational — name,
// muscle lists and equipment come in as props.

export default function ExercisePickRow({ name, primary, secondary, equipment, onAdd, onViewDetail }) {
  return (
    <div className="flex items-center w-full text-left rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 hover:border-accent-300 dark:hover:border-accent-700 transition-colors overflow-hidden">
      <button onClick={onAdd}
        className="shrink-0 px-2.5 py-1.5 text-accent-600 hover:bg-accent-50 dark:hover:bg-accent-900/20 text-sm font-bold transition-colors border-r border-slate-200 dark:border-slate-700">
        +
      </button>
      <button onClick={onViewDetail} className="flex-1 px-2.5 py-1.5 min-w-0">
        <div className="flex items-center justify-between">
          <span className="text-sm font-medium text-slate-800 dark:text-slate-200 truncate hover:text-accent-600 dark:hover:text-accent-400">{name}</span>
          <span className="text-[10px] text-slate-400 shrink-0 ml-2">{(equipment || []).join(", ") || "bodyweight"}</span>
        </div>
        {(primary?.length > 0 || secondary?.length > 0) && (
          <div className="flex gap-1 mt-1 flex-wrap">
            {primary?.map(m => <span key={m} className="text-[10px] px-1 py-0.5 rounded bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300">{m}</span>)}
            {secondary?.map(m => <span key={m} className="text-[10px] px-1 py-0.5 rounded bg-slate-50 dark:bg-slate-800 text-slate-400">{m}</span>)}
          </div>
        )}
      </button>
    </div>
  );
}
