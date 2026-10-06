// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Guided strength session runner: walks the user through each exercise of a
// generated strength workout, one at a time, with the animation slot, coaching
// cues, a set checklist prefilled from the prescription, an auto rest timer,
// and an end-of-session RPE capture. Logging feeds the progression loop via
// POST /workouts/sessions with the planned_workout_id.
import { useMemo, useState } from "react";
import { api } from "../../api/client";
import { kgToDisplay, displayToKg, weightUnit } from "../../lib/weight";
import MovementSlot from "./MovementSlot";
import { useCountdown } from "./usePlayerTimer";
import { CompletionScreen } from "./FlowPlayer";
import { useWakeLock } from "./useWakeLock";

// Build the initial editable set-grid from the workout's strength steps.
function initSets(exercises, imperial) {
  return exercises.map((ex) =>
    Array.from({ length: Math.max(1, ex.sets || 1) }, () => ({
      done: false,
      weight: kgToDisplay(ex.weight_kg, imperial),
      reps: ex.reps || 0,
      rpe: "",
    })),
  );
}

export default function StrengthRunner({ workout, imperial, onClose, onLogged }) {
  const exercises = useMemo(
    () => (workout.steps || []).filter((s) => s.type === "strength_exercise"),
    [workout.steps],
  );
  const [exIdx, setExIdx] = useState(0);
  const [grid, setGrid] = useState(() => initSets(exercises, imperial));
  const [phase, setPhase] = useState("run"); // run | summary | done
  const [sessionRpe, setSessionRpe] = useState(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  const rest = useCountdown();
  useWakeLock(phase === "run");

  const ex = exercises[exIdx];
  const sets = grid[exIdx] || [];
  const unit = weightUnit(imperial);
  const nextEx = exercises[exIdx + 1];

  if (exercises.length === 0) {
    return (
      <Overlay title={workout.title} onClose={onClose}>
        <div className="flex-1 flex items-center justify-center text-slate-500">
          This workout has no strength exercises to run.
        </div>
      </Overlay>
    );
  }

  function patchSet(si, changes) {
    setGrid((g) => g.map((exSets, i) =>
      i !== exIdx ? exSets : exSets.map((s, j) => (j === si ? { ...s, ...changes } : s)),
    ));
  }

  function toggleDone(si) {
    const nowDone = !sets[si].done;
    patchSet(si, { done: nowDone });
    if (nowDone && ex.rest_seconds) rest.start(ex.rest_seconds);
  }

  async function submit() {
    setSaving(true);
    setError(null);
    try {
      const payload = {
        planned_workout_id: workout.id,
        session_rpe: sessionRpe,
        exercises: exercises.map((e, i) => ({
          exercise_name: e.name,
          sets: (grid[i] || [])
            .filter((s) => s.done && Number(s.reps) > 0)
            .map((s) => ({
              weight_kg: displayToKg(s.weight, imperial),
              reps: Number(s.reps),
              rpe: s.rpe ? Number(s.rpe) : null,
            })),
        })).filter((e) => e.sets.length > 0),
      };
      await api.logWorkoutSession(payload);
      setPhase("done");
      onLogged?.();
    } catch (e) {
      setError(e?.message || "Could not save your session.");
    } finally {
      setSaving(false);
    }
  }

  if (phase === "done") {
    return (
      <Overlay title={workout.title} onClose={onClose}>
        <CompletionScreen onClose={onClose} label="Session logged — great work." />
      </Overlay>
    );
  }

  if (phase === "summary") {
    return (
      <Overlay title={workout.title} onClose={onClose}>
        <div className="flex-1 flex flex-col items-center justify-center gap-5 px-5 max-w-sm mx-auto w-full text-center">
          <h2 className="text-lg font-bold text-slate-800 dark:text-white">How hard was that session?</h2>
          <p className="text-sm text-slate-500 dark:text-slate-400">Rate your overall effort (RPE).</p>
          <div className="grid grid-cols-5 gap-2 w-full">
            {Array.from({ length: 10 }, (_, i) => i + 1).map((n) => (
              <button key={n} type="button" aria-pressed={sessionRpe === n} onClick={() => setSessionRpe(n)}
                className="chip h-11 font-semibold tabular-nums">{n}</button>
            ))}
          </div>
          {error && <p className="text-sm text-red-500">{error}</p>}
          <div className="flex gap-3 w-full mt-2">
            <button onClick={() => setPhase("run")}
              className="btn btn-neutral flex-1">
              Back
            </button>
            <button onClick={submit} disabled={saving}
              className="btn btn-primary flex-1">
              {saving ? "Saving…" : "Finish"}
            </button>
          </div>
        </div>
      </Overlay>
    );
  }

  const allSetsDone = sets.every((s) => s.done);

  return (
    <Overlay
      title={workout.title}
      subtitle={`Exercise ${exIdx + 1} of ${exercises.length}`}
      onClose={onClose}
    >
      <div className="flex-1 overflow-y-auto px-4 py-3.5 max-w-md mx-auto w-full space-y-4">
        <div className="max-w-xs mx-auto w-full">
          <MovementSlot name={ex.name} muscles={ex.primary_muscles} />
        </div>

        <div className="text-center">
          <h2 className="text-xl font-bold text-slate-800 dark:text-white">{ex.name}</h2>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-0.5">
            {ex.sets}×{ex.reps}
            {ex.target_rpe ? ` · RPE ${ex.target_rpe}` : ""}
          </p>
        </div>

        {(ex.cues || []).length > 0 && (
          <ul className="space-y-1 bg-slate-50 dark:bg-slate-900 rounded-lg p-2.5">
            {ex.cues.slice(0, 3).map((c, i) => (
              <li key={i} className="flex gap-2 text-sm text-slate-600 dark:text-slate-300">
                <span className="text-accent-500 shrink-0">•</span><span>{c}</span>
              </li>
            ))}
          </ul>
        )}

        {/* Rest banner */}
        {rest.running && (
          <div className="flex items-center justify-between bg-accent-50 dark:bg-accent-900/20 border border-accent-200 dark:border-accent-800 rounded-lg px-3.5 py-1.5">
            <span className="text-sm font-medium text-accent-700 dark:text-accent-300">
              Rest: {rest.seconds}s
            </span>
            <button onClick={rest.stop} className="btn btn-neutral btn-sm">Skip</button>
          </div>
        )}

        {/* Set grid */}
        <div className="space-y-2">
          <div className="grid grid-cols-[2rem_1fr_1fr_1fr_2.5rem] gap-2 text-[10px] uppercase tracking-wide text-slate-400 px-1">
            <span>Set</span><span>Weight ({unit})</span><span>Reps</span><span>RPE</span><span></span>
          </div>
          {sets.map((s, si) => (
            <div key={si} className={`grid grid-cols-[2rem_1fr_1fr_1fr_2.5rem] gap-2 items-center ${s.done ? "opacity-60" : ""}`}>
              <span className="text-sm font-medium text-slate-500 tabular-nums pl-1">{si + 1}</span>
              <NumInput value={s.weight} onChange={(v) => patchSet(si, { weight: v })} />
              <NumInput value={s.reps} onChange={(v) => patchSet(si, { reps: v })} />
              <NumInput value={s.rpe} onChange={(v) => patchSet(si, { rpe: v })} placeholder="–" />
              <button onClick={() => toggleDone(si)}
                className={`w-9 h-9 rounded-lg border flex items-center justify-center text-lg ${
                  s.done
                    ? "bg-accent-500 border-accent-500 text-white"
                    : "border-slate-300 dark:border-slate-600 text-transparent hover:border-accent-400"
                }`}
                aria-label={s.done ? "Mark set not done" : "Mark set done"}>✓</button>
            </div>
          ))}
        </div>

        {nextEx && (
          <p className="text-xs text-center text-slate-400 dark:text-slate-500">Next: {nextEx.name}</p>
        )}
      </div>

      {/* Footer nav */}
      <div className="flex gap-3 px-4 py-2.5 border-t border-slate-100 dark:border-slate-800 max-w-md mx-auto w-full">
        <button onClick={() => setExIdx((i) => Math.max(0, i - 1))} disabled={exIdx === 0}
          className="btn btn-neutral">
          Back
        </button>
        {nextEx ? (
          <button onClick={() => { rest.stop(); setExIdx((i) => i + 1); }}
            className="btn btn-primary flex-1">
            {allSetsDone ? "Next exercise" : "Skip to next"}
          </button>
        ) : (
          <button onClick={() => { rest.stop(); setPhase("summary"); }}
            className="btn btn-primary flex-1">
            Finish session
          </button>
        )}
      </div>
    </Overlay>
  );
}

function Overlay({ title, subtitle, onClose, children }) {
  return (
    <div className="fixed inset-0 z-50 bg-white dark:bg-slate-950 flex flex-col">
      <div className="flex items-center justify-between px-3.5 py-2.5 border-b border-slate-100 dark:border-slate-800">
        <div className="min-w-0">
          <p className="text-sm font-semibold text-slate-700 dark:text-slate-200 truncate">{title || "Workout"}</p>
          {subtitle && <p className="text-xs text-slate-400">{subtitle}</p>}
        </div>
        <button onClick={onClose} className="icon-btn">×</button>
      </div>
      {children}
    </div>
  );
}

function NumInput({ value, onChange, placeholder }) {
  return (
    <input
      type="number"
      inputMode="decimal"
      value={value === 0 ? "" : value}
      placeholder={placeholder ?? "0"}
      onChange={(e) => onChange(e.target.value)}
      className="field field-sm text-center"
    />
  );
}
