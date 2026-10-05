// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Manual daily-metric logger (weight / hydration / calories) for a chosen date.
// Owns its own form + date + saving/saved state; converts weight to kg when the
// user prefers imperial, PATCHes the daily metric, and calls `onSaved` so the
// parent can refetch.

import { useState } from "react";
import { api } from "../../api/client";

export default function ManualEntryPanel({ today, imperial, onSaved }) {
  const weightLabel = imperial ? "Weight (lbs)" : "Weight (kg)";
  const weightPlaceholder = imperial ? "e.g. 160" : "e.g. 72.5";

  const [selectedDate, setSelectedDate] = useState(today);
  const [form, setForm] = useState({ weight: "", hydration_ml: "", calories_in: "" });
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);

  function field(key) {
    return { value: form[key], onChange: e => setForm(f => ({ ...f, [key]: e.target.value })) };
  }

  const isToday = selectedDate === today;
  const dateLabel = isToday
    ? "Today"
    : new Date(selectedDate + "T00:00:00").toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" });

  async function handleSubmit(e) {
    e.preventDefault();
    const data = {};
    if (form.weight) {
      const raw = parseFloat(form.weight);
      data.weight_kg = imperial ? raw * 0.453592 : raw;
    }
    if (form.hydration_ml) data.hydration_ml = parseInt(form.hydration_ml, 10);
    if (form.calories_in)  data.calories_in  = parseInt(form.calories_in, 10);
    if (!Object.keys(data).length) return;
    setSaving(true);
    try {
      await api.patchDailyMetric(selectedDate, data);
      setSaved(true);
      setForm({ weight: "", hydration_ml: "", calories_in: "" });
      setTimeout(() => setSaved(false), 2000);
      onSaved();
    } catch { /* ignore */ }
    finally { setSaving(false); }
  }

  const inputCls = "text-sm rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-100 px-2.5 py-1 focus:outline-none focus:ring-2 focus:ring-accent-500 w-full";

  return (
    <form onSubmit={handleSubmit} className="space-y-3">
      <div className="flex items-center gap-2">
        <label className="text-xs text-slate-500 dark:text-slate-400 shrink-0">Date</label>
        <input
          type="date"
          max={today}
          value={selectedDate}
          onChange={e => setSelectedDate(e.target.value)}
          className={inputCls + " w-auto"}
        />
        {!isToday && (
          <button type="button" onClick={() => setSelectedDate(today)}
            className="btn btn-tonal btn-sm">
            Reset to today
          </button>
        )}
      </div>
      <div className="grid grid-cols-3 gap-3 items-end">
        <div>
          <label className="block text-xs text-slate-500 dark:text-slate-400 mb-1">{weightLabel}</label>
          <input type="number" step="0.1" placeholder={weightPlaceholder} className={inputCls} {...field("weight")} />
        </div>
        <div>
          <label className="block text-xs text-slate-500 dark:text-slate-400 mb-1">Hydration (ml)</label>
          <input type="number" step="50" placeholder="e.g. 2500" className={inputCls} {...field("hydration_ml")} />
        </div>
        <div>
          <label className="block text-xs text-slate-500 dark:text-slate-400 mb-1">Calories (kcal)</label>
          <input type="number" step="10" placeholder="e.g. 2200" className={inputCls} {...field("calories_in")} />
        </div>
        <div className="col-span-3 flex justify-end">
          <button
            type="submit"
            disabled={saving}
            className="btn btn-primary btn-sm"
          >
            {saved ? "Saved!" : saving ? "Saving…" : `Log for ${dateLabel}`}
          </button>
        </div>
      </div>
    </form>
  );
}
