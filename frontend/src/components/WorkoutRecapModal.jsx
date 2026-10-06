// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * WorkoutRecapModal — post-workout animation feedback.
 *
 * Opens after the user has synced one or more workouts that contain
 * animatable exercises. Shows each animatable (cat, subtype) once, with
 * three buttons per row:
 *
 *   Yes             — animates on the user's primary device
 *   No              — doesn't animate
 *   Don't remember  — skipped; no confirmation written for this entry,
 *                      but the workout still gets marked done so we stop
 *                      asking about it.
 *
 * When multiple workouts are pending, the modal walks them one at a time
 * (Next button at the bottom advances; Skip dismisses the whole workout).
 *
 * Pre-fills from the user's existing confirmations for each pair so that
 * if they've already voted (via the thumbs on the cards), we don't overwrite
 * their answer unless they explicitly change it in this flow.
 */
import { useEffect, useState } from "react";
import { api } from "../api/client";


const VERDICT = {
  YES: true,
  NO:  false,
  IDK: null,
};

function _verdictFromState(state) {
  if (state === "confirmed_yes") return VERDICT.YES;
  if (state === "confirmed_no")  return VERDICT.NO;
  return undefined;        // null vs undefined matters: undefined = unanswered
}


function VerdictButton({ active, color, onClick, label, disabled }) {
  const colorMap = {
    accent: {
      active: "bg-accent-500 text-white border-accent-500",
      inactive: "border-slate-300 dark:border-slate-600 text-slate-600 dark:text-slate-300 hover:border-accent-400 hover:text-accent-500",
    },
    rose: {
      active: "bg-rose-500 text-white border-rose-500",
      inactive: "border-slate-300 dark:border-slate-600 text-slate-600 dark:text-slate-300 hover:border-rose-400 hover:text-rose-500",
    },
    slate: {
      active: "bg-slate-500 text-white border-slate-500",
      inactive: "border-slate-300 dark:border-slate-600 text-slate-600 dark:text-slate-300 hover:border-slate-400",
    },
  };
  const c = colorMap[color];
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className={`flex-1 px-1.5 py-1.5 rounded-lg text-xs font-medium border transition-colors flex items-center justify-center gap-1.5 disabled:opacity-50 ${
        active ? c.active : c.inactive
      }`}
    >
      {label}
    </button>
  );
}


