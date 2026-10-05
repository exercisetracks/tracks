// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Colour/label lookup tables and pure date/format helpers for the training-plan
// calendar. Framework-free so they're trivially shareable across the calendar
// sub-components (StepRow, WorkoutDetail) and the section shell.

export const WORKOUT_COLORS = {
  easy:          "bg-accent-100 dark:bg-accent-900/40 text-accent-700 dark:text-accent-300 border-accent-200 dark:border-accent-800",
  long_run:      "bg-blue-100 dark:bg-blue-900/40 text-blue-700 dark:text-blue-300 border-blue-200 dark:border-blue-800",
  tempo:         "bg-amber-100 dark:bg-amber-900/40 text-amber-700 dark:text-amber-300 border-amber-200 dark:border-amber-800",
  intervals:     "bg-red-100 dark:bg-red-900/40 text-red-700 dark:text-red-300 border-red-200 dark:border-red-800",
  race_pace:     "bg-orange-100 dark:bg-orange-900/40 text-orange-700 dark:text-orange-300 border-orange-200 dark:border-orange-800",
  fartlek:       "bg-purple-100 dark:bg-purple-900/40 text-purple-700 dark:text-purple-300 border-purple-200 dark:border-purple-800",
  short_quality: "bg-pink-100 dark:bg-pink-900/40 text-pink-700 dark:text-pink-300 border-pink-200 dark:border-pink-800",
  race:          "bg-slate-900 dark:bg-white text-white dark:text-slate-900 border-slate-700 dark:border-slate-300",
  endurance:     "bg-sky-100 dark:bg-sky-900/40 text-sky-700 dark:text-sky-300 border-sky-200 dark:border-sky-800",
  sweet_spot:    "bg-teal-100 dark:bg-teal-900/40 text-teal-700 dark:text-teal-300 border-teal-200 dark:border-teal-800",
  easy_spin:     "bg-accent-100 dark:bg-accent-900/40 text-accent-700 dark:text-accent-300 border-accent-200 dark:border-accent-800",
  aerobic:       "bg-cyan-100 dark:bg-cyan-900/40 text-cyan-700 dark:text-cyan-300 border-cyan-200 dark:border-cyan-800",
  // Swimming, rowing, hiking, skiing, climbing: easy work in cool colours,
  // the hard sessions warm, as above.
  technique:     "bg-cyan-100 dark:bg-cyan-900/40 text-cyan-700 dark:text-cyan-300 border-cyan-200 dark:border-cyan-800",
  ut2:           "bg-sky-100 dark:bg-sky-900/40 text-sky-700 dark:text-sky-300 border-sky-200 dark:border-sky-800",
  ut1:           "bg-sky-100 dark:bg-sky-900/40 text-sky-700 dark:text-sky-300 border-sky-200 dark:border-sky-800",
  vert:          "bg-sky-100 dark:bg-sky-900/40 text-sky-700 dark:text-sky-300 border-sky-200 dark:border-sky-800",
  pole_hike:     "bg-sky-100 dark:bg-sky-900/40 text-sky-700 dark:text-sky-300 border-sky-200 dark:border-sky-800",
  arc:           "bg-sky-100 dark:bg-sky-900/40 text-sky-700 dark:text-sky-300 border-sky-200 dark:border-sky-800",
  back_to_back:  "bg-blue-100 dark:bg-blue-900/40 text-blue-700 dark:text-blue-300 border-blue-200 dark:border-blue-800",
  descent:       "bg-teal-100 dark:bg-teal-900/40 text-teal-700 dark:text-teal-300 border-teal-200 dark:border-teal-800",
  eccentric:     "bg-teal-100 dark:bg-teal-900/40 text-teal-700 dark:text-teal-300 border-teal-200 dark:border-teal-800",
  agility:       "bg-teal-100 dark:bg-teal-900/40 text-teal-700 dark:text-teal-300 border-teal-200 dark:border-teal-800",
  css:           "bg-amber-100 dark:bg-amber-900/40 text-amber-700 dark:text-amber-300 border-amber-200 dark:border-amber-800",
  threshold:     "bg-amber-100 dark:bg-amber-900/40 text-amber-700 dark:text-amber-300 border-amber-200 dark:border-amber-800",
  incline_intervals: "bg-red-100 dark:bg-red-900/40 text-red-700 dark:text-red-300 border-red-200 dark:border-red-800",
  vo2:           "bg-red-100 dark:bg-red-900/40 text-red-700 dark:text-red-300 border-red-200 dark:border-red-800",
  bounding:      "bg-red-100 dark:bg-red-900/40 text-red-700 dark:text-red-300 border-red-200 dark:border-red-800",
  ski_intervals: "bg-red-100 dark:bg-red-900/40 text-red-700 dark:text-red-300 border-red-200 dark:border-red-800",
  power_endurance: "bg-red-100 dark:bg-red-900/40 text-red-700 dark:text-red-300 border-red-200 dark:border-red-800",
  sprint:        "bg-pink-100 dark:bg-pink-900/40 text-pink-700 dark:text-pink-300 border-pink-200 dark:border-pink-800",
  plyometrics:   "bg-purple-100 dark:bg-purple-900/40 text-purple-700 dark:text-purple-300 border-purple-200 dark:border-purple-800",
  hangboard:     "bg-purple-100 dark:bg-purple-900/40 text-purple-700 dark:text-purple-300 border-purple-200 dark:border-purple-800",
  limit_bouldering: "bg-purple-100 dark:bg-purple-900/40 text-purple-700 dark:text-purple-300 border-purple-200 dark:border-purple-800",
  // Strength/mobility: resolved by title in workoutColor() below
  strength:      "bg-violet-100 dark:bg-violet-900/40 text-violet-700 dark:text-violet-300 border-violet-200 dark:border-violet-800",
  mobility:      "bg-teal-100 dark:bg-teal-900/40 text-teal-700 dark:text-teal-300 border-teal-200 dark:border-teal-800",
};
export const WORKOUT_DEFAULT = "bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-300 border-slate-200 dark:border-slate-700";

