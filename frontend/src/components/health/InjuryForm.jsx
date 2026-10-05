// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Create / edit injury form. Owns its own draft + delete-confirmation state and
// merges any `initial` injury over EMPTY_FORM. Calls back via `onSave`,
// `onCancel`, and (when editing) `onDelete`; `loading` disables the submit.

import { useState } from "react";
import { EMPTY_FORM, BODY_PARTS, INJURY_TYPES } from "./constants";

export default function InjuryForm({ initial = EMPTY_FORM, onSave, onCancel, onDelete, loading }) {
  const [form, setForm] = useState({ ...EMPTY_FORM, ...initial, end_date: initial.end_date ?? "" });
  const [confirmDelete, setConfirmDelete] = useState(false);

  function field(key) {
    return {
      value: form[key] ?? "",
      onChange: e => setForm(f => ({ ...f, [key]: e.target.value })),
    };
  }

  function handleSubmit(e) {
    e.preventDefault();
    onSave({
      ...form,
      severity: Number(form.severity),
      end_date: form.end_date || null,
      notes: form.notes || null,
    });
  }

  const inputCls = "w-full text-sm rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-100 px-2.5 py-1 focus:outline-none focus:ring-2 focus:ring-accent-500";

  return (
    <form onSubmit={handleSubmit} className="space-y-3">
      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className="block text-xs text-slate-500 dark:text-slate-400 mb-1">Body Part</label>
          <select className={inputCls} {...field("body_part")}>
            {BODY_PARTS.map(p => <option key={p}>{p}</option>)}
          </select>
        </div>
        <div>
          <label className="block text-xs text-slate-500 dark:text-slate-400 mb-1">Type</label>
          <select className={inputCls} {...field("injury_type")}>
            {INJURY_TYPES.map(t => <option key={t}>{t}</option>)}
          </select>
        </div>
      </div>
      <div>
        <label className="block text-xs text-slate-500 dark:text-slate-400 mb-1">
          Severity: <strong>{form.severity}/10</strong>
        </label>
        <input type="range" min={1} max={10} step={1} className="w-full accent-accent-500" {...field("severity")} />
      </div>
      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className="block text-xs text-slate-500 dark:text-slate-400 mb-1">Start date</label>
          <input type="date" required className={inputCls} {...field("start_date")} />
        </div>
        <div>
          <label className="block text-xs text-slate-500 dark:text-slate-400 mb-1">End date (optional)</label>
          <input type="date" className={inputCls} {...field("end_date")} />
        </div>
      </div>
      <div>
        <label className="block text-xs text-slate-500 dark:text-slate-400 mb-1">Notes</label>
        <textarea rows={2} className={`${inputCls} resize-none`} placeholder="Optional notes…" {...field("notes")} />
      </div>

      <div className="flex items-center gap-2 justify-between pt-1">
        {/* Delete (only when editing) */}
        {onDelete && (
          confirmDelete ? (
            <div className="flex items-center gap-2 bg-red-50 dark:bg-red-950 border border-red-200 dark:border-red-800 rounded-lg px-2.5 py-1.5">
              <span className="text-xs text-red-700 dark:text-red-300">Delete permanently?</span>
              <button
                type="button"
                onClick={onDelete}
                className="btn btn-danger btn-sm"
              >
                Yes, delete
              </button>
              <button
                type="button"
                onClick={() => setConfirmDelete(false)}
                className="btn btn-neutral btn-sm"
              >
                Cancel
              </button>
            </div>
          ) : (
            <button
              type="button"
              onClick={() => setConfirmDelete(true)}
              className="btn btn-danger btn-sm"
            >
              Delete
            </button>
          )
        )}

        <div className="flex gap-2 ml-auto">
          <button
            type="button"
            onClick={onCancel}
            className="btn btn-neutral btn-sm"
          >
            Cancel
          </button>
          <button
            type="submit"
            disabled={loading}
            className="btn btn-primary btn-sm"
          >
            {loading ? "Saving…" : "Save"}
          </button>
        </div>
      </div>
    </form>
  );
}
