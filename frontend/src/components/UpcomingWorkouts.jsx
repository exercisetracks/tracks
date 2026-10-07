// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api } from "../api/client";
import { todayDate } from "../lib/today";
import { workoutDot } from "../lib/workoutColors";

function makeFmtDist(imperial) {
  return function fmtDist(m) {
    if (!m) return null;
    if (imperial) {
      const miles = m / 1609.344;
      return miles >= 0.5 ? `${miles.toFixed(2)} mi` : `${Math.round(m * 1.09361)} yd`;
    }
    return m >= 1000 ? `${(m / 1000).toFixed(1)} km` : `${Math.round(m)} m`;
  };
}


function workoutBadge(type) {
  if (!type) return null;
  if (type === "skills") return "SKILLS";
  if (type.startsWith("field_test")) return "TEST";
  return null;
}

function fmtDur(min) {
  if (!min) return null;
  const h = Math.floor(min / 60);
  const m = min % 60;
  return h > 0 ? `${h}h ${m > 0 ? m + "m" : ""}`.trim() : `${m} min`;
}

function relDay(dateStr) {
  const today = todayDate();
  const d = new Date(dateStr + "T00:00:00");
  const diff = Math.round((d - today) / 86400000);
  if (diff === 0) return "Today";
  if (diff === 1) return "Tomorrow";
  if (diff <= 7) return d.toLocaleDateString(undefined, { weekday: "long" });
  return d.toLocaleDateString(undefined, { month: "short", day: "numeric" });
}

export default function UpcomingWorkouts({ imperial = false } = {}) {
  const [workouts, setWorkouts] = useState(null);
  const navigate = useNavigate();
  const fmtDist = makeFmtDist(imperial);

  useEffect(() => {
    api.getUpcomingWorkouts(14)
      .then(setWorkouts)
      .catch(() => setWorkouts([]));
  }, []);

  if (workouts === null) return null;
  if (workouts.length === 0) return null;

  return (
    <div className="card">
      <div className="flex items-center justify-between mb-3">
        <h3 className="section-title">
          Upcoming Workouts
        </h3>
        <button
          onClick={() => navigate("/calendar")}
          className="btn btn-tonal btn-sm"
        >
          View calendar
        </button>
      </div>
      <div className="space-y-2">
        {workouts.slice(0, 5).map(w => (
          <div
            key={w.id}
            className="flex items-center gap-3 py-1 cursor-pointer hover:opacity-80"
            onClick={() => navigate("/calendar")}
          >
            <div className={`w-2 h-2 rounded-full shrink-0 ${workoutDot(w)}`} />
            <div className="flex-1 min-w-0">
              <p className="text-sm font-medium text-slate-800 dark:text-slate-100 truncate">
                {w.title}
                {workoutBadge(w.workout_type) && (
                  <span className="ml-1.5 inline-block px-1 py-0.5 rounded text-[10px] font-bold align-middle bg-amber-100 dark:bg-amber-900/40 text-amber-700 dark:text-amber-300">
                    {workoutBadge(w.workout_type)}
                  </span>
                )}
              </p>
              <p className="text-xs text-slate-400 dark:text-slate-500">
                {relDay(w.scheduled_date)}
                {w.duration_minutes && ` · ${fmtDur(w.duration_minutes)}`}
                {w.distance_meters && ` · ${fmtDist(w.distance_meters)}`}
              </p>
            </div>
            {w.is_complete && (
              <svg className="w-4 h-4 text-accent-500 shrink-0" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
              </svg>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