// Per-split strength colors — keyed by keywords found in the workout title.
// Lower body = warm orange; Upper = indigo; Core/upper = rose; Push = sky; Pull = violet;
// Full body = amber; Mobility = teal (same as above).
export const STRENGTH_COLORS = {
  lower:    "bg-orange-100 dark:bg-orange-900/40 text-orange-700 dark:text-orange-300 border-orange-200 dark:border-orange-800",
  upper:    "bg-indigo-100 dark:bg-indigo-900/40 text-indigo-700 dark:text-indigo-300 border-indigo-200 dark:border-indigo-800",
  core:     "bg-rose-100 dark:bg-rose-900/40 text-rose-700 dark:text-rose-300 border-rose-200 dark:border-rose-800",
  push:     "bg-sky-100 dark:bg-sky-900/40 text-sky-700 dark:text-sky-300 border-sky-200 dark:border-sky-800",
  pull:     "bg-violet-100 dark:bg-violet-900/40 text-violet-700 dark:text-violet-300 border-violet-200 dark:border-violet-800",
  legs:     "bg-amber-100 dark:bg-amber-900/40 text-amber-700 dark:text-amber-300 border-amber-200 dark:border-amber-800",
  fullbody: "bg-yellow-100 dark:bg-yellow-900/40 text-yellow-700 dark:text-yellow-300 border-yellow-200 dark:border-yellow-800",
};

// Pick the badge colour for a workout. Strength workouts are keyed by title
// keyword (lower/upper/core/push/pull/leg/full); everything else falls back to
// the workout_type table.
export function workoutColor(w) {
  if (w.workout_type === "mobility") return WORKOUT_COLORS.mobility;
  if (w.workout_type === "strength") {
    const t = (w.title || "").toLowerCase();
    if (t.includes("lower"))                  return STRENGTH_COLORS.lower;
    if (t.includes("upper") && t.includes("core")) return STRENGTH_COLORS.core;
    if (t.includes("upper"))                  return STRENGTH_COLORS.upper;
    if (t.includes("push"))                   return STRENGTH_COLORS.push;
    if (t.includes("pull"))                   return STRENGTH_COLORS.pull;
    if (t.includes("leg"))                    return STRENGTH_COLORS.legs;
    if (t.includes("full"))                   return STRENGTH_COLORS.fullbody;
    return WORKOUT_COLORS.strength; // fallback
  }
  return WORKOUT_COLORS[w.workout_type] ?? WORKOUT_DEFAULT;
}

export const PACE_LABELS = {
  easy: "Easy", marathon: "Marathon pace", threshold: "Threshold",
  interval: "Interval", repetition: "Rep pace",
};

export const MONTH_NAMES = ["January","February","March","April","May","June",
                            "July","August","September","October","November","December"];
export const DOW = ["Mon","Tue","Wed","Thu","Fri","Sat","Sun"];

export function isoDate(d) { return d.toISOString().slice(0, 10); }

// Build the grid of Date cells for a month, padded to whole weeks (Mon-start).
// Leading days come from the previous month, trailing days from the next.
export function monthDates(year, month) {
  const first = new Date(year, month, 1);
  const last  = new Date(year, month + 1, 0);
  const startDow = (first.getDay() + 6) % 7;
  const days = [];
  for (let i = -startDow; i < last.getDate(); i++) {
    days.push(new Date(year, month, 1 + i));
  }
  while (days.length % 7 !== 0) {
    days.push(new Date(year, month, last.getDate() + (days.length - last.getDate() - startDow + 1)));
  }
  return days;
}

export function fmtDist(m, imperial = false) {
  if (!m) return null;
  if (imperial) {
    const miles = m / 1609.344;
    return miles >= 0.5 ? `${miles.toFixed(2)} mi` : `${Math.round(m * 1.09361)} yd`;
  }
  return m >= 1000 ? `${(m / 1000).toFixed(1)} km` : `${Math.round(m)} m`;
}

export function fmtDur(min) {
  if (!min) return null;
  const h = Math.floor(min / 60);
  const m = min % 60;
  return h > 0 ? `${h}h ${m > 0 ? m + "m" : ""}`.trim() : `${m} min`;
}
