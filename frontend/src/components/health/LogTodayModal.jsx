// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// "Log today": one form for everything a person records by hand — weight,
// water and food — opened from the Body card. The desktop counterpart of the
// phone's HealthLogSheet, and laid out the same way.
//
// ## Why one form and not a Nutrition section
//
// Food used to live in its own section: two tabs, a library to curate before
// anything could be logged quickly, a time-of-day chart, and a "Log entry"
// panel elsewhere on the page with a separate calories field that the meal
// diary never fed. Three places, two calorie totals that did not agree, for an
// act that takes ten seconds. Everything here is one gesture — "here is
// something the watch could not know" — so it is one form with one Save.
//
// ## What is instant and what waits for Save
//
// The fields are a form: weight, water and a new food line are committed
// together by the one primary button, which says how many things it is about
// to record. The saved meals are not form fields — a chip is a single click
// that means something on its own, so it logs at once. Repeating yesterday's
// porridge should not cost a form. The water shortcuts add a glass to the
// field, which is how water is actually drunk: one more glass, not a total
// somebody computes.
//
// The library is built from the food line ("Remember this meal") rather than
// curated in advance, and Manage turns the chips into an editor for the rare
// fix or deletion.

import { useEffect, useMemo, useRef, useState } from "react";
import { api } from "../../api/client";
import { PlusIcon } from "../ui/Button";
import { localIso } from "./scales";
import { foodOn, grams, loggedAtFor } from "./food";

const LB_PER_KG = 2.20462;
/** A glass, a bottle, a big bottle. Round numbers people actually drink in. */
const GLASSES = [250, 500, 750];

const INPUT = "w-full text-sm rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-100 px-2.5 h-9 focus:outline-none focus:ring-2 focus:ring-accent-500";
const LABEL = "block text-xs text-slate-500 dark:text-slate-400 mb-1";
const HEADING = "text-[10px] font-semibold uppercase tracking-wider text-slate-400 dark:text-slate-500";

