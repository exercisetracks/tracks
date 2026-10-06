// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Create / edit injury form. Owns its own draft + delete-confirmation state and
// merges any `initial` injury over EMPTY_FORM. Calls back via `onSave`,
// `onCancel`, and (when editing) `onDelete`; `loading` disables the submit.

import { useState } from "react";
import { EMPTY_FORM, BODY_PARTS, INJURY_TYPES } from "./constants";
import { isoToday } from "./helpers";
import DatePicker from "../ui/DatePicker";
import ConfirmDialog from "../ConfirmDialog";

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

  const inputCls = "field";

  return (
    <form onSubmit={handleSubmit} className="space-y-3">
      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className="field-label">Body Part</label>
          <select className={inputCls} {...field("body_part")}>
            {BODY_PARTS.map(p => <option key={p}>{p}</option>)}
          </select>
        </div>
        <div>
          <label className="field-label">Type</label>
          <select className={inputCls} {...field("injury_type")}>
            {INJURY_TYPES.map(t => <option key={t}>{t}</option>)}
          </select>
        </div>
      </div>
      <div>
        <label className="field-label">
          Severity: <strong>{form.severity}/10</strong>
        </label>
        <input type="range" min={1} max={10} step={1} className="w-full accent-accent-500" {...field("severity")} />
      </div>
      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className="field-label">Start date</label>
          <DatePicker className="w-full" value={form.start_date}
            onChange={v => setForm(f => ({ ...f, start_date: v || isoToday() }))} />
        </div>
        <div>
          <label className="field-label">End date (optional)</label>
          <DatePicker className="w-full" value={form.end_date} min={form.start_date} placeholder="Still ongoing"
            onChange={v => setForm(f => ({ ...f, end_date: v }))} />
        </div>
      </div>
      <div>
        <label className="field-label">Notes</label>
        <textarea rows={2} className={`${inputCls} resize-none`} placeholder="Optional notes…" {...field("notes")} />
      </div>

      <div className="flex items-center gap-2 justify-between pt-1">
        {/* Delete (only when editing) */}
        {onDelete && (
          <button
            type="button"
            onClick={() => setConfirmDelete(true)}
            className="btn btn-danger btn-sm"
          >
            Delete
          </button>
        )}
        {confirmDelete && (
          <ConfirmDialog
            title="Delete this injury?"
            message="It is removed permanently, with its notes."
            confirmLabel="Delete"
            danger
            onConfirm={onDelete}
            onCancel={() => setConfirmDelete(false)}
          />
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
