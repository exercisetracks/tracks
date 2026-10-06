// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Slide-in detail panel for a single planned workout: header (type badge +
// date), title/summary, description, the ordered step list, and a "mark
// complete" action (hidden for races). Presentational — the parent owns the
// selected workout and the complete/close callbacks.

import { useState } from "react";
import { workoutColor, fmtDur, fmtDist } from "./constants";
import StepRow from "./StepRow";
import FlowPlayer from "../player/FlowPlayer";
import StrengthRunner from "../player/StrengthRunner";

const PHASE_ORDER = ["warmup", "main", "finisher"];
const PHASE_LABEL = { warmup: "Warm-up", main: "Main", finisher: "Finisher" };

export default function WorkoutDetail({ workout, onClose, onMarkComplete, onSessionLogged, imperial = false }) {
  const colorClass = workoutColor(workout);
  const steps = workout.steps ?? [];
  const isStrength = workout.workout_type === "strength" || workout.workout_type === "mobility";
  // Which guided player is open, if any.
  const [player, setPlayer] = useState(null); // null | "strength" | "flow"

  // A generated session is "runnable" when it has strength or mobility steps.
  const hasStrengthSteps = steps.some((s) => s.type === "strength_exercise");
  const hasMobilitySteps = steps.some((s) => s.type === "mobility_exercise");
  const runnable = hasStrengthSteps || hasMobilitySteps;

  // Archetype sessions tag each step with a phase — group them for display.
  const hasPhases = steps.some((s) => s.phase && s.phase !== "main");
  const phaseGroups = steps.reduce((acc, s, i) => {
    const p = s.phase || "main";
    (acc[p] ||= []).push({ ...s, _i: i });
    return acc;
  }, {});

  if (player === "strength") {
    return (
      <StrengthRunner
        workout={workout}
        imperial={imperial}
        onClose={() => setPlayer(null)}
        onLogged={() => { setPlayer(null); onSessionLogged?.(); }}
      />
    );
  }
  if (player === "flow") {
    return (
      <FlowPlayer
        title={workout.title}
        steps={steps.filter((s) => s.type === "mobility_exercise")}
        onClose={() => setPlayer(null)}
        onComplete={() => { if (!workout.is_complete) onMarkComplete?.(workout); }}
      />
    );
  }

  return (
    <div className="h-full flex flex-col">
      <div className="flex items-center justify-between p-3.5 border-b border-slate-200 dark:border-slate-700">
        <div className="flex items-center gap-2 min-w-0">
          <span className={`text-xs font-semibold px-1.5 py-0.5 rounded-full border capitalize ${colorClass}`}>
            {workout.workout_type.replace(/_/g, " ")}
          </span>
          <span className="text-xs text-slate-400 dark:text-slate-500">
            {new Date(workout.scheduled_date + "T00:00:00").toLocaleDateString(undefined, {
              weekday: "short", month: "short", day: "numeric",
            })}
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
            {workout.distance_meters  && <span>{fmtDist(workout.distance_meters, imperial)}</span>}
          </div>
        </div>

        {workout.description && (
          <p className="text-sm text-slate-600 dark:text-slate-300 leading-relaxed">{workout.description}</p>
        )}

        {steps.length > 0 && (
          hasPhases ? (
            // Archetype sessions group their steps into Warm-up / Main / Finisher.
            PHASE_ORDER.filter(p => phaseGroups[p]?.length).map(p => (
              <div key={p}>
                <p className="text-xs font-semibold text-slate-400 dark:text-slate-500 uppercase tracking-wider mb-1">
                  {PHASE_LABEL[p]}
                </p>
                <div className="divide-y divide-slate-100 dark:divide-slate-800">
                  {phaseGroups[p].map((s) => <StepRow key={s._i} step={s} imperial={imperial} />)}
                </div>
              </div>
            ))
          ) : (
            <div>
              <p className="text-xs font-semibold text-slate-400 dark:text-slate-500 uppercase tracking-wider mb-1">
                {isStrength ? "Exercises" : "Workout Structure"}
              </p>
              <div className="divide-y divide-slate-100 dark:divide-slate-800">
                {steps.map((s, i) => <StepRow key={i} step={s} imperial={imperial} />)}
              </div>
            </div>
          )
        )}
      </div>

      {workout.workout_type !== "race" && (
        <div className="p-3.5 border-t border-slate-200 dark:border-slate-700 space-y-2">
          {runnable && !workout.is_complete && (
            <button
              onClick={() => setPlayer(hasStrengthSteps ? "strength" : "flow")}
              className="btn btn-primary w-full"
            >
              {hasStrengthSteps ? "Start session" : "Start flow"}
            </button>
          )}
          <button
            onClick={() => onMarkComplete(workout)}
            className={`btn w-full ${workout.is_complete ? "btn-tonal" : runnable ? "btn-neutral" : "btn-primary"}`}
          >
            {workout.is_complete ? "Marked complete ✓" : "Mark as complete"}
          </button>
        </div>
      )}
    </div>
  );
}
