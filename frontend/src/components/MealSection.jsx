// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Nutrition section of the app: a two-tab panel ("Today" and "Meal Library")
// plus a quick-log modal. This component is the stateful orchestrator — it owns
// all the API calls (list/create/update/delete meal templates, log/delete diary
// entries), the 7-day summary, and which tab / form / modal is open. It derives
// today's macro totals from the summary and renders everything.
//
// The genuinely separable presentational pieces live in ./meal/:
//   - MealForm       create / edit a reusable meal template
//   - LogModal       quick-log a template or a custom entry (self-contained state)
//   - TimeOfDayChart calories-by-hour bar chart (pure, two array props)
//   - constants      shared INPUT/BTN_* style tokens + fmtNum/clsx helpers
// The tab layout and list rendering stay inline here because they are tightly
// coupled to this component's state and would only add prop-drilling if split.

import { useState, useEffect, useCallback } from "react";
import { api } from "../api/client";
import { fmtNum, clsx, BTN_PRIMARY, BTN_GHOST, BTN_TONAL, BTN_DANGER } from "./meal/constants";
import { PlusIcon } from "./ui/Button";
import MealForm from "./meal/MealForm";
import LogModal from "./meal/LogModal";
import TimeOfDayChart from "./meal/TimeOfDayChart";
import Tabs from "./ui/Tabs";

