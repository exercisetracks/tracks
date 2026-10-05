// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * MobilityCard
 * Renders a standalone mobility / recovery session.
 * workout.workout_type === "mobility"
 */

export default function MobilityCard({ workout, compact = false }) {
  const { title, description, duration_minutes, steps = [] } = workout;
  const exercises = steps.filter(s => s.type === "mobility_exercise");

  return (
    <div className="rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 overflow-hidden">
      {/* Header */}
      <div className="px-3.5 pt-2.5 pb-1.5 border-b border-slate-100 dark:border-slate-700 flex items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <span className="inline-block px-1.5 py-0.5 rounded text-xs font-semibold bg-teal-100 text-teal-700 dark:bg-teal-900/40 dark:text-teal-300">
              Mobility
            </span>
            <span className="text-sm font-semibold text-slate-800 dark:text-slate-100 truncate">{title}</span>
          </div>
          {description && !compact && (
            <p className="text-xs text-slate-500 dark:text-slate-400 mt-1 line-clamp-2">{description}</p>
          )}
        </div>
        {duration_minutes && (
          <span className="shrink-0 text-xs text-slate-400 dark:text-slate-500 tabular-nums">{duration_minutes} min</span>
        )}
      </div>

      {!compact && exercises.length > 0 && (
        <div className="px-3.5 py-1.5 space-y-1">
          {exercises.map((s, i) => (
            <div key={i} className="flex items-center justify-between py-1 border-b border-slate-100 dark:border-slate-700/40 last:border-0">
              <div className="min-w-0">
                <p className="text-sm text-slate-700 dark:text-slate-200">{s.name}</p>
                {s.description && (
                  <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5">{s.description}</p>
                )}
              </div>
              <div className="shrink-0 text-right ml-3">
                {s.duration_seconds && (
                  <p className="text-xs tabular-nums text-slate-500 dark:text-slate-400">
                    {s.each_side ? `${s.duration_seconds}s / side` : `${s.duration_seconds}s`}
                    {s.sets > 1 ? ` × ${s.sets}` : ""}
                  </p>
                )}
              </div>
            </div>
          ))}
        </div>
      )}

      {compact && exercises.length > 0 && (
        <div className="px-3.5 py-1.5">
          <p className="text-xs text-slate-500 dark:text-slate-400">
            {exercises.length} exercises · ~{duration_minutes ?? 20} min
          </p>
        </div>
      )}
    </div>
  );
}
