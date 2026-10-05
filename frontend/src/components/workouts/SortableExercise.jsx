// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// One drag-sortable exercise row inside the workout editor: exposes the
// sets / reps / RIR / rest inputs plus the weight-method picker and its value
// field. Fully presentational — the exercise object and all mutations live in
// the parent; `onChange(id, field, value)` writes a single field, `onRemove(id)`
// deletes the row, and `onViewDetail(ex)` opens the exercise info popup.
// Dragging is the parent's too: BlockStructure moves rows into and out of
// Superset / Repeat blocks by where they are dropped. Inside a group the
// group's rounds are the sets, so `inGroup` hides the Sets field. Reps are one
// number, not a range — the watch's workout step counts one.

import { WEIGHT_METHODS } from "./constants";

export default function SortableExercise({ ex, id, onChange, onRemove, onViewDetail, inGroup }) {
  const handle = (field, val) => onChange(id, field, val);

  return (
    <div className="rounded-lg border bg-white dark:bg-slate-800 p-1.5 border-slate-200 dark:border-slate-700 hover:border-accent-300 dark:hover:border-accent-700">
      <div className="flex items-center justify-between mb-1.5">
        <div className="flex items-center gap-2">
          <button onClick={() => onViewDetail(ex)}
            className="text-xs font-semibold text-slate-800 dark:text-slate-200 hover:text-accent-600 dark:hover:text-accent-400 text-left">
            {ex.exercise_name}
          </button>
        </div>
        <span className="flex items-center gap-2">
          <button onClick={() => onRemove(id)} className="text-sm text-red-500 hover:text-red-700 shrink-0 ml-2 p-1 leading-none">✕</button>
        </span>
      </div>
      <div className="grid grid-cols-4 gap-1.5 text-[10px]">
        <div className={inGroup ? "invisible" : ""}>
          <label className="text-slate-400 block">Sets</label>
          <input type="number" min={1} max={15} value={ex.target_sets}
            onChange={e => handle("target_sets", parseInt(e.target.value) || 3)}
            className="w-full rounded bg-slate-50 dark:bg-slate-900 border border-slate-200 dark:border-slate-700 px-1 py-0.5 text-slate-800 dark:text-slate-200" />
        </div>
        <div className="col-span-2">
          <label className="text-slate-400 block">Reps</label>
          <div className="flex items-center gap-0.5">
            <input type="number" min={1} max={50} value={ex.target_reps}
              onChange={e => handle("target_reps", parseInt(e.target.value) || 8)}
              className="w-12 rounded bg-slate-50 dark:bg-slate-900 border border-slate-200 dark:border-slate-700 px-1 py-0.5 text-slate-800 dark:text-slate-200" />
            <span className="text-slate-400 ml-1">RIR</span>
            <input type="number" min={0} max={5} value={ex.rir_target}
              onChange={e => handle("rir_target", parseInt(e.target.value) || 2)}
              className="w-8 rounded bg-slate-50 dark:bg-slate-900 border border-slate-200 dark:border-slate-700 px-1 py-0.5 text-slate-800 dark:text-slate-200" />
          </div>
        </div>
        <div>
          <label className="text-slate-400 block">Rest</label>
          <input type="number" min={0} max={600} step={15} value={ex.rest_seconds}
            onChange={e => handle("rest_seconds", parseInt(e.target.value) || 90)}
            className="w-full rounded bg-slate-50 dark:bg-slate-900 border border-slate-200 dark:border-slate-700 px-1 py-0.5 text-slate-800 dark:text-slate-200" />
        </div>
      </div>
      <div className="mt-1.5">
        <div className="flex gap-1">
          {WEIGHT_METHODS.map(wm => (
            <button key={wm.key} onClick={() => handle("weight_method", wm.key)}
              className={`flex-1 text-[10px] px-1 py-1 rounded transition-colors ${
                ex.weight_method === wm.key
                  ? "bg-accent-600 text-white"
                  : "bg-slate-100 dark:bg-slate-700 text-slate-500 dark:text-slate-400 hover:bg-slate-200 dark:hover:bg-slate-600"
              }`}>{wm.label}</button>
          ))}
        </div>
        {ex.weight_method === "percentage_e1rm" && (
          <div className="mt-1 flex items-center gap-1 text-[10px]">
            <input type="number" min={0.3} max={1.0} step={0.05} value={ex.weight_value || 0.75}
              onChange={e => handle("weight_value", parseFloat(e.target.value) || 0.75)}
              className="w-14 rounded bg-slate-50 dark:bg-slate-900 border border-slate-200 dark:border-slate-700 px-1 py-0.5 text-slate-800 dark:text-slate-200" />
            <span className="text-slate-500">× e1RM</span>
          </div>
        )}
        {ex.weight_method === "fixed" && (
          <div className="mt-1 flex items-center gap-1 text-[10px]">
            <input type="number" min={0} step={0.5} value={ex.weight_value || 20}
              onChange={e => handle("weight_value", parseFloat(e.target.value) || 20)}
              className="w-14 rounded bg-slate-50 dark:bg-slate-900 border border-slate-200 dark:border-slate-700 px-1 py-0.5 text-slate-800 dark:text-slate-200" />
            <span className="text-slate-500">kg</span>
          </div>
        )}
      </div>
    </div>
  );
}
