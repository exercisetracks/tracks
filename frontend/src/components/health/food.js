// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// What was eaten, by day. Framework-free so the Eaten dial's arithmetic can be
// tested without rendering the form that feeds it.
//
// ## One number for "calories eaten"
//
// There used to be two, and they never agreed. A typed daily total
// (`calories_in` on the daily metric) drove the Eaten dial, and the meal log —
// every food entry, from the Nutrition section and the phone — kept its own
// total that the dial ignored. Somebody who logged three meals saw an empty
// dial; somebody who typed a total and logged meals had two answers.
//
// Now the meal log is the source: a day's Eaten is its food entries added up.
// Days from before that, which carry only a typed total, keep it — so history
// is not lost — but a day with any food logged is counted from the food, never
// from both, which would double it.

import { localIso } from "./scales";
import { isoOf } from "../../lib/today";

/** The day an entry was eaten on, in the account's zone. */
export function dayOfEntry(entry) {
  return isoOf(new Date(entry.logged_at));
}

/** Food entries on one local day, oldest first. */
export function foodOn(log, date) {
  return log
    .filter(e => dayOfEntry(e) === date)
    .sort((a, b) => new Date(a.logged_at) - new Date(b.logged_at));
}

/**
 * The Eaten dial's series: per day, the food logged, or else the typed total.
 * `days` is the window's metric rows; `log` the food entries (any range — the
 * days before `start` are dropped).
 */
export function eatenSeries(days, log, start = null) {
  const food = new Map();
  for (const e of log) {
    const d = dayOfEntry(e);
    if (start && d < start) continue;
    food.set(d, (food.get(d) ?? 0) + (e.calories ?? 0));
  }
  const typed = new Map(days.filter(d => d.calories_in != null).map(d => [d.date, d.calories_in]));
  const dates = [...new Set([...food.keys(), ...typed.keys()])].sort();
  return {
    dates,
    values: dates.map(d => (food.has(d) ? food.get(d) : typed.get(d))),
  };
}

/**
 * When a food entry logged for `date` happened. Now, for today; midday for a
 * day in the past — the form only knows which day, and noon keeps the entry
 * on that day in any zone the reader is likely to be in.
 */
export function loggedAtFor(date, now = new Date()) {
  if (date === isoOf(now)) return now.toISOString();
  const [y, m, d] = date.split("-").map(Number);
  return new Date(y, m - 1, d, 12).toISOString();
}

/** A typed macro field as a number, or null when left blank. */
export function grams(text) {
  if (text == null || String(text).trim() === "") return null;
  const n = parseFloat(text);
  return Number.isFinite(n) && n >= 0 ? n : null;
}
