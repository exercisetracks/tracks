// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Add / edit form for a medication and its schedules. Local form + schedules
// state seed from `initial` (edit) or sensible defaults (add). On submit it hands
// the combined `{ ...form, schedules }` object to `onSave`; the parent owns the
// API call and the `loading` flag.

import { useState } from "react";
import { INPUT, BTN_GHOST, BTN_PRIMARY, BTN_TONAL, FORMS, DOSE_UNITS } from "./constants";
import { PlusIcon } from "../ui/Button";
import ScheduleRow from "./ScheduleRow";
import { todayIso } from "../../lib/today";

export default function MedForm({ initial, onSave, onCancel, loading }) {
  const today = todayIso();
  const [form, setForm] = useState({
    name:      initial?.name      ?? "",
    dose:      initial?.dose      ?? "",
    dose_unit: initial?.dose_unit ?? "mg",
    form:      initial?.form      ?? "Tablet",
    notes:     initial?.notes     ?? "",
    is_active: initial?.is_active ?? true,
  });
  const [schedules, setSchedules] = useState(
    initial?.schedules?.length
      ? initial.schedules.map(s => ({ ...s }))
      : [{ time_of_day: "08:00", days_of_week: null, start_date: today, end_date: null, notify: false, is_as_needed: false }]
  );

  function addSchedule() {
    setSchedules(p => [...p, { time_of_day: "08:00", days_of_week: null, start_date: today, end_date: null, notify: false, is_as_needed: false }]);
  }

  function handleSubmit(e) {
    e.preventDefault();
    onSave({ ...form, schedules });
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      <div className="grid grid-cols-2 gap-3">
        <div className="col-span-2">
          <label className="field-label">Medication name *</label>
          <input type="text" required className={INPUT} placeholder="e.g. Metformin" value={form.name} onChange={e => setForm(p => ({ ...p, name: e.target.value }))} />
        </div>
        <div>
          <label className="field-label">Dose</label>
          <input type="text" className={INPUT} placeholder="e.g. 500" value={form.dose} onChange={e => setForm(p => ({ ...p, dose: e.target.value }))} />
        </div>
        <div>
          <label className="field-label">Unit</label>
          <select className={INPUT} value={form.dose_unit} onChange={e => setForm(p => ({ ...p, dose_unit: e.target.value }))}>
            {DOSE_UNITS.map(u => <option key={u} value={u}>{u}</option>)}
          </select>
        </div>
        <div>
          <label className="field-label">Form</label>
          <select className={INPUT} value={form.form} onChange={e => setForm(p => ({ ...p, form: e.target.value }))}>
            {FORMS.map(f => <option key={f} value={f}>{f}</option>)}
          </select>
        </div>
        <div>
          <label className="field-label">Status</label>
          <select className={INPUT} value={form.is_active ? "active" : "inactive"} onChange={e => setForm(p => ({ ...p, is_active: e.target.value === "active" }))}>
            <option value="active">Active</option>
            <option value="inactive">Inactive</option>
          </select>
        </div>
        <div className="col-span-2">
          <label className="field-label">Notes</label>
          <input type="text" className={INPUT} placeholder="e.g. Take with food" value={form.notes} onChange={e => setForm(p => ({ ...p, notes: e.target.value }))} />
        </div>
      </div>

      <div>
        <div className="flex items-center justify-between mb-2">
          <p className="text-xs font-medium text-slate-500 dark:text-slate-400">Schedule</p>
          <button type="button" onClick={addSchedule} className={BTN_TONAL}><PlusIcon />Add time</button>
        </div>
        <div className="space-y-2">
          {schedules.map((s, i) => (
            <ScheduleRow
              key={i}
              sched={s}
              onChange={updated => setSchedules(p => p.map((x, j) => j === i ? updated : x))}
              onRemove={() => setSchedules(p => p.filter((_, j) => j !== i))}
            />
          ))}
        </div>
      </div>

      <div className="flex gap-2 pt-1">
        <button type="button" onClick={onCancel} className={BTN_GHOST}>Cancel</button>
        <button type="submit" disabled={loading} className={BTN_PRIMARY}>
          {loading ? "Saving…" : initial ? "Save changes" : "Add medication"}
        </button>
      </div>
    </form>
  );
}