export default function MealSection() {
  const [meals,       setMeals]       = useState([]);
  const [summary,     setSummary]     = useState(null);
  const [activeTab,   setActiveTab]   = useState("today");
  const [showAdd,     setShowAdd]     = useState(false);
  const [editMeal,    setEditMeal]    = useState(null);
  const [logMeal,     setLogMeal]     = useState(null);    // null | meal | "custom"
  const [addLoading,  setAddLoading]  = useState(false);
  const [deletingId,  setDeletingId]  = useState(null);

  // Refetch the meal library and the rolling 7-day log summary together.
  const reload = useCallback(() => {
    api.getMeals().then(setMeals).catch(() => {});
    api.getMealLogSummary(7).then(setSummary).catch(() => {});
  }, []);

  useEffect(() => { reload(); }, [reload]);

  // Create a new template or save edits to an existing one, then refresh.
  async function handleSaveMeal(data) {
    setAddLoading(true);
    try {
      if (editMeal) {
        await api.updateMeal(editMeal.id, data);
        setEditMeal(null);
      } else {
        await api.createMeal(data);
        setShowAdd(false);
      }
      reload();
    } catch { /* ignore */ }
    finally { setAddLoading(false); }
  }

  async function handleDeleteMeal(id) {
    setDeletingId(id);
    try {
      await api.deleteMeal(id);
      reload();
    } catch { /* ignore */ }
    finally { setDeletingId(null); }
  }

  // Log a diary entry (from LogModal). Awaited so the modal closes only on success.
  async function handleLog(data) {
    await api.logMeal(data);
    reload();
  }

  async function handleDeleteLogEntry(id) {
    await api.deleteMealLog(id).catch(() => {});
    reload();
  }

  // Today's logged entries + derived macro totals for the summary card.
  const today = summary?.today_entries ?? [];
  const todayTotal = summary?.today_total?.calories ?? 0;
  const todayMacros = today.reduce(
    (acc, e) => ({
      protein_g: acc.protein_g + (e.protein_g ?? 0),
      carbs_g:   acc.carbs_g   + (e.carbs_g   ?? 0),
      fat_g:     acc.fat_g     + (e.fat_g     ?? 0),
    }),
    { protein_g: 0, carbs_g: 0, fat_g: 0 },
  );

  const TABS = [
    { key: "today",   label: "Today" },
    { key: "library", label: "Meal Library" },
  ];

  return (
    <div className="space-y-4">
      {/* Tab bar */}
      <div className="flex items-center justify-between">
        <Tabs tabs={TABS} value={activeTab} onChange={setActiveTab} size="sm" />
        <div className="flex gap-2">
          <button onClick={() => setLogMeal("custom")} className={BTN_TONAL}><PlusIcon />Custom entry</button>
        </div>
      </div>

      {/* Today tab */}
      {activeTab === "today" && (
        <div className="space-y-4">
          {/* Daily totals */}
          <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 p-3.5">
            <div className="flex items-center justify-between mb-3">
              <p className="text-xs font-medium text-slate-500 dark:text-slate-400">Today's totals</p>
              <p className="text-lg font-bold text-slate-800 dark:text-slate-100">{todayTotal.toLocaleString()} kcal</p>
            </div>
            <div className="grid grid-cols-3 gap-3 text-center text-xs">
              <div>
                <p className="text-slate-400 dark:text-slate-500">Protein</p>
                <p className="font-semibold text-slate-700 dark:text-slate-300">{fmtNum(todayMacros.protein_g, 1)}g</p>
              </div>
              <div>
                <p className="text-slate-400 dark:text-slate-500">Carbs</p>
                <p className="font-semibold text-slate-700 dark:text-slate-300">{fmtNum(todayMacros.carbs_g, 1)}g</p>
              </div>
              <div>
                <p className="text-slate-400 dark:text-slate-500">Fat</p>
                <p className="font-semibold text-slate-700 dark:text-slate-300">{fmtNum(todayMacros.fat_g, 1)}g</p>
              </div>
            </div>
          </div>

          {/* Time of day chart */}
          <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 p-3.5">
            <p className="text-xs font-medium text-slate-500 dark:text-slate-400 mb-3">
              Calories by time of day
              <span className="ml-2 text-slate-400 font-normal">(today vs. 7-day avg)</span>
            </p>
            <TimeOfDayChart
              todayByHour={summary?.today_by_hour  ?? Array(24).fill(0)}
              weeklyByHour={summary?.weekly_by_hour ?? Array(24).fill(0)}
            />
          </div>

          {/* Today's log entries */}
          <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 p-3.5">
            <div className="flex items-center justify-between mb-3">
              <p className="text-xs font-medium text-slate-500 dark:text-slate-400">Logged today</p>
            </div>
            {today.length === 0 ? (
              <p className="text-xs text-slate-400 text-center py-3.5">No entries yet — log your first meal above</p>
            ) : (
              <div className="space-y-2">
                {today.map(e => {
                  const time = new Date(e.logged_at).toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" });
                  return (
                    <div key={e.id} className="flex items-center justify-between gap-2 py-1 border-b border-slate-100 dark:border-slate-800 last:border-0">
                      <div className="min-w-0">
                        <p className="text-sm text-slate-800 dark:text-slate-100 font-medium truncate">{e.name}</p>
                        <p className="text-xs text-slate-400">{time}</p>
                      </div>
                      <div className="flex items-center gap-3 shrink-0">
                        <div className="text-right">
                          <p className="text-sm font-semibold text-slate-700 dark:text-slate-300">{e.calories} kcal</p>
                          {(e.protein_g || e.carbs_g || e.fat_g) && (
                            <p className="text-xs text-slate-400">
                              P:{fmtNum(e.protein_g, 0)}g C:{fmtNum(e.carbs_g, 0)}g F:{fmtNum(e.fat_g, 0)}g
                            </p>
                          )}
                        </div>
                        <button
                          onClick={() => handleDeleteLogEntry(e.id)}
                          className="text-slate-300 dark:text-slate-600 hover:text-red-400 dark:hover:text-red-500 transition-colors text-lg leading-none"
                          title="Remove"
                        >
                          ×
                        </button>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>

          {/* Quick-log from library */}
          {meals.length > 0 && (
            <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 p-3.5">
              <p className="text-xs font-medium text-slate-500 dark:text-slate-400 mb-3">Quick-add from library</p>
              <div className="flex flex-wrap gap-2">
                {meals.map(m => (
                  <button
                    key={m.id}
                    onClick={() => setLogMeal(m)}
                    className="flex items-center gap-1.5 text-xs px-2.5 py-1 rounded-full border border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-300 hover:border-accent-400 hover:text-accent-600 dark:hover:text-accent-400 transition-colors"
                  >
                    <span>{m.name}</span>
                    <span className="text-slate-400">· {m.calories} kcal</span>
                  </button>
                ))}
              </div>
            </div>
          )}
        </div>
      )}

      {/* Library tab */}
      {activeTab === "library" && (
        <div className="space-y-3">
          <button onClick={() => { setShowAdd(true); setEditMeal(null); }} className={BTN_PRIMARY}>
            <PlusIcon />Add meal to library
          </button>

          {showAdd && !editMeal && (
            <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 p-3.5">
              <p className="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-3">New meal</p>
              <MealForm onSave={handleSaveMeal} onCancel={() => setShowAdd(false)} loading={addLoading} />
            </div>
          )}

          {meals.length === 0 && !showAdd ? (
            <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 p-7 text-center">
              <p className="text-sm text-slate-400">No saved meals yet.</p>
              <p className="text-xs text-slate-400 mt-1">Add meals to your library for quick logging.</p>
            </div>
          ) : (
            <div className="space-y-2">
              {meals.map(m => (
                <div key={m.id} className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 p-3.5">
                  {editMeal?.id === m.id ? (
                    <>
                      <p className="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-3">Edit meal</p>
                      <MealForm initial={m} onSave={handleSaveMeal} onCancel={() => setEditMeal(null)} loading={addLoading} />
                    </>
                  ) : (
                    <div className="flex items-start justify-between gap-3">
                      <div className="min-w-0">
                        <p className="font-medium text-slate-800 dark:text-slate-100">{m.name}</p>
                        <div className="flex flex-wrap gap-2 mt-1 text-xs text-slate-500 dark:text-slate-400">
                          <span className="font-semibold text-slate-700 dark:text-slate-300">{m.calories} kcal</span>
                          {m.protein_g != null && <span>P: {fmtNum(m.protein_g, 1)}g</span>}
                          {m.carbs_g   != null && <span>C: {fmtNum(m.carbs_g, 1)}g</span>}
                          {m.fat_g     != null && <span>F: {fmtNum(m.fat_g, 1)}g</span>}
                        </div>
                        {m.notes && <p className="text-xs text-slate-400 mt-1 italic">{m.notes}</p>}
                      </div>
                      <div className="flex gap-1.5 shrink-0">
                        <button onClick={() => setLogMeal(m)} className={BTN_PRIMARY}>Log</button>
                        <button onClick={() => { setEditMeal(m); setShowAdd(false); }} className={BTN_GHOST}>Edit</button>
                        <button
                          onClick={() => handleDeleteMeal(m.id)}
                          disabled={deletingId === m.id}
                          className={BTN_DANGER}
                        >
                          {deletingId === m.id ? "…" : "Delete"}
                        </button>
                      </div>
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {/* Log modal (custom entry or a chosen template) */}
      {logMeal && (
        <LogModal
          meal={logMeal === "custom" ? null : logMeal}
          onLog={handleLog}
          onClose={() => setLogMeal(null)}
        />
      )}
    </div>
  );
}
