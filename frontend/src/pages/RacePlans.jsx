// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../api/client";

function SyncBadge({ status }) {
  if (!status) return null;
  const cfg = {
    synced:   { label: "Synced to watch",          cls: "bg-accent-50 text-accent-700 dark:bg-accent-900/20 dark:text-accent-400" },
    pending:  { label: "Will sync on next connect", cls: "bg-amber-50 text-amber-700 dark:bg-amber-900/20 dark:text-amber-400" },
    no_plan:  { label: "No plan generated",         cls: "bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-400" },
  }[status] ?? { label: status, cls: "bg-slate-100 text-slate-500" };
  return (
    <span className={`inline-flex items-center text-xs font-medium px-1.5 py-0.5 rounded-full ${cfg.cls}`}>
      {cfg.label}
    </span>
  );
}

function GoalCard({ goal, imperial }) {
  const [pred, setPred] = useState(null);
  const [plan, setPlan] = useState(null);

  useEffect(() => {
    if (!goal.event_distance_meters) return;
    api.getPredictedTime(goal.id).then(setPred).catch(() => {});
    api.getRacePlan(goal.id).then(setPlan).catch(() => {});
  }, [goal.id]);

  const distLabel = goal.event_distance_meters
    ? (imperial
        ? `${(goal.event_distance_meters / 1609.344).toFixed(1)} mi`
        : `${(goal.event_distance_meters / 1000).toFixed(1)} km`)
    : null;

  const syncStatus = plan?.generated_at
    ? (plan.watch_uploaded_at ? "synced" : "pending")
    : "no_plan";

  return (
    <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 p-4 flex items-start justify-between gap-4">
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2 flex-wrap mb-1">
          <span className="font-semibold text-slate-900 dark:text-white">{goal.event_name || "Unnamed event"}</span>
          {goal.event_sport && (
            <span className="text-xs text-slate-400 capitalize">{goal.event_sport.replace(/_/g, " ")}</span>
          )}
          <SyncBadge status={syncStatus} />
        </div>
        <div className="flex items-center gap-3 text-sm text-slate-500 dark:text-slate-400 flex-wrap">
          {distLabel && <span>{distLabel}</span>}
          {goal.event_date && <span>{goal.event_date}</span>}
          {pred?.predicted_time && (
            <span className="text-accent-600 dark:text-accent-400 font-medium font-mono">
              Predicted {pred.predicted_time}
            </span>
          )}
        </div>
      </div>
      <Link
        to={`/race-plans/${goal.id}`}
        className="btn btn-tonal btn-sm"
      >
        {plan?.generated_at ? "View plan" : "Create plan"}
      </Link>
    </div>
  );
}

export default function RacePlans() {
  const [goals, setGoals] = useState(null);
  const [settings, setSettings] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    api.listGoals()
      .then(all => setGoals(all.filter(g => g.goal_type === "event")))
      .catch(e => setError(e.message));
    api.getSettings().then(setSettings).catch(() => {});
  }, []);

  const imperial = settings?.units === "imperial";

  return (
    <div className="max-w-3xl mx-auto px-3.5 py-7 space-y-6">
      <div data-tour="raceplans-intro">
        <h1 className="text-2xl font-bold text-slate-900 dark:text-white">Race Plans</h1>
        <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
          Pacing guides generated from your VDOT, freshness, course profile, and race-day weather.
        </p>
      </div>

      {error && (
        <div className="text-sm text-red-600 dark:text-red-400 bg-red-50 dark:bg-red-900/20 rounded-lg px-3.5 py-2.5">
          {error}
        </div>
      )}

      {goals === null && !error && (
        <div className="flex justify-center py-11">
          <div className="w-6 h-6 border-2 border-accent-500 border-t-transparent rounded-full animate-spin" />
        </div>
      )}

      {goals?.length === 0 && (
        <div className="text-center py-11 text-slate-500 dark:text-slate-400">
          <p className="mb-3">No event goals found.</p>
          <Link to="/goals" className="text-accent-600 dark:text-accent-400 hover:underline text-sm">
            Create an event goal in Training →
          </Link>
        </div>
      )}

      {goals?.length > 0 && (
        <div data-tour="raceplans-card" className="space-y-3">
          {goals.map(g => <GoalCard key={g.id} goal={g} imperial={imperial} />)}
        </div>
      )}
    </div>
  );
}
