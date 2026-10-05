// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Colour/label lookup tables and pure date/format helpers for the training
// calendar page. Framework-free (no React) so they can be shared trivially
// across the calendar sub-components (StepRow, WorkoutDetail) and the page shell.
//
// NOTE: this mirrors components/plancalendar/constants.js but is kept separate
// on purpose — the two calendars have subtly different behaviour and we don't
// want a change in one to silently alter the other.

// ─────────────────────────────────────────
// Colour palette per workout type
// ─────────────────────────────────────────
export const WORKOUT_COLORS = {
  easy:          "bg-accent-100 dark:bg-accent-900/40 text-accent-700 dark:text-accent-300 border-accent-200 dark:border-accent-800",
  long_run:      "bg-blue-100 dark:bg-blue-900/40 text-blue-700 dark:text-blue-300 border-blue-200 dark:border-blue-800",
  tempo:         "bg-amber-100 dark:bg-amber-900/40 text-amber-700 dark:text-amber-300 border-amber-200 dark:border-amber-800",
  intervals:     "bg-red-100 dark:bg-red-900/40 text-red-700 dark:text-red-300 border-red-200 dark:border-red-800",
  race_pace:     "bg-orange-100 dark:bg-orange-900/40 text-orange-700 dark:text-orange-300 border-orange-200 dark:border-orange-800",
  fartlek:       "bg-purple-100 dark:bg-purple-900/40 text-purple-700 dark:text-purple-300 border-purple-200 dark:border-purple-800",
  short_quality: "bg-pink-100 dark:bg-pink-900/40 text-pink-700 dark:text-pink-300 border-pink-200 dark:border-pink-800",
  race:          "bg-slate-900 dark:bg-white text-white dark:text-slate-900 border-slate-700 dark:border-slate-300",
};

export const WORKOUT_DEFAULT = "bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-300 border-slate-200 dark:border-slate-700";

// Human-readable labels for pace zones used in run/warmup/cooldown step rows.
export const PACE_LABELS = {
  easy:       "Easy",
  marathon:   "Marathon pace",
  threshold:  "Threshold",
  interval:   "Interval",
  repetition: "Rep pace",
};

export const MONTH_NAMES = ["January","February","March","April","May","June",
                            "July","August","September","October","November","December"];
export const DOW = ["Mon","Tue","Wed","Thu","Fri","Sat","Sun"];

// ─────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────

// Format a Date as a YYYY-MM-DD string (matches the backend scheduled_date key).
export function isoDate(d) {
  return d.toISOString().slice(0, 10);
}

// Returns array of dates for the calendar grid (including padding days).
// Monday-start weeks: leading cells come from the previous month, trailing
// cells from the next, so the grid always fills whole rows of 7.
export function monthDates(year, month) {
  const first = new Date(year, month, 1);
  const last = new Date(year, month + 1, 0);
  const startDow = (first.getDay() + 6) % 7; // Monday = 0
  const days = [];
  for (let i = -startDow; i < last.getDate(); i++) {
    days.push(new Date(year, month, 1 + i));
  }
  // Pad to complete last row
  while (days.length % 7 !== 0) {
    days.push(new Date(year, month, last.getDate() + (days.length - last.getDate() - startDow + 1)));
  }
  return days;
}

// Distance formatter. Metric by default; imperial switches to miles (or yards
// under half a mile). Returns null for falsy inputs so callers can skip render.
export function fmtDist(m, imperial = false) {
  if (!m) return null;
  if (imperial) {
    const miles = m / 1609.344;
    return miles >= 0.5 ? `${miles.toFixed(2)} mi` : `${Math.round(m * 1.09361)} yd`;
  }
  return m >= 1000 ? `${(m / 1000).toFixed(1)} km` : `${Math.round(m)} m`;
}

// Duration formatter: minutes → "1h 20m" / "1h" / "45 min". Null for falsy input.
export function fmtDur(min) {
  if (!min) return null;
  const h = Math.floor(min / 60);
  const m = min % 60;
  return h > 0 ? `${h}h ${m > 0 ? m + "m" : ""}`.trim() : `${m} min`;
}
