// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Side detail panel for a single planned workout: header (type badge + date),
// title/summary, the ordered step list (or a plain description fallback), and a
// "mark complete" action (hidden for races). Presentational — the parent owns
// the selected workout and supplies the close / mark-complete callbacks.

import { WORKOUT_COLORS, WORKOUT_DEFAULT, fmtDur, fmtDist } from "./constants";
import StepRow from "./StepRow";

export default function WorkoutDetail({ workout, onClose, onMarkComplete, imperial = false }) {
  const colorClass = WORKOUT_COLORS[workout.workout_type] ?? WORKOUT_DEFAULT;
  const steps = workout.steps ?? [];

  return (
    <div className="h-full flex flex-col">
      <div className="flex items-center justify-between p-3.5 border-b border-slate-200 dark:border-slate-700">
        <div className="flex items-center gap-2 min-w-0">
          <span className={`text-xs font-semibold px-1.5 py-0.5 rounded-full border capitalize ${colorClass}`}>
            {workout.workout_type.replace("_", " ")}
          </span>
          <span className="text-xs text-slate-400 dark:text-slate-500">
            {new Date(workout.scheduled_date + "T00:00:00").toLocaleDateString(undefined, {weekday:"short", month:"short", day:"numeric"})}
          </span>
        </div>
        <button onClick={onClose} className="p-1 rounded hover:bg-slate-100 dark:hover:bg-slate-800 text-slate-400">
          <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      <div className="flex-1 overflow-y-auto p-3.5 space-y-4">
        <div>
          <h3 className="text-base font-semibold text-slate-900 dark:text-white">{workout.title}</h3>
          <div className="flex gap-3 mt-1 text-xs text-slate-500 dark:text-slate-400">
            {workout.duration_minutes && <span>{fmtDur(workout.duration_minutes)}</span>}
            {workout.distance_meters && <span>{fmtDist(workout.distance_meters, imperial)}</span>}
          </div>
        </div>

        {steps.length > 0 && (
          <div>
            <p className="text-xs font-semibold text-slate-400 dark:text-slate-500 uppercase tracking-wider mb-1">Workout Structure</p>
            <div className="divide-y divide-slate-100 dark:divide-slate-800">
              {steps.map((s, i) => <StepRow key={i} step={s} imperial={imperial} />)}
            </div>
          </div>
        )}

        {/* Fallback: free-text description when the workout has no structured steps. */}
        {workout.description && steps.length === 0 && (
          <p className="text-sm text-slate-600 dark:text-slate-300 whitespace-pre-line">{workout.description}</p>
        )}
      </div>

      {workout.workout_type !== "race" && (
        <div className="p-3.5 border-t border-slate-200 dark:border-slate-700">
          <button
            onClick={() => onMarkComplete(workout)}
            className={`btn w-full ${workout.is_complete ? "btn-tonal" : "btn-primary"}`}
          >
            {workout.is_complete ? "Marked complete" : "Mark as complete"}
          </button>
        </div>
      )}
    </div>
  );
}
