// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Colour/label lookup tables and pure date/format helpers for the training
// calendar page. Framework-free (no React) so they can be shared trivially
// across the calendar sub-components (StepRow, WorkoutDetail) and the page shell.
//
// NOTE: this mirrors components/plancalendar/constants.js but is kept separate
// on purpose — the two calendars have subtly different behaviour and we don't
// want a change in one to silently alter the other.
import { isoOfDay } from "../../lib/today";

// ─────────────────────────────────────────
// Colour palette per workout type
// ─────────────────────────────────────────

// Workout chip colours come from lib/workoutColors.js (spec/workout_colors.yaml).
export { workoutChip } from "../../lib/workoutColors";

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
  return isoOfDay(d);
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
