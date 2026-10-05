// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import AnimationBadge from "./AnimationBadge";
import MovementSlot from "./player/MovementSlot";
import { hasAnimation } from "../animations";
import { muscleLabel } from "../utils/muscleGroups";

const PAT_COLOR = {
  static_stretch:     "bg-blue-100 dark:bg-blue-900/40 text-blue-700 dark:text-blue-300 border-blue-200 dark:border-blue-800",
  dynamic_stretch:    "bg-accent-100 dark:bg-accent-900/40 text-accent-700 dark:text-accent-300 border-accent-200 dark:border-accent-800",
  yoga_pose:          "bg-purple-100 dark:bg-purple-900/40 text-purple-700 dark:text-purple-300 border-purple-200 dark:border-purple-800",
  balance_pose:       "bg-amber-100 dark:bg-amber-900/40 text-amber-700 dark:text-amber-300 border-amber-200 dark:border-amber-800",
  pnf_stretch:        "bg-red-100 dark:bg-red-900/40 text-red-700 dark:text-red-300 border-red-200 dark:border-red-800",
  myofascial_release: "bg-pink-100 dark:bg-pink-900/40 text-pink-700 dark:text-pink-300 border-pink-200 dark:border-pink-800",
};
const PAT_LABEL = { static_stretch: "Static", dynamic_stretch: "Dynamic", yoga_pose: "Yoga", balance_pose: "Balance", pnf_stretch: "PNF", myofascial_release: "Release" };


export default function StretchDetailModal({ stretch, onClose }) {
  if (!stretch) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/50 backdrop-blur-sm" onClick={onClose}>
      <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 shadow-lg max-w-lg w-full max-h-[80vh] overflow-y-auto m-4" onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between p-3.5 border-b border-slate-200 dark:border-slate-700">
          <div className="flex items-center gap-2 min-w-0">
            <span className={`text-xs font-semibold px-1.5 py-0.5 rounded-full border ${PAT_COLOR[stretch.movement_pattern] || "bg-slate-100 dark:bg-slate-800 text-slate-500"}`}>
              {PAT_LABEL[stretch.movement_pattern] || stretch.movement_pattern}
            </span>
          </div>
          <button onClick={onClose} className="p-1 rounded hover:bg-slate-100 dark:hover:bg-slate-800 text-slate-400">
            <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>
        <div className="p-3.5 space-y-3">
          {hasAnimation(stretch.name, stretch.viewer_slug) && (
            <div className="h-52 -mx-1">
              <MovementSlot name={stretch.name} viewerSlug={stretch.viewer_slug}
                muscles={stretch.primary_muscles} />
            </div>
          )}
          <div className="flex items-center gap-2 flex-wrap">
            <h3 className="text-base font-semibold text-slate-900 dark:text-white">{stretch.name}</h3>
            <AnimationBadge
              hasAnimation={stretch.has_animation}
              show={stretch.has_animation !== undefined}
            />
          </div>
          <div className="flex items-center gap-2 flex-wrap">
            {stretch.each_side && <span className="text-[10px] px-1.5 py-0.5 rounded bg-amber-100 dark:bg-amber-900/40 text-amber-700 dark:text-amber-300 font-medium">Each side</span>}
            <span className="text-sm text-slate-500 dark:text-slate-400">
              {stretch.duration_per_side_sec}s hold{stretch.each_side ? " per side" : ""}{stretch.sets > 1 ? ` × ${stretch.sets} sets` : ""}
            </span>
            <span className="text-sm text-slate-400">Difficulty: {"\u25CF".repeat(stretch.difficulty)}{"\u25CB".repeat(3 - stretch.difficulty)}</span>
          </div>
          {(stretch.primary_muscles || []).length > 0 && (
            <div><p className="text-xs font-semibold text-slate-400 uppercase tracking-wider mb-1">Targets</p>
              <div className="flex gap-1 flex-wrap">
                {stretch.primary_muscles.map(m => (
                  <span key={m} className="text-[10px] px-1 py-0.5 rounded bg-accent-100 dark:bg-accent-900/40 text-accent-700 dark:text-accent-300">{muscleLabel(m)}</span>
                ))}
                {(stretch.secondary_muscles || []).map(m => (
                  <span key={m} className="text-[10px] px-1 py-0.5 rounded bg-slate-100 dark:bg-slate-800 text-slate-500 dark:text-slate-400">{muscleLabel(m)}</span>
                ))}
              </div>
            </div>
          )}
          {(stretch.equipment || []).length > 0 && (
            <div><p className="text-xs font-semibold text-slate-400 uppercase tracking-wider mb-1">Equipment</p>
              <div className="flex gap-1 flex-wrap">
                {(stretch.equipment || []).map(e => (
                  <span key={e} className="text-[10px] px-1 py-0.5 rounded bg-slate-100 dark:bg-slate-800 text-slate-500 dark:text-slate-400">{e}</span>
                ))}
              </div>
            </div>
          )}
          {stretch.description && <p className="text-sm text-slate-600 dark:text-slate-300">{stretch.description}</p>}
          {(stretch.cues || []).length > 0 && (
            <div>
              <p className="text-xs font-semibold text-slate-400 uppercase tracking-wider mb-1">Coaching cues</p>
              <ul className="space-y-1">
                {stretch.cues.map((c, i) => (
                  <li key={i} className="flex gap-2 text-sm text-slate-600 dark:text-slate-300">
                    <span className="text-accent-500 shrink-0">•</span><span>{c}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
          {stretch.breath_cue && (
            <p className="text-sm text-slate-500 dark:text-slate-400 italic">Breathe: {stretch.breath_cue}</p>
          )}
          {stretch.instructions && (
            <div>
              <p className="text-xs font-semibold text-slate-400 uppercase tracking-wider mb-1">Instructions</p>
              <ol className="list-decimal list-inside text-sm text-slate-600 dark:text-slate-300 space-y-1">
                {stretch.instructions.split("\n").filter(Boolean).map((l, i) => (
                  <li key={i} className="pl-1">{l.replace(/^\d+\.\s*/, "")}</li>
                ))}
              </ol>
            </div>
          )}
          {stretch.cautions && (
            <div className="p-2.5 rounded-lg bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800">
              <p className="text-xs font-semibold text-amber-700 dark:text-amber-400 mb-1">Cautions</p>
              <p className="text-xs text-amber-600 dark:text-amber-300">{stretch.cautions}</p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
