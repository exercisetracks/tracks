// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Colour/label lookup tables and pure date/format helpers for the training-plan
// calendar. Framework-free so they're trivially shareable across the calendar
// sub-components (StepRow, WorkoutDetail) and the section shell.

import { isoOfDay } from "../../lib/today";

// A workout's chip colour: family hue, intensity shade, from
// spec/workout_colors.yaml — see lib/workoutColors.js. The per-type tables that
// used to live here left every type they did not list (the generators' `long`,
// `easy_recovery`, `flexibility`) grey.
export { workoutChip as workoutColor } from "../../lib/workoutColors";

export const PACE_LABELS = {
  easy: "Easy", marathon: "Marathon pace", threshold: "Threshold",
  interval: "Interval", repetition: "Rep pace",
};

export const MONTH_NAMES = ["January","February","March","April","May","June",
                            "July","August","September","October","November","December"];
export const DOW = ["Mon","Tue","Wed","Thu","Fri","Sat","Sun"];

export function isoDate(d) { return isoOfDay(d); }

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