export default function WorkoutRecapModal({ workoutIds, onClose, onAllDone }) {
  // workoutIds: ordered list of planned-workout ids to walk through.
  // The modal manages its own progress; parent just receives a done callback.

  const [cursor, setCursor]   = useState(0);
  const [data,   setData]     = useState(null);     // current workout detail
  const [answers, setAnswers] = useState({});       // (cat:sub) → bool|null
  const [loading, setLoading] = useState(true);
  const [saving, setSaving]   = useState(false);
  const [error,  setError]    = useState(null);

  const currentId = workoutIds[cursor];

  // Load the detail for the current workout when cursor advances.
  useEffect(() => {
    if (currentId == null) return;
    setLoading(true);
    setError(null);
    api.getRecap(currentId)
      .then(r => {
        setData(r);
        // Pre-fill: copy any confirmed_yes/no states into the answers map.
        const pre = {};
        for (const it of (r.items || [])) {
          const key = `${it.garmin_category}:${it.garmin_subtype}`;
          const v = _verdictFromState(it.animation_state);
          if (v !== undefined) pre[key] = v;
        }
        setAnswers(pre);
      })
      .catch(e => setError(e?.message || "Failed to load recap"))
      .finally(() => setLoading(false));
  }, [currentId]);

  const setAnswer = (cat, sub, value) => {
    setAnswers(prev => ({ ...prev, [`${cat}:${sub}`]: value }));
  };

  const advance = async (submit) => {
    if (!data) return;
    setSaving(true);
    setError(null);
    try {
      if (submit) {
        const items = (data.items || []).map(it => ({
          garmin_category: it.garmin_category,
          garmin_subtype:  it.garmin_subtype,
          animates:        answers[`${it.garmin_category}:${it.garmin_subtype}`] ?? null,
        }));
        await api.submitRecap(currentId, items);
      } else {
        await api.dismissRecap(currentId);
      }
      // Advance or finish
      if (cursor + 1 < workoutIds.length) {
        setCursor(cursor + 1);
      } else {
        onAllDone?.();
        onClose?.();
      }
    } catch (e) {
      setError(e?.message || "Failed to save");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="modal-backdrop"
         onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="modal max-w-lg max-h-[90vh] flex flex-col">

        {/* Header */}
        <div className="flex items-start justify-between px-4 pt-4 pb-2.5 border-b border-slate-100 dark:border-slate-800">
          <div className="flex-1 min-w-0">
            <p className="text-[10px] font-semibold uppercase tracking-wider text-slate-400 dark:text-slate-500 mb-0.5">
              Workout recap {workoutIds.length > 1 ? `· ${cursor + 1} of ${workoutIds.length}` : ""}
            </p>
            <h2 className="modal-title truncate">
              {data?.title || (loading ? "Loading…" : "Workout")}
            </h2>
            {data?.scheduled_date && (
              <p className="text-[11px] text-slate-400 dark:text-slate-500">
                {data.scheduled_date} · {data.workout_type}
              </p>
            )}
          </div>
          <button onClick={onClose}
            className="icon-btn">×</button>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-y-auto px-4 py-3.5 space-y-4">
          {!data?.primary_device_set && !loading && (
            <div className="rounded-lg border border-amber-300 dark:border-amber-700 bg-amber-50 dark:bg-amber-900/20 px-2.5 py-1.5 text-xs text-amber-700 dark:text-amber-300">
              Set a primary device in Settings → Devices before answering.
              Without it, your verdicts can't be saved against a specific watch.
            </div>
          )}

          <p className="text-sm text-slate-600 dark:text-slate-300">
            For each exercise below, did the watch play an animation?
            <span className="block text-[11px] text-slate-400 dark:text-slate-500 mt-1">
              Choose <em>I don't remember</em> for any you can't recall — the workout
              still gets marked done so we won't ask again.
            </span>
          </p>

          {loading && (
            <div className="flex justify-center py-5">
              <div className="spinner" />
            </div>
          )}

          {!loading && (data?.items || []).map(it => {
            const key = `${it.garmin_category}:${it.garmin_subtype}`;
            const v = answers[key];
            return (
              <div key={key} className="rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800/40 p-2.5">
                <p className="text-sm font-medium text-slate-800 dark:text-slate-200 mb-2">
                  {it.name}
                </p>
                <p className="text-[10px] text-slate-400 dark:text-slate-500 font-mono mb-2">
                  {it.garmin_category} · {it.garmin_subtype}
                </p>
                <div className="flex items-center gap-1.5">
                  <VerdictButton
                    active={v === true} color="accent"
                    label="Yes"
                    onClick={() => setAnswer(it.garmin_category, it.garmin_subtype, true)}
                    disabled={!data?.primary_device_set}
                  />
                  <VerdictButton
                    active={v === false} color="rose"
                    label="No"
                    onClick={() => setAnswer(it.garmin_category, it.garmin_subtype, false)}
                    disabled={!data?.primary_device_set}
                  />
                  <VerdictButton
                    active={v === null} color="slate"
                    label="I don't remember"
                    onClick={() => setAnswer(it.garmin_category, it.garmin_subtype, null)}
                  />
                </div>
              </div>
            );
          })}

          {!loading && (data?.items || []).length === 0 && (
            <p className="text-sm text-slate-400 dark:text-slate-500 italic text-center py-3.5">
              No animatable exercises in this workout. Skip to dismiss.
            </p>
          )}

          {error && <p className="text-sm text-rose-500">{error}</p>}
        </div>

        {/* Footer */}
        <div className="flex items-center justify-between gap-2 px-4 py-2.5 border-t border-slate-100 dark:border-slate-800">
          <button type="button" onClick={() => advance(false)} disabled={saving}
            className="btn btn-neutral btn-sm">
            Skip workout
          </button>
          <button type="button" onClick={() => advance(true)} disabled={saving || loading}
            className="btn btn-primary">
            {saving ? "Saving…" : (cursor + 1 < workoutIds.length ? "Save & next" : "Save & finish")}
          </button>
        </div>
      </div>
    </div>
  );
}
