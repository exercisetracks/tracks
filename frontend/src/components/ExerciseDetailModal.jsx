// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Read-only detail modal for a strength exercise from the library. Shows the
// movement pattern (colour-coded via PAT_COLOR — push/pull/hinge/squat/…),
// targeted muscle groups, instructions, and any demo animation badge. Opened
// from the Workouts/Exercises tabs.
import { muscleLabel } from "../utils/muscleGroups";
import AnimationBadge from "./AnimationBadge";
import MovementSlot from "./player/MovementSlot";
import { hasAnimation } from "../animations";

const PAT_COLOR = {
  push:      "bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300",
  pull:      "bg-violet-100 text-violet-700 dark:bg-violet-900/30 dark:text-violet-300",
  hinge:     "bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300",
  squat:     "bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-300",
  isometric: "bg-teal-100 text-teal-700 dark:bg-teal-900/30 dark:text-teal-300",
  plyometric:"bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300",
  isolation: "bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-300",
  rotation:  "bg-pink-100 text-pink-700 dark:bg-pink-900/30 dark:text-pink-300",
  carry:     "bg-lime-100 text-lime-700 dark:bg-lime-900/30 dark:text-lime-300",
};

function DifficultyDots({ level }) {
  return (
    <div className="flex gap-1">
      {[1, 2, 3].map(i => (
        <span key={i}
          className={`w-2 h-2 rounded-full ${i <= level
            ? "bg-accent-500"
            : "bg-slate-200 dark:bg-slate-600"}`}
        />
      ))}
    </div>
  );
}

function MuscleChips({ muscles, primary }) {
  if (!muscles?.length) return null;
  return (
    <div className="flex flex-wrap gap-1">
      {muscles.map(m => (
        <span key={m}
          className={`text-[11px] px-1.5 py-0.5 rounded-full font-medium ${primary
            ? "bg-accent-100 text-accent-800 dark:bg-accent-900/30 dark:text-accent-300"
            : "bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-300"}`}>
          {muscleLabel(m)}
        </span>
      ))}
    </div>
  );
}

function InstructionSteps({ text }) {
  if (!text) return null;
  const lines = text.split("\n").filter(l => l.trim());
  const steps  = lines.filter(l => /^\d+\./.test(l.trim()));
  const tips   = lines.filter(l => /^tip:/i.test(l.trim()));
  const others = lines.filter(l => !steps.includes(l) && !tips.includes(l));

  return (
    <div className="space-y-3">
      {steps.length > 0 && (
        <ol className="space-y-2">
          {steps.map((step, i) => {
            const body = step.replace(/^\d+\.\s*/, "");
            return (
              <li key={i} className="flex gap-3">
                <span className="shrink-0 w-5 h-5 rounded-full bg-accent-100 dark:bg-accent-900/30 text-accent-700 dark:text-accent-400 text-[11px] font-bold flex items-center justify-center mt-0.5">
                  {i + 1}
                </span>
                <span className="text-sm text-slate-700 dark:text-slate-300 leading-snug">
                  {body}
                </span>
              </li>
            );
          })}
        </ol>
      )}
      {others.map((line, i) => (
        <p key={i} className="text-sm text-slate-600 dark:text-slate-400">{line}</p>
      ))}
      {tips.map((tip, i) => (
        <div key={i}
          className="bg-amber-50 dark:bg-amber-900/10 border border-amber-200 dark:border-amber-800 rounded-lg px-2.5 py-1.5">
          <p className="text-xs text-amber-700 dark:text-amber-300 leading-relaxed">
            <span className="font-semibold">Tip:</span> {tip.replace(/^tip:\s*/i, "")}
          </p>
        </div>
      ))}
    </div>
  );
}

