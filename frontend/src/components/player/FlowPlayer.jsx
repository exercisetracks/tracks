// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Guided stretch-flow player: a full-screen overlay that walks the user through
// each pose with a per-pose countdown, breath cue, left/right handling and a
// next-up preview.
//
// Works from either generated `mobility_exercise` steps (a PlannedWorkout) or a
// user flow's stretch list — the caller passes a normalized `steps` array
// (lib/sessionPlan.js planFlow for a saved flow). On completion a planned
// workout is marked done by `onComplete`; a flow run from the library has no
// plan entry to tick, so `onLog(rpe)` records a workout session instead, as the
// phone does — see FlexibilityViewModel's "What finishing a session records".
import { useEffect, useMemo, useState } from "react";
import { buildFlowPhases, usePlayerTimer } from "./usePlayerTimer";
import MovementSlot from "./MovementSlot";
import CountdownRing from "./CountdownRing";
import { useWakeLock } from "./useWakeLock";

export default function FlowPlayer({ title, steps, onClose, onComplete, onLog }) {
  // Memoised: usePlayerTimer re-seeds whenever the array's identity changes,
  // and a fresh array per render would restart the flow on every tick.
  const phases = useMemo(() => buildFlowPhases(steps), [steps]);
  const holds = phases.filter((p) => !p.rest).length;
  const t = usePlayerTimer(phases, { onComplete });
  useWakeLock(!t.done);

  // Close on Escape.
  useEffect(() => {
    const onKey = (e) => { if (e.key === "Escape") onClose?.(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const nextPhase = t.index + 1 < phases.length ? phases[t.index + 1] : null;

  return (
    <div className="fixed inset-0 z-50 bg-white dark:bg-slate-950 flex flex-col">
      {/* Header */}
      <div className="flex items-center justify-between px-3.5 py-2.5 border-b border-slate-100 dark:border-slate-800">
        <div className="min-w-0">
          <p className="text-xs uppercase tracking-wide text-slate-400">{title || "Stretch Flow"}</p>
          <p className="text-sm font-semibold text-slate-700 dark:text-slate-200">
            {t.done ? "Complete" : `Pose ${Math.min(t.phase.hold + 1, holds)} of ${holds}`}
          </p>
        </div>
        <button onClick={onClose} className="icon-btn">×</button>
      </div>

      {t.done ? (
        onLog
          ? <LogFlow onLog={onLog} onClose={onClose} />
          : <CompletionScreen onClose={onClose} label="Flow complete — nicely done." />
      ) : t.phase?.rest ? (
        <div className="flex-1 flex flex-col items-center justify-center gap-6 px-5 max-w-md mx-auto w-full">
          <h2 className="text-xl font-bold text-slate-800 dark:text-white">Rest</h2>
          <CountdownRing seconds={t.remaining} total={t.phase.seconds || 1} paused={!t.running} />
          {nextPhase && (
            <p className="text-sm text-slate-500 dark:text-slate-400">
              Next: {nextPhase.name}{nextPhase.side ? ` (${nextPhase.side})` : ""}
            </p>
          )}
          <Controls t={t} />
        </div>
      ) : (
        <div className="flex-1 flex flex-col items-center justify-center gap-6 px-5 max-w-md mx-auto w-full">
          <div className="w-full max-w-xs">
            <MovementSlot name={t.phase?.name} muscles={t.phase?.muscles} side={t.phase?.side} />
          </div>

          <div className="text-center">
            <h2 className="text-xl font-bold text-slate-800 dark:text-white">{t.phase?.name}</h2>
            {t.phase?.side && (
              <p className="text-sm font-medium uppercase tracking-wide text-accent-600 dark:text-accent-400 mt-0.5">
                {t.phase.side} side
              </p>
            )}
          </div>

          <CountdownRing seconds={t.remaining} total={t.phase?.seconds || 1} paused={!t.running} />

          {t.phase?.breath_cue && (
            <p className="text-sm text-slate-500 dark:text-slate-400 italic text-center">{t.phase.breath_cue}</p>
          )}
          {(t.phase?.cues || []).slice(0, 1).map((c, i) => (
            <p key={i} className="text-sm text-slate-600 dark:text-slate-300 text-center">{c}</p>
          ))}

          {nextPhase && (
            <p className="text-xs text-slate-400 dark:text-slate-500">
              Next: {nextPhase.name}{nextPhase.side ? ` (${nextPhase.side})` : ""}
            </p>
          )}

          <Controls t={t} />
        </div>
      )}
    </div>
  );
}

function Controls({ t }) {
  return (
    <div className="flex items-center gap-4 mt-2">
      <ControlButton onClick={t.prev} label="Back" disabled={t.index === 0} />
      <button
        onClick={t.toggle}
        className="w-16 h-16 rounded-full bg-accent-500 hover:bg-accent-600 text-white text-2xl font-bold shadow-lg flex items-center justify-center"
        aria-label={t.running ? "Pause" : "Resume"}
      >
        {t.running ? "❚❚" : "▶"}
      </button>
      <ControlButton onClick={t.next} label="Skip" />
    </div>
  );
}

// The end of a library flow: an optional effort rating, then the session is
// logged. Closing without logging is allowed — a flow abandoned halfway and
// skipped to the end is not one the user did.
function LogFlow({ onLog, onClose }) {
  const [rpe, setRpe] = useState(null);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState(null);

  if (saved) return <CompletionScreen onClose={onClose} label="Flow logged — nicely done." />;

  async function save() {
    setSaving(true);
    setError(null);
    try {
      await onLog(rpe);
      setSaved(true);
    } catch (e) {
      setError(e?.message || "Could not save your session.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="flex-1 flex flex-col items-center justify-center gap-5 px-5 max-w-sm mx-auto w-full text-center">
      <p className="text-lg font-semibold text-slate-800 dark:text-white">Flow complete — nicely done.</p>
      <p className="text-sm text-slate-500 dark:text-slate-400">How hard was it? (optional)</p>
      <RpeChips value={rpe} onChange={setRpe} />
      {error && <p className="alert-error w-full">{error}</p>}
      <div className="flex gap-3 w-full mt-2">
        <button onClick={onClose} className="btn btn-neutral flex-1">Don't log</button>
        <button onClick={save} disabled={saving} className="btn btn-primary flex-1">
          {saving ? "Saving…" : "Log session"}
        </button>
      </div>
    </div>
  );
}

export function RpeChips({ value, onChange }) {
  return (
    <div className="grid grid-cols-5 gap-2 w-full">
      {Array.from({ length: 10 }, (_, i) => i + 1).map((n) => (
        <button key={n} type="button" aria-pressed={value === n} onClick={() => onChange(value === n ? null : n)}
          className="chip h-11 font-semibold tabular-nums">{n}</button>
      ))}
    </div>
  );
}

function ControlButton({ onClick, label, disabled }) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      className="btn btn-neutral"
    >
      {label}
    </button>
  );
}

export function CompletionScreen({ onClose, label, children }) {
  return (
    <div className="flex-1 flex flex-col items-center justify-center gap-5 px-5 text-center">
      <p className="text-lg font-semibold text-slate-800 dark:text-white">{label}</p>
      {children}
      <button
        onClick={onClose}
        className="btn btn-primary mt-2"
      >
        Done
      </button>
    </div>
  );
}
