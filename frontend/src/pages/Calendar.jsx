// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Training Calendar page. Orchestrates the event-goal → generated training plan
// view: loads goals + settings, lets the user pick an event goal and generate
// its plan, renders the plan as a month grid, and opens a per-workout detail
// panel (with a mark-complete action) when a workout cell is clicked. Also
// exposes an .ics subscription link for the selected goal.
//
// The presentational pieces (goal selector, month-grid cell content, workout
// detail panel + step rows, ics copy button) plus the colour/format constants
// live in components/calendar/* — this file owns the page shell and all the
// tightly-coupled data/state wiring (loading, generation, mark-complete,
// month navigation, and the workouts-by-date index the grid reads).

import { useEffect, useState, useMemo } from "react";
import { api } from "../api/client";
import {
  WORKOUT_COLORS, WORKOUT_DEFAULT, MONTH_NAMES, DOW,
  isoDate, monthDates,
} from "../components/calendar/constants";
import GoalSelector from "../components/calendar/GoalSelector";
import { hasPlan } from "../components/goals/helpers";
import IcsExport from "../components/calendar/IcsExport";
import WorkoutDetail from "../components/calendar/WorkoutDetail";
import { todayDate } from "../lib/today";

export default function CalendarPage() {
  const today = todayDate();
  const [year, setYear] = useState(today.getFullYear());
  const [month, setMonth] = useState(today.getMonth());
  const [goals, setGoals] = useState([]);
  const [selectedGoalId, setSelectedGoalId] = useState(null);
  const [plan, setPlan] = useState(null);
  const [generating, setGenerating] = useState(false);
  const [selected, setSelected] = useState(null); // selected workout for detail panel
  const [error, setError] = useState(null);
  const [settings, setSettings] = useState(null);
  const imperial = settings?.units === "imperial";

  // Load goals and settings on mount. Auto-select the first active event goal.
  useEffect(() => {
    api.getSettings().then(setSettings).catch(() => {});
    api.getGoals().then(gs => {
      setGoals(gs);
      const first = gs.find(g => hasPlan(g) && g.is_active);
      if (first) setSelectedGoalId(first.id);
    }).catch(() => {});
  }, []);

  // Load plan when goal changes. A 404 just means "no plan yet" (not an error).
  useEffect(() => {
    if (!selectedGoalId) { setPlan(null); return; }
    api.getPlan(selectedGoalId).then(p => { setPlan(p); setError(null); }).catch(e => {
      if (e.status === 404) { setPlan(null); setError(null); }
      else setError("Failed to load plan.");
    });
  }, [selectedGoalId]);

  const handleGenerate = async (goalId) => {
    setGenerating(true);
    setError(null);
    try {
      const p = await api.generatePlan(goalId);
      setPlan(p);
    } catch {
      setError("Plan generation failed. Check the goal has a future event date.");
    } finally {
      setGenerating(false);
    }
  };

  // Toggle a workout's completion, patch it into the plan in place, and keep the
  // detail panel showing the freshly-updated workout.
  const handleMarkComplete = async (workout) => {
    const updated = await api.updateWorkout(workout.id, { is_complete: !workout.is_complete });
    setPlan(prev => prev ? {
      ...prev,
      workouts: prev.workouts.map(w => w.id === updated.id ? updated : w),
    } : prev);
    setSelected(updated);
  };

  // Index workouts by date string for O(1) lookup while rendering the grid.
  const workoutsByDate = useMemo(() => {
    const map = {};
    for (const w of plan?.workouts ?? []) {
      if (!map[w.scheduled_date]) map[w.scheduled_date] = [];
      map[w.scheduled_date].push(w);
    }
    return map;
  }, [plan]);

  const calDays = useMemo(() => monthDates(year, month), [year, month]);
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
    <div className="p-5 max-w-7xl mx-auto">
      {/* Header */}
      <div className="flex items-center justify-between gap-4 mb-6 flex-wrap">
        <h1 className="text-xl font-bold text-slate-900 dark:text-white">Training Calendar</h1>
        <GoalSelector
          goals={goals}
          selectedId={selectedGoalId}
          onSelect={setSelectedGoalId}
          onGenerate={handleGenerate}
          generating={generating}
        />
      </div>

      {error && (
        <div className="mb-4 px-3.5 py-2.5 rounded-lg bg-red-50 dark:bg-red-900/20 text-red-700 dark:text-red-400 text-sm border border-red-200 dark:border-red-800">
          {error}
        </div>
      )}

      {plan && !error && (
        <div className="mb-4 flex items-center justify-between flex-wrap gap-3">
          <div className="text-sm text-slate-500 dark:text-slate-400">
            {plan.workouts.length} workouts · {plan.vdot ? `VDOT ${plan.vdot}` : "Estimated pacing (no runs measured yet)"}
          </div>
          {selectedGoalId && <IcsExport goalId={selectedGoalId} />}
        </div>
      )}

      {!plan && !generating && selectedGoalId && (
        <div className="mb-4 px-3.5 py-2.5 rounded-lg bg-slate-50 dark:bg-slate-800/50 text-slate-500 dark:text-slate-400 text-sm border border-slate-200 dark:border-slate-700">
          No plan generated yet. Click <strong>Generate Plan</strong> to build your training schedule.
        </div>
      )}

      <div className={`flex gap-4 ${selected ? "lg:grid lg:grid-cols-[1fr_360px]" : ""}`}>
        {/* Calendar grid */}
        <div className="flex-1 min-w-0">
          {/* Month nav */}
          <div className="flex items-center justify-between mb-3">
            <button onClick={prevMonth} className="p-1 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 text-slate-500 dark:text-slate-400">
              <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" d="M15 19l-7-7 7-7" />
              </svg>
            </button>
            <span className="text-sm font-semibold text-slate-800 dark:text-slate-100">
              {MONTH_NAMES[month]} {year}
            </span>
            <button onClick={nextMonth} className="p-1 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 text-slate-500 dark:text-slate-400">
              <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" d="M9 5l7 7-7 7" />
              </svg>
            </button>
          </div>

          {/* Day-of-week headers */}
          <div className="grid grid-cols-7 mb-1">
            {DOW.map(d => (
              <div key={d} className="text-center text-xs font-medium text-slate-400 dark:text-slate-500 py-1">
                {d}
              </div>
            ))}
          </div>

          {/* Days grid. Each cell reads the pre-indexed workoutsByDate map; days
              outside the current month are dimmed, today gets an accent chip. */}
          <div className="grid grid-cols-7 gap-px bg-slate-200 dark:bg-slate-700 rounded-xl overflow-hidden border border-slate-200 dark:border-slate-700">
            {calDays.map((d, i) => {
              const ds = isoDate(d);
              const inMonth = d.getMonth() === month;
              const isToday = ds === todayStr;
              const workouts = workoutsByDate[ds] ?? [];

              return (
                <div
                  key={i}
                  className={`bg-white dark:bg-slate-900 min-h-[80px] p-1 ${!inMonth ? "opacity-35" : ""}`}
                >
                  <div className={`text-xs font-medium w-6 h-6 flex items-center justify-center rounded-full mb-1 ${
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
                        onClick={() => setSelected(w)}
                        title={w.title}
                        className={`w-full text-left px-1 py-0.5 rounded text-xs font-medium border truncate leading-tight transition-opacity hover:opacity-80 ${
                          WORKOUT_COLORS[w.workout_type] ?? WORKOUT_DEFAULT
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

        {/* Detail panel (only mounted when a workout is selected) */}
        {selected && (
          <div className="card p-0 lg:w-[360px] overflow-hidden shrink-0">
            <WorkoutDetail
              workout={selected}
              onClose={() => setSelected(null)}
              onMarkComplete={handleMarkComplete}
              imperial={imperial}
            />
          </div>
        )}
      </div>
    </div>
  );
}