export default function LogTodayModal({ days, meals, log, imperial, onChanged, onClose }) {
  const overlayRef = useRef(null);
  const today = localIso();
  const [date, setDate] = useState(today);
  const [weight, setWeight] = useState("");
  const [water, setWater] = useState("");
  const [food, setFood] = useState({ name: "", kcal: "", protein: "", carbs: "", fat: "" });
  const [remember, setRemember] = useState(false);
  const [moreOpen, setMoreOpen] = useState(false);
  const [managing, setManaging] = useState(false);
  const [editing, setEditing] = useState(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    function onKey(e) { if (e.key === "Escape") onClose(); }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose]);

  const row = days.find(d => d.date === date);
  // Weight is a standing fact, so "so far" shows the newest one on or before
  // the day; water is the day's own total.
  const latestWeight = [...days].reverse().find(d => d.date <= date && d.weight_kg != null)?.weight_kg;
  const entries = useMemo(() => foodOn(log, date), [log, date]);
  const eaten = entries.reduce((s, e) => s + (e.calories ?? 0), 0);
  const unit = imperial ? "lbs" : "kg";

  const typedWeight = weight.trim() === "" ? null : parseFloat(weight);
  const typedWater = water.trim() === "" ? null : parseInt(water, 10);
  const typedFood = food.name.trim() !== "";
  // What Save is about to do, counted so the button can say it — the cheapest
  // answer to "did it notice what I typed".
  const pending = [Number.isFinite(typedWeight), Number.isFinite(typedWater), typedFood].filter(Boolean).length;

  const sofar = [
    latestWeight != null && `${(imperial ? latestWeight * LB_PER_KG : latestWeight).toFixed(1)} ${unit}`,
    row?.hydration_ml ? `${row.hydration_ml.toLocaleString()} ml` : null,
    eaten > 0 && `${eaten.toLocaleString()} kcal`,
  ].filter(Boolean).join(" · ");

  function addGlass(ml) {
    const base = typedWater ?? row?.hydration_ml ?? 0;
    setWater(String(base + ml));
  }

  // Typing a name that is already saved fills its calories, so a remembered
  // meal is as quick from the keyboard as from its chip.
  function setFoodName(name) {
    const match = meals.find(m => m.name.toLowerCase() === name.trim().toLowerCase());
    setFood(f => ({
      ...f, name,
      kcal: f.kcal === "" && match ? String(match.calories) : f.kcal,
    }));
  }

  async function run(work) {
    setError(null);
    try { await work(); onChanged(); }
    catch { setError("That didn't save. Check the connection and try again."); }
  }

  async function save(e) {
    e.preventDefault();
    if (!pending) return;
    setSaving(true);
    await run(async () => {
      const patch = {};
      if (Number.isFinite(typedWeight)) patch.weight_kg = imperial ? typedWeight / LB_PER_KG : typedWeight;
      if (Number.isFinite(typedWater)) patch.hydration_ml = typedWater;
      if (Object.keys(patch).length) await api.patchDailyMetric(date, patch);
      if (typedFood) {
        const entry = {
          name: food.name.trim(),
          calories: parseInt(food.kcal, 10) || 0,
          protein_g: grams(food.protein),
          carbs_g: grams(food.carbs),
          fat_g: grams(food.fat),
        };
        // Remembered only if it is not already: the name is the identity a
        // person sees, and two chips called "Porridge" would be a puzzle.
        let mealId = meals.find(m => m.name.toLowerCase() === entry.name.toLowerCase())?.id ?? null;
        if (remember && mealId == null) mealId = (await api.createMeal(entry)).id;
        await api.logMeal({ ...entry, meal_id: mealId, logged_at: loggedAtFor(date) });
      }
      setWeight(""); setWater("");
      setFood({ name: "", kcal: "", protein: "", carbs: "", fat: "" });
      setRemember(false); setMoreOpen(false);
    });
    setSaving(false);
  }

  function logSaved(meal) {
    run(() => api.logMeal({
      meal_id: meal.id, name: meal.name, calories: meal.calories,
      protein_g: meal.protein_g, carbs_g: meal.carbs_g, fat_g: meal.fat_g,
      logged_at: loggedAtFor(date),
    }));
  }

  return (
    <div
      ref={overlayRef}
      className="fixed inset-0 z-50 flex items-center justify-center p-3.5 bg-black/60 backdrop-blur-sm"
      onClick={e => { if (e.target === overlayRef.current) onClose(); }}
    >
      <div role="dialog" aria-modal="true" aria-label="Log today"
        className="bg-white dark:bg-slate-900 rounded-2xl border border-slate-200 dark:border-slate-700 shadow-2xl w-full max-w-lg max-h-[90vh] overflow-y-auto p-5 space-y-4">
        <div className="flex items-start justify-between gap-3">
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-base font-bold text-slate-900 dark:text-white">Log</h2>
              {/* Back-dating stays possible — a weigh-in forgotten yesterday
                  belongs to yesterday — but today is the default and the
                  only thing most visits need. */}
              <input type="date" max={today} value={date} onChange={e => setDate(e.target.value || today)}
                aria-label="Day to log for"
                className="text-sm font-semibold bg-transparent text-slate-900 dark:text-white rounded-md px-1 focus:outline-none focus:ring-2 focus:ring-accent-500" />
              {date !== today && (
                <button type="button" onClick={() => setDate(today)} className="btn btn-neutral btn-sm">Today</button>
              )}
            </div>
            <p className="mt-0.5 text-xs text-slate-500 dark:text-slate-400">
              {sofar ? `${sofar} so far` : "Nothing logged yet"}
            </p>
          </div>
          <button onClick={onClose} aria-label="Close"
            className="w-7 h-7 rounded-full flex items-center justify-center text-slate-400 hover:text-slate-700 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors text-lg leading-none">
            ×
          </button>
        </div>

        <form onSubmit={save} className="space-y-4">
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className={LABEL} htmlFor="log-weight">Weight ({unit})</label>
              <input id="log-weight" type="number" step="0.1" min="0" inputMode="decimal" className={INPUT}
                value={weight} onChange={e => setWeight(e.target.value)} />
            </div>
            <div>
              <label className={LABEL} htmlFor="log-water">Water (ml)</label>
              <input id="log-water" type="number" step="50" min="0" inputMode="numeric" className={INPUT}
                value={water} onChange={e => setWater(e.target.value)}
                placeholder={row?.hydration_ml ? String(row.hydration_ml) : ""} />
              <div className="mt-2 flex gap-1.5">
                {GLASSES.map(ml => (
                  <button key={ml} type="button" onClick={() => addGlass(ml)} className="btn btn-neutral btn-sm flex-1">+{ml}</button>
                ))}
              </div>
            </div>
          </div>

          <div className="space-y-2">
            <p className={HEADING}>Food</p>
            <div className="flex gap-2">
              <input type="text" list="saved-meals" aria-label="What did you eat?" placeholder="What did you eat?"
                className={INPUT} value={food.name} onChange={e => setFoodName(e.target.value)} />
              <input type="number" min="0" inputMode="numeric" aria-label="Calories" placeholder="kcal"
                className={`${INPUT} w-24 shrink-0`} value={food.kcal}
                onChange={e => setFood(f => ({ ...f, kcal: e.target.value }))} />
              <datalist id="saved-meals">
                {meals.map(m => <option key={m.id} value={m.name} />)}
              </datalist>
            </div>
            {moreOpen && (
              <div className="grid grid-cols-3 gap-2">
                {[["protein", "Protein (g)"], ["carbs", "Carbs (g)"], ["fat", "Fat (g)"]].map(([k, label]) => (
                  <div key={k}>
                    <label className={LABEL} htmlFor={`log-${k}`}>{label}</label>
                    <input id={`log-${k}`} type="number" min="0" step="0.1" className={INPUT}
                      value={food[k]} onChange={e => setFood(f => ({ ...f, [k]: e.target.value }))} />
                  </div>
                ))}
              </div>
            )}
            <div className="flex items-center justify-between gap-3">
              <label className="flex items-center gap-2 text-sm text-slate-600 dark:text-slate-300 cursor-pointer select-none">
                <input type="checkbox" checked={remember} onChange={e => setRemember(e.target.checked)}
                  className="rounded border-slate-300 dark:border-slate-600 text-accent-600 focus:ring-accent-500" />
                Remember this meal
              </label>
              <button type="button" onClick={() => setMoreOpen(o => !o)} className="btn btn-neutral btn-sm">
                {moreOpen ? "Fewer details" : "Protein, carbs, fat"}
              </button>
            </div>
          </div>

          <SavedMeals
            meals={meals}
            managing={managing}
            onToggleManage={() => { setManaging(m => !m); setEditing(null); }}
            onLog={logSaved}
            editing={editing}
            onEdit={setEditing}
            onSaveEdit={(id, data) => run(async () => { await api.updateMeal(id, data); setEditing(null); })}
            onDelete={id => run(async () => { await api.deleteMeal(id); setEditing(null); })}
          />

          {error && <p className="text-sm text-red-600 dark:text-red-400">{error}</p>}

          <button type="submit" disabled={!pending || saving} className="btn btn-primary w-full">
            {saving ? "Saving…" : pending > 1 ? `Save ${pending} entries` : "Save"}
          </button>
        </form>

        {entries.length > 0 && (
          <div className="border-t border-slate-100 dark:border-slate-800 pt-3">
            <div className="flex items-center justify-between mb-1.5">
              <p className={HEADING}>{date === today ? "Today" : "That day"}</p>
              <p className="text-sm font-semibold text-slate-700 dark:text-slate-200 tabular-nums">{eaten.toLocaleString()} kcal</p>
            </div>
            <ul className="divide-y divide-slate-100 dark:divide-slate-800">
              {entries.map(e => (
                <li key={e.id} className="flex items-center gap-3 py-1.5 text-sm">
                  <span className="w-16 shrink-0 text-xs text-slate-400 tabular-nums">
                    {new Date(e.logged_at).toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" })}
                  </span>
                  <span className="flex-1 min-w-0 truncate text-slate-800 dark:text-slate-100">{e.name}</span>
                  <span className="text-slate-500 dark:text-slate-400 tabular-nums">{e.calories}</span>
                  <button type="button" onClick={() => run(() => api.deleteMealLog(e.id))} aria-label={`Remove ${e.name}`}
                    className="w-6 h-6 rounded-full flex items-center justify-center text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-500/10 transition-colors">
                    ×
                  </button>
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </div>
  );
}

/**
 * The saved meals as chips. Normally a click logs one; under Manage a click
 * opens it for editing instead, so the two meanings never share a gesture.
 */
function SavedMeals({ meals, managing, onToggleManage, onLog, editing, onEdit, onSaveEdit, onDelete }) {
  if (!meals.length) {
    return (
      <p className="text-xs text-slate-400 dark:text-slate-500">
        Tick “Remember this meal” and it appears here, one click to log next time.
      </p>
    );
  }
  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between">
        <p className={HEADING}>Saved meals</p>
        <button type="button" onClick={onToggleManage} className={`btn btn-sm ${managing ? "btn-tonal" : "btn-neutral"}`}>
          {managing ? "Done" : "Manage"}
        </button>
      </div>
      <div className="flex flex-wrap gap-1.5">
        {meals.map(m => (
          <button key={m.id} type="button"
            onClick={() => (managing ? onEdit(m) : onLog(m))}
            aria-label={managing ? `Edit ${m.name}` : `Log ${m.name}`}
            className={`btn btn-sm ${managing ? (editing?.id === m.id ? "btn-primary" : "btn-neutral") : "btn-tonal"}`}>
            {!managing && <PlusIcon />}
            {m.name}
            <span className="opacity-60 tabular-nums">{m.calories}</span>
          </button>
        ))}
      </div>
      {managing && editing && (
        <MealEditor key={editing.id} meal={editing} onSave={onSaveEdit} onDelete={onDelete} onCancel={() => onEdit(null)} />
      )}
    </div>
  );
}

function MealEditor({ meal, onSave, onDelete, onCancel }) {
  const [f, setF] = useState({
    name: meal.name, kcal: String(meal.calories ?? ""),
    protein: meal.protein_g ?? "", carbs: meal.carbs_g ?? "", fat: meal.fat_g ?? "",
  });
  const bind = k => ({ value: f[k], onChange: e => setF(p => ({ ...p, [k]: e.target.value })) });
  return (
    <div className="rounded-xl bg-slate-50 dark:bg-slate-800/60 p-3 space-y-2">
      <div className="flex gap-2">
        <input type="text" aria-label="Meal name" className={INPUT} {...bind("name")} />
        <input type="number" min="0" aria-label="Calories" placeholder="kcal" className={`${INPUT} w-24 shrink-0`} {...bind("kcal")} />
      </div>
      <div className="grid grid-cols-3 gap-2">
        {[["protein", "Protein (g)"], ["carbs", "Carbs (g)"], ["fat", "Fat (g)"]].map(([k, label]) => (
          <input key={k} type="number" min="0" step="0.1" aria-label={label} placeholder={label} className={INPUT} {...bind(k)} />
        ))}
      </div>
      <div className="flex gap-2 justify-end">
        <button type="button" onClick={() => onDelete(meal.id)} className="btn btn-danger btn-sm mr-auto">Delete</button>
        <button type="button" onClick={onCancel} className="btn btn-neutral btn-sm">Cancel</button>
        <button type="button" disabled={!f.name.trim()}
          onClick={() => onSave(meal.id, {
            name: f.name.trim(), calories: parseInt(f.kcal, 10) || 0,
            // Notes are not edited here and are left out: the PATCH only
            // touches the fields it is sent.
            protein_g: grams(f.protein), carbs_g: grams(f.carbs), fat_g: grams(f.fat),
          })}
          className="btn btn-primary btn-sm">Save</button>
      </div>
    </div>
  );
}
