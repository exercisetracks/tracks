// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Header control for the training calendar: picks which active planned goal
// (event or fitness) to view and triggers plan generation for it. Pure presentational — the parent
// owns the goal list, the current selection, and the select/generate handlers.
// Renders a friendly empty state (with a link to /goals) when there are no
// active event goals to choose from.

import { hasPlan, rampLabel } from "../goals/helpers";

export default function GoalSelector({ goals, selectedId, onSelect, onGenerate, generating }) {
  const eventGoals = goals.filter(g => hasPlan(g) && g.is_active);
  if (eventGoals.length === 0) {
    return (
      <div className="text-sm text-slate-500 dark:text-slate-400">
        No active event or fitness goals.{" "}
        <a href="/goals" className="text-accent-600 dark:text-accent-400 hover:underline">Add one</a>
      </div>
    );
  }
  return (
    <div className="flex items-center gap-3 flex-wrap">
      <select
        value={selectedId ?? ""}
        onChange={e => onSelect(Number(e.target.value))}
        className="text-sm rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-200 px-2.5 py-1 focus:outline-none focus:ring-2 focus:ring-accent-500"
      >
        {eventGoals.map(g => (
          <option key={g.id} value={g.id}>
            {g.goal_type === "fitness"
              ? `Fitness — ${rampLabel(g.ctl_ramp_per_week ?? 0)}`
              : `${g.event_name} — ${g.event_date}`}
          </option>
        ))}
      </select>
      <button
        onClick={() => selectedId && onGenerate(selectedId)}
        disabled={!selectedId || generating}
        className="btn btn-primary btn-sm"
      >
        {generating ? "Generating…" : "Generate Plan"}
      </button>
    </div>
  );
}
