// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Modal for logging a meal entry to the diary. When `meal` is a template the
// macro fields pre-fill from it and the name is fixed; when `meal` is null the
// user enters a one-off custom entry. Owns its own local form + `saving` state
// and the datetime picker; `onLog` performs the API call (awaited so the modal
// only closes on success), `onClose` dismisses it.

import { useState } from "react";
import { INPUT, BTN_GHOST, BTN_PRIMARY } from "./constants";

export default function LogModal({ meal, onLog, onClose }) {
  // Default the "logged at" picker to now, in the browser's local timezone
  // (datetime-local wants a naive local ISO string, hence the offset shift).
  const now = new Date();
  const localISO = new Date(now - now.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
  const [loggedAt, setLoggedAt] = useState(localISO);
  const [form, setForm] = useState({
    name:      meal?.name      ?? "",
    calories:  meal?.calories  ?? "",
    protein_g: meal?.protein_g ?? "",
    carbs_g:   meal?.carbs_g   ?? "",
    fat_g:     meal?.fat_g     ?? "",
    notes:     "",
  });
  const [saving, setSaving] = useState(false);

  // Spreadable value/onChange pair for a controlled input bound to `form[key]`.
  function f(key) {
    return { value: form[key] ?? "", onChange: e => setForm(p => ({ ...p, [key]: e.target.value })) };
  }

  async function handleSubmit(e) {
    e.preventDefault();
    setSaving(true);
    try {
      await onLog({
        meal_id:   meal?.id ?? null,
        name:      form.name.trim(),
        calories:  parseInt(form.calories, 10) || 0,
        protein_g: form.protein_g !== "" ? parseFloat(form.protein_g) : null,
        carbs_g:   form.carbs_g   !== "" ? parseFloat(form.carbs_g)   : null,
        fat_g:     form.fat_g     !== "" ? parseFloat(form.fat_g)     : null,
        logged_at: new Date(loggedAt).toISOString(),
        notes:     form.notes.trim() || null,
      });
      onClose();
    } catch { /* ignore */ }
    finally { setSaving(false); }
  }

  return (
    // Backdrop click closes; inner click is stopped so it doesn't bubble to close.
    <div className="fixed inset-0 bg-black/60 backdrop-blur-sm z-50 flex items-center justify-center p-3.5" onClick={onClose}>
      <div className="bg-white dark:bg-slate-900 rounded-2xl border border-slate-200 dark:border-slate-800 shadow-2xl w-full max-w-md p-5" onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between mb-4">
          <h3 className="font-semibold text-slate-800 dark:text-slate-100">{meal ? `Log: ${meal.name}` : "Log custom entry"}</h3>
          <button onClick={onClose} className="text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 text-2xl leading-none">×</button>
        </div>
        <form onSubmit={handleSubmit} className="space-y-3">
          {/* Name is only editable for custom entries; templates carry their own. */}
          {!meal && (
            <div>
              <label className="block text-xs text-slate-500 dark:text-slate-400 mb-1">Name *</label>
              <input type="text" required className={INPUT} placeholder="What did you eat?" {...f("name")} />
            </div>
          )}
          <div>
            <label className="block text-xs text-slate-500 dark:text-slate-400 mb-1">Time</label>
            <input type="datetime-local" className={INPUT} value={loggedAt} onChange={e => setLoggedAt(e.target.value)} />
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-xs text-slate-500 dark:text-slate-400 mb-1">Calories (kcal)</label>
              <input type="number" min="0" className={INPUT} {...f("calories")} />
            </div>
            <div>
              <label className="block text-xs text-slate-500 dark:text-slate-400 mb-1">Protein (g)</label>
              <input type="number" min="0" step="0.1" className={INPUT} {...f("protein_g")} />
            </div>
            <div>
              <label className="block text-xs text-slate-500 dark:text-slate-400 mb-1">Carbs (g)</label>
              <input type="number" min="0" step="0.1" className={INPUT} {...f("carbs_g")} />
            </div>
            <div>
              <label className="block text-xs text-slate-500 dark:text-slate-400 mb-1">Fat (g)</label>
              <input type="number" min="0" step="0.1" className={INPUT} {...f("fat_g")} />
            </div>
          </div>
          <div>
            <label className="block text-xs text-slate-500 dark:text-slate-400 mb-1">Notes</label>
            <input type="text" className={INPUT} placeholder="Optional" {...f("notes")} />
          </div>
          <div className="flex gap-2 pt-1">
            <button type="button" onClick={onClose} className={BTN_GHOST}>Cancel</button>
            <button type="submit" disabled={saving} className={BTN_PRIMARY}>
              {saving ? "Logging…" : "Log entry"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
