// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Training-plan calendar section: browse a goal's plan month-by-month, sync it
// to the watch, and drill into a day's workout. Owns the plan fetch,
// sync-status polling, and month navigation; the calendar cells + workout
// detail rendering delegate to plancalendar/*.
//
// There is no Regenerate button (user decision, 2026-09-28): the server
// rebuilds the plan on every edit that leaves it stale. A goal with no plan
// at all (one made on a phone that never built it, or a failed build) gets
// one built here, once, when the section opens. The subscription link lives
// in the goal's edit form.

import { useEffect, useState, useMemo, useRef } from "react";
import { api } from "../api/client";
import {
  workoutColor, isoDate, monthDates, MONTH_NAMES, DOW,
} from "./plancalendar/constants";
import WorkoutDetail from "./plancalendar/WorkoutDetail";

export default function PlanCalendarSection({ goalId, refreshKey, imperial = false }) {
  const today = new Date();
  const [year,       setYear]       = useState(today.getFullYear());
  const [month,      setMonth]      = useState(today.getMonth());
  const [plan,       setPlan]       = useState(null);
  const [loading,    setLoading]    = useState(true);
  const [generating, setGenerating] = useState(false);
  const [selected,   setSelected]   = useState(null);
  const [error,      setError]      = useState(null);
  const [syncing,    setSyncing]    = useState(false);
  const [syncStatus, setSyncStatus] = useState(null); // null | "pending" | "ok" | "error"
  const syncPollRef = useRef(null);

  useEffect(() => {
    if (!goalId) { setLoading(false); return; }
    setLoading(true);
    api.getPlan(goalId)
      .then(p => { setPlan(p); setError(null); })
      .catch(e => {
        if (e.status === 404) buildMissing();
        else setError("Failed to load plan.");
      })
      .finally(() => setLoading(false));
  }, [goalId, refreshKey]);

  // Once per goal: a build the server refuses (a race already run) must not
  // be retried on every refresh.
  const triedBuild = useRef(null);
  const buildMissing = async () => {
    if (triedBuild.current === goalId) return;
    triedBuild.current = goalId;
    setGenerating(true);
    setError(null);
    try {
      setPlan(await api.generatePlan(goalId));
    } catch {
      setError("Could not build a plan. Check the goal has a future event date.");
    } finally {
      setGenerating(false);
    }
  };

  const handleSync = async () => {
    if (syncPollRef.current) clearInterval(syncPollRef.current);
    setSyncing(true);
    setSyncStatus(null);
    try {
      await api.triggerGarminSync();
      setSyncStatus("pending");
      const triggerTime = Date.now();
      syncPollRef.current = setInterval(async () => {
        try {
          const status = await api.getSyncStatus();
          if (status.last_synced_at) {
            const syncedAt = new Date(status.last_synced_at).getTime();
            if (syncedAt >= triggerTime) {
              clearInterval(syncPollRef.current);
              syncPollRef.current = null;
              setSyncStatus("ok");
              setTimeout(() => setSyncStatus(null), 5000);
            }
          }
          // Give up after 2 minutes (watch may not be connected)
          if (Date.now() - triggerTime > 120_000) {
            clearInterval(syncPollRef.current);
            syncPollRef.current = null;
            setSyncStatus(null);
          }
        } catch { /* ignore transient poll errors */ }
      }, 2000);
    } catch {
      setSyncStatus("error");
      setTimeout(() => setSyncStatus(null), 3000);
    } finally {
      setSyncing(false);
    }
  };

  const handleMarkComplete = async (workout) => {
    try {
      const updated = await api.updateWorkout(workout.id, { is_complete: !workout.is_complete });
      setPlan(prev => prev ? {
        ...prev,
        workouts: prev.workouts.map(w => w.id === updated.id ? updated : w),
      } : prev);
      setSelected(updated);
    } catch {}
  };

  // After the guided runner/flow player logs a session the workout is already
  // complete server-side — refetch the plan so the calendar reflects it.
  const handleSessionLogged = async () => {
    try {
      const p = await api.getPlan(goalId);
      setPlan(p);
      setSelected(null);
    } catch {}
  };

  const workoutsByDate = useMemo(() => {
    const map = {};
    for (const w of plan?.workouts ?? []) {
      if (!map[w.scheduled_date]) map[w.scheduled_date] = [];
      map[w.scheduled_date].push(w);
    }
    return map;
  }, [plan]);

  const calDays  = useMemo(() => monthDates(year, month), [year, month]);
  const todayStr = isoDate(today);

  const prevMonth = () => {
    if (month === 0) { setYear(y => y - 1); setMonth(11); }
    else setMonth(m => m - 1);
  };
  const nextMonth = () => {
    if (month === 11) { setYear(y => y + 1); setMonth(0); }
    else setMonth(m => m + 1);
  };

  return (
    <div data-tour="goals-plan" className="card mt-3">
      {/* Header */}
      <div className="flex items-center justify-between gap-3 mb-4 flex-wrap">
        <div className="flex items-center gap-3 flex-wrap">
          <h4 className="section-title">
            Training Plan
          </h4>
          {plan && (
            <span className="text-xs text-slate-400 dark:text-slate-500">
              {plan.workouts.length} workouts{plan.vdot ? ` · VDOT ${plan.vdot}` : ""}
            </span>
          )}
        </div>
        <div className="flex items-center gap-2 flex-wrap">
          {plan && (
            <button
              onClick={handleSync}
              disabled={syncing}
              className={`btn btn-sm ${syncStatus === "error" ? "btn-danger" : syncStatus === "pending" ? "btn-neutral" : "btn-tonal"}`}
            >
              {syncing ? "Requesting…" : syncStatus === "ok" ? "Synced ✓" : syncStatus === "pending" ? "Syncing…" : syncStatus === "error" ? "Failed" : "Sync to Watch"}
            </button>
          )}
        </div>
      </div>

      {error && (
        <div className="mb-3 px-2.5 py-1.5 rounded-lg bg-red-50 dark:bg-red-900/20 text-red-700 dark:text-red-400 text-sm border border-red-200 dark:border-red-800">
          {error}
        </div>
      )}

      {loading && (
        <div className="py-3.5 text-center text-sm text-slate-400 dark:text-slate-500">Loading…</div>
      )}

      {generating && (
        <div className="py-3.5 text-center text-sm text-slate-400 dark:text-slate-500">Building the plan…</div>
      )}

      {plan && (
        <div className={selected ? "lg:grid lg:grid-cols-[1fr_340px] gap-4" : ""}>
          {/* Calendar */}
          <div className="min-w-0">
            <div className="flex items-center justify-between mb-2">
              <button onClick={prevMonth}
                className="p-1 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 text-slate-400 dark:text-slate-500">
                <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M15 19l-7-7 7-7" />
                </svg>
              </button>
              <span className="text-sm font-semibold text-slate-800 dark:text-slate-100">
                {MONTH_NAMES[month]} {year}
              </span>
              <button onClick={nextMonth}
                className="p-1 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 text-slate-400 dark:text-slate-500">
                <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M9 5l7 7-7 7" />
                </svg>
              </button>
            </div>

            <div className="grid grid-cols-7 mb-1">
              {DOW.map(d => (
                <div key={d} className="text-center text-xs font-medium text-slate-400 dark:text-slate-500 py-1">{d}</div>
              ))}
            </div>

            <div className="grid grid-cols-7 gap-px bg-slate-200 dark:bg-slate-700 rounded-xl overflow-hidden border border-slate-200 dark:border-slate-700">
              {calDays.map((d, i) => {
                const ds       = isoDate(d);
                const inMonth  = d.getMonth() === month;
                const isToday  = ds === todayStr;
                const workouts = workoutsByDate[ds] ?? [];

                return (
                  <div
                    key={i}
                    className={`bg-white dark:bg-slate-900 min-h-[72px] p-1 ${!inMonth ? "opacity-30" : ""}`}
                  >
                    <div className={`text-xs font-medium w-5 h-5 flex items-center justify-center rounded-full mb-1 ${
                      isToday
                        ? "bg-accent-600 text-white"
                        : "text-slate-500 dark:text-slate-400"
                    }`}>
                      {d.getDate()}
                    </div>
                    <div className="space-y-0.5">
                      {workouts.map(w => (
                        <button
                          key={w.id}
                          onClick={() => setSelected(selected?.id === w.id ? null : w)}
                          title={w.title}
                          className={`w-full text-left px-1 py-0.5 rounded text-xs font-medium border truncate leading-tight transition-opacity hover:opacity-80 ${
                            workoutColor(w)
                          } ${w.is_complete ? "opacity-50 line-through" : ""}`}
                        >
                          {w.title.replace(/ — .*/, "")}
                        </button>
                      ))}
                    </div>
                  </div>
                );
              })}
            </div>
          </div>

          {/* Detail panel */}
          {selected && (
            <div className="mt-3 lg:mt-0 bg-slate-50 dark:bg-slate-800/50 rounded-xl border border-slate-200 dark:border-slate-700 overflow-hidden">
              <WorkoutDetail
                workout={selected}
                onClose={() => setSelected(null)}
                onMarkComplete={handleMarkComplete}
                onSessionLogged={handleSessionLogged}
                imperial={imperial}
              />
            </div>
          )}
        </div>
      )}
    </div>
  );
}
