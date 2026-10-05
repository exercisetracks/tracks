// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Create / edit form for a reusable meal template (name + macros + notes). Local
// form state seeds from `initial` (edit) or empty strings (add). On submit it
// parses the numeric fields and hands a clean payload to `onSave`; the parent
// owns the API call and the `loading` flag. Purely presentational otherwise.

import { useState } from "react";
import { INPUT, BTN_GHOST, BTN_PRIMARY } from "./constants";

export default function MealForm({ initial, onSave, onCancel, loading }) {
  const [form, setForm] = useState({
    name:      initial?.name      ?? "",
    calories:  initial?.calories  ?? "",
    protein_g: initial?.protein_g ?? "",
    carbs_g:   initial?.carbs_g   ?? "",
    fat_g:     initial?.fat_g     ?? "",
    notes:     initial?.notes     ?? "",
  });

  // Spreadable value/onChange pair for a controlled input bound to `form[key]`.
  function f(key) {
    return { value: form[key], onChange: e => setForm(p => ({ ...p, [key]: e.target.value })) };
  }

  function handleSubmit(e) {
    e.preventDefault();
    // Coerce the string inputs to numbers; blank macro fields stay null.
    onSave({
      name:      form.name.trim(),
      calories:  parseInt(form.calories, 10) || 0,
      protein_g: form.protein_g !== "" ? parseFloat(form.protein_g) : null,
      carbs_g:   form.carbs_g   !== "" ? parseFloat(form.carbs_g)   : null,
      fat_g:     form.fat_g     !== "" ? parseFloat(form.fat_g)     : null,
      notes:     form.notes.trim() || null,
    });
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-3">
      <div>
        <label className="block text-xs text-slate-500 dark:text-slate-400 mb-1">Meal name *</label>
        <input type="text" required className={INPUT} placeholder="e.g. Oatmeal with berries" {...f("name")} />
      </div>
      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className="block text-xs text-slate-500 dark:text-slate-400 mb-1">Calories (kcal) *</label>
          <input type="number" min="0" required className={INPUT} placeholder="e.g. 350" {...f("calories")} />
        </div>
        <div>
          <label className="block text-xs text-slate-500 dark:text-slate-400 mb-1">Protein (g)</label>
          <input type="number" min="0" step="0.1" className={INPUT} placeholder="e.g. 12" {...f("protein_g")} />
        </div>
        <div>
          <label className="block text-xs text-slate-500 dark:text-slate-400 mb-1">Carbs (g)</label>
          <input type="number" min="0" step="0.1" className={INPUT} placeholder="e.g. 55" {...f("carbs_g")} />
        </div>
        <div>
          <label className="block text-xs text-slate-500 dark:text-slate-400 mb-1">Fat (g)</label>
          <input type="number" min="0" step="0.1" className={INPUT} placeholder="e.g. 8" {...f("fat_g")} />
        </div>
      </div>
      <div>
        <label className="block text-xs text-slate-500 dark:text-slate-400 mb-1">Notes</label>
        <input type="text" className={INPUT} placeholder="Optional" {...f("notes")} />
      </div>
      <div className="flex gap-2 pt-1">
        <button type="button" onClick={onCancel} className={BTN_GHOST}>Cancel</button>
        <button type="submit" disabled={loading} className={BTN_PRIMARY}>
          {loading ? "Saving…" : initial ? "Save changes" : "Add meal"}
        </button>
      </div>
    </form>
  );
}
