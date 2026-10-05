// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Bottom-sheet / dialog listing activities in the window around an injury
// (7 days before/after its start and end). Self-contained: fetches its own data
// from the API on the injury id, formats distance/duration by unit preference,
// and navigates to an activity on click. Closes via `onClose`.

import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api } from "../../api/client";
import { fmtDate } from "./helpers";

export default function InjuryActivitiesDrawer({ injury, onClose, imperial = false }) {
  const navigate = useNavigate();
  const [acts, setActs] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    setActs(null);
    setError(null);
    api.getInjuryActivities(injury.id)
      .then(setActs)
      .catch(err => { setError(err?.message || "Failed to load"); setActs([]); });
  }, [injury.id]);

  function fmtDist(m) {
    if (!m) return "—";
    if (imperial) return `${(m / 1609.344).toFixed(2)} mi`;
    return `${(m / 1000).toFixed(1)} km`;
  }
  function fmtDur(s) {
    if (!s) return "—";
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    return h > 0 ? `${h}h ${m}m` : `${m}m`;
  }

  const windowDesc = injury.end_date
    ? `7 days before ${fmtDate(injury.start_date)} and 7 days after ${fmtDate(injury.end_date)}`
    : `7 days before and after ${fmtDate(injury.start_date)}`;

  return (
    <div className="fixed inset-0 bg-black/40 z-50 flex items-end sm:items-center justify-center" onClick={onClose}>
      <div
        className="bg-white dark:bg-slate-900 rounded-t-2xl sm:rounded-2xl border border-slate-200 dark:border-slate-800 w-full sm:max-w-lg max-h-[80vh] overflow-y-auto p-4"
        onClick={e => e.stopPropagation()}
      >
        <div className="flex items-center justify-between mb-3">
          <h3 className="font-semibold text-slate-800 dark:text-slate-100">
            {injury.body_part} — {injury.injury_type}
          </h3>
          <button onClick={onClose} className="text-slate-400 hover:text-slate-600 text-xl leading-none">×</button>
        </div>
        <p className="text-xs text-slate-500 dark:text-slate-400 mb-4">{windowDesc}</p>

        {acts === null && !error && (
          <p className="text-sm text-slate-400 animate-pulse">Loading…</p>
        )}
        {error && (
          <p className="text-sm text-red-500">{error}</p>
        )}
        {acts !== null && acts.length === 0 && (
          <p className="text-sm text-slate-400">No activities in this window.</p>
        )}
        {acts !== null && acts.length > 0 && (
          <div className="space-y-2">
            {acts.map(a => {
              const rel = a.days_from_injury;
              const before = rel < 0;
              const same = rel === 0;
              return (
                <button
                  key={a.id}
                  onClick={() => { onClose(); navigate(`/activities/${a.id}`); }}
                  className={`w-full text-left rounded-lg p-2.5 text-xs border transition-opacity hover:opacity-80 ${
                    same
                      ? "border-red-300 dark:border-red-700 bg-red-50 dark:bg-red-950"
                      : before
                      ? "border-slate-200 dark:border-slate-700"
                      : "border-amber-200 dark:border-amber-800 bg-amber-50 dark:bg-amber-950"
                  }`}
                >
                  <div className="flex justify-between">
                    <span className="font-semibold text-slate-800 dark:text-slate-100">
                      {a.title || a.sport || "Activity"}
                    </span>
                    <span className={`font-medium ${same ? "text-red-600 dark:text-red-400" : before ? "text-slate-500" : "text-amber-600 dark:text-amber-400"}`}>
                      {same ? "Injury day" : before ? `${Math.abs(rel)}d before` : `${rel}d after`}
                    </span>
                  </div>
                  <div className="flex gap-3 mt-1 text-slate-500 dark:text-slate-400">
                    <span>{a.sport}</span>
                    <span>{fmtDist(a.distance_meters)}</span>
                    <span>{fmtDur(a.duration_seconds)}</span>
                    <span>{a.started_at ? new Date(a.started_at).toLocaleDateString() : ""}</span>
                  </div>
                </button>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