export default function ExerciseDetailModal({ exercise, onClose }) {
  if (!exercise) return null;

  const patCls = PAT_COLOR[exercise.movement_pattern] || "bg-slate-100 text-slate-500 dark:bg-slate-700 dark:text-slate-400";
  const diffLabel = ["", "Beginner", "Intermediate", "Advanced"][exercise.difficulty] ?? "";

  return (
    <div
      className="modal-backdrop"
      onClick={e => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div className="modal max-w-lg max-h-[88vh] flex flex-col">

        {/* Header */}
        <div className="flex items-start justify-between px-4 pt-4 pb-3.5 border-b border-slate-100 dark:border-slate-800">
          <div className="flex-1 min-w-0 pr-2.5">
            <div className="flex items-center gap-2 flex-wrap mb-1.5">
              <h2 className="modal-title">
                {exercise.name}
              </h2>
              {exercise.is_custom && (
                <span className="text-[10px] px-1 py-0.5 rounded bg-indigo-100 text-indigo-700 dark:bg-indigo-900/30 dark:text-indigo-300">
                  custom
                </span>
              )}
              <AnimationBadge
                hasAnimation={exercise.has_animation}
                show={exercise.has_animation !== undefined}
              />
            </div>
            <div className="flex items-center gap-2 flex-wrap">
              {exercise.movement_pattern && (
                <span className={`text-[11px] px-1.5 py-0.5 rounded-full ${patCls}`}>
                  {exercise.movement_pattern}
                </span>
              )}
              {exercise.is_compound !== undefined && (
                <span className="text-[11px] text-slate-400 dark:text-slate-500">
                  {exercise.is_compound ? "Compound" : "Isolation"}
                </span>
              )}
              <div className="flex items-center gap-1.5">
                <DifficultyDots level={exercise.difficulty} />
                <span className="text-[11px] text-slate-400 dark:text-slate-500">{diffLabel}</span>
              </div>
            </div>
          </div>
          <button onClick={onClose}
            className="icon-btn shrink-0 mt-0.5">
            ×
          </button>
        </div>

        {/* Body — scrollable */}
        <div className="flex-1 overflow-y-auto px-4 py-3.5 space-y-5">

          {/* 3D movement demonstration (only when an animation exists) */}
          {hasAnimation(exercise.name, exercise.viewer_slug) && (
            <div className="h-56 -mx-1">
              <MovementSlot name={exercise.name} viewerSlug={exercise.viewer_slug}
                muscles={exercise.primary_muscles} />
            </div>
          )}

          {/* Equipment */}
          {exercise.equipment?.length > 0 && (
            <div>
              <h3 className="text-[10px] font-semibold uppercase tracking-wider text-slate-400 dark:text-slate-500 mb-1.5">
                Equipment
              </h3>
              <div className="flex flex-wrap gap-1.5">
                {exercise.equipment.map(e => (
                  <span key={e}
                    className="text-xs px-1.5 py-1 rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-300">
                    {e}
                  </span>
                ))}
              </div>
            </div>
          )}

          {/* Muscles */}
          <div>
            <h3 className="text-[10px] font-semibold uppercase tracking-wider text-slate-400 dark:text-slate-500 mb-1.5">
              Primary muscles
            </h3>
            <MuscleChips muscles={exercise.primary_muscles} primary />
            {exercise.secondary_muscles?.length > 0 && (
              <div className="mt-2">
                <p className="text-[10px] text-slate-400 dark:text-slate-500 mb-1">Secondary</p>
                <MuscleChips muscles={exercise.secondary_muscles} />
              </div>
            )}
          </div>

          {/* Description / coaching note */}
          {exercise.description && (
            <div>
              <h3 className="text-[10px] font-semibold uppercase tracking-wider text-slate-400 dark:text-slate-500 mb-1.5">
                About
              </h3>
              <p className="text-sm text-slate-600 dark:text-slate-400 leading-relaxed">
                {exercise.description}
              </p>
            </div>
          )}

          {/* Coaching cues */}
          {(exercise.cues || []).length > 0 && (
            <div>
              <h3 className="text-[10px] font-semibold uppercase tracking-wider text-slate-400 dark:text-slate-500 mb-2">
                Coaching cues
              </h3>
              <ul className="space-y-1">
                {exercise.cues.map((c, i) => (
                  <li key={i} className="flex gap-2 text-sm text-slate-700 dark:text-slate-300">
                    <span className="text-accent-500 shrink-0">•</span>
                    <span>{c}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}

          {/* Instructions */}
          {exercise.instructions ? (
            <div>
              <h3 className="text-[10px] font-semibold uppercase tracking-wider text-slate-400 dark:text-slate-500 mb-2">
                How to perform
              </h3>
              <InstructionSteps text={exercise.instructions} />
            </div>
          ) : (
            <div className="text-sm text-slate-400 dark:text-slate-500 italic">
              No instructions available yet.
            </div>
          )}

          {/* Default sets/reps for custom exercises */}
          {exercise.is_custom && (exercise.default_sets || exercise.default_reps) && (
            <div>
              <h3 className="text-[10px] font-semibold uppercase tracking-wider text-slate-400 dark:text-slate-500 mb-1.5">
                Default prescription
              </h3>
              <div className="flex gap-3 text-sm text-slate-700 dark:text-slate-300">
                {exercise.default_sets && <span>{exercise.default_sets} sets</span>}
                {exercise.default_reps && <span>{exercise.default_reps} reps</span>}
              </div>
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="px-4 py-2.5 border-t border-slate-100 dark:border-slate-800 flex justify-end">
          <button onClick={onClose}
            className="btn btn-neutral">
            Close
          </button>
        </div>
      </div>
    </div>
  );
}
