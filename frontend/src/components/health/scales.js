// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The scales every Health dial is drawn against, and the rules for what a dial
// may claim. A port of the phone's HealthMeters.kt — the bands, goals and
// colours are the same numbers, because the same reading must not be "Good" on
// one screen and "Average" on the other.
//
// Framework-free, so the rules that decide what a dial says can be tested
// without rendering one.
//
// ## Four kinds of scale, because there are four kinds of number
//
//   bands     Published ranges that mean the same thing for everyone. SpO₂ of
//             91 is low whoever you are.
//   goal      Something to reach. 6,000 steps is not wrong, it is just not
//             10,000 yet.
//   personal  No honest universal scale. Colouring somebody's body mass red
//             would be medically worthless and unkind, and a "good" HRV is
//             whatever is normal for you — so the dial places today inside
//             your own range for the window, and says so.
//   stacked   A total made of parts — a day's calories, a night's stages —
//             where the arc itself carries the breakdown.

import { isoOfDay, todayIso } from "../../lib/today";

export const TEAL   = "#14b8a6";
export const GREEN  = "#22c55e";
export const AMBER  = "#eab308";
export const ORANGE = "#f97316";
export const RED    = "#ef4444";
export const BLUE   = "#38bdf8";
export const VIOLET = "#a855f7";
export const SKY    = "#0ea5e9";
export const SLATE  = "#94a3b8";

/** "Not there yet": the empty stretch of a goal or a stacked arc. */
export const OUTLINE = SLATE;

export const HRV_COLOR       = VIOLET;
export const WEIGHT_COLOR    = BLUE;
export const HYDRATION_COLOR = SKY;
export const CALORIE_COLOR   = ORANGE;
export const STEP_COLOR      = GREEN;
export const ACTIVE_COLOR    = ORANGE;
// Distinct from the active half and from the empty track, so the two halves of
// the day's burn read as two colours rather than one and a gap.
export const RESTING_COLOR   = "#3b82f6";
export const SLEEP_COLOR     = "#6366f1";
/** The watch's sleep score — amber on every sleep chart, phone and web. */
export const SCORE_COLOR     = "#f59e0b";

const zone = (label, min, max, color) => ({ label, min, max, color });

// ── Published ranges ─────────────────────────────────────────────────────────
//
// For adults at rest, and nothing more clinical than that. They say whether a
// number is typical, which is the question somebody glancing at a health page
// is asking; they are not a diagnosis, and the explanation behind each dial
// says so. The stress and Body Battery bands are Garmin's own, so the watch
// and this page cannot disagree about what "medium" means.

/** Lower is better, so the good colours sit at the bottom of the scale. */
export const RESTING_HR_ZONES = [
  zone("Excellent", 35, 50, TEAL),
  zone("Good",      50, 58, GREEN),
  zone("Average",   58, 66, AMBER),
  zone("Fair",      66, 75, ORANGE),
  zone("High",      75, 100, RED),
];

/** Pulse oximetry. Below 90 is the number worth noticing. */
export const SPO2_ZONES = [
  zone("Very low", 80, 90, RED),
  zone("Low",      90, 95, ORANGE),
  zone("Normal",   95, 100, GREEN),
];

/** Breaths per minute, adult at rest. */
export const RESPIRATION_ZONES = [
  zone("Low",      5, 12, AMBER),
  zone("Normal",   12, 20, GREEN),
  zone("Elevated", 20, 30, ORANGE),
];

export const STRESS_ZONES = [
  zone("Rest",   0, 25, TEAL),
  zone("Low",    25, 50, GREEN),
  zone("Medium", 50, 75, AMBER),
  zone("High",   75, 100, ORANGE),
];

export const BODY_BATTERY_ZONES = [
  zone("Low",      0, 25, RED),
  zone("Moderate", 25, 50, AMBER),
  zone("Good",     50, 75, TEAL),
  zone("Charged",  75, 100, GREEN),
];

/**
 * Hours slept. Seven to nine is the adult recommendation; under five is the
 * one worth a red arc, and over nine is amber rather than red because a long
 * night is not a failure.
 */
export const SLEEP_ZONES = [
  zone("Short", 0, 5, RED),
  zone("Light", 5, 6.5, ORANGE),
  zone("Good",  6.5, 7.5, GREEN),
  zone("Ideal", 7.5, 9, TEAL),
  zone("Long",  9, 12, AMBER),
];

// ── Goals ────────────────────────────────────────────────────────────────────
// Everyday defaults, and the only invented numbers here.

export const STEP_GOAL           = 10_000;
/** The sleep dial's arc runs to it and the history draws its goal line at it. */
export const SLEEP_GOAL_HOURS    = 8;
export const ACTIVE_CALORIE_GOAL = 500;
export const HYDRATION_GOAL_ML   = 2_000;

/**
 * What kind of day it has been, by what was burned above resting.
 *
 * The calorie dial spends its colours on the split between resting and active,
 * which leaves it unable to say whether the total is a lot — and "1,552 kcal"
 * means nothing without knowing that 1,530 of it was the body idling. These
 * supply the word, anchored on `base` so they measure the earned part, and tied
 * to ACTIVE_CALORIE_GOAL so the arc and the word agree on what a good day is.
 */
export function activityZones(base) {
  return [
    zone("Sedentary", base,       base + 150, SLATE),
    zone("Light",     base + 150, base + 350, AMBER),
    zone("Active",    base + 350, base + ACTIVE_CALORIE_GOAL, ORANGE),
    zone("Goal met",  base + ACTIVE_CALORIE_GOAL, base + ACTIVE_CALORIE_GOAL * 3, GREEN),
  ];
}

// ── Building a dial's bands ──────────────────────────────────────────────────

/**
 * A stacked scale's bands, laid end to end from zero.
 *
 * Empty parts are dropped rather than drawn as zero-width bands: a night with
 * no REM should not put "REM" on the arc. If nothing is left the whole arc is
 * one empty band — an arc with no extent draws nothing, and a dial that
 * vanishes when a reading is zero looks like a bug. The remainder is drawn only
 * while there is room before the target; past it the arc simply ends on the
 * last part, which is the honest picture of a goal beaten.
 */
export function stackedZones(scale) {
  const parts = scale.parts.filter(p => p.amount > 0);
  const target = Math.max(0, scale.target);
  if (!parts.length) return [zone("None", 0, Math.max(target, 1), OUTLINE)];

  let at = 0;
  const bands = parts.map(p => {
    const from = at;
    at += p.amount;
    return zone(p.label, from, at, p.color);
  });
  return at < target
    ? [...bands, zone(scale.remainderLabel ?? "To goal", at, target, scale.remainderColor ?? OUTLINE)]
    : bands;
}

/**
 * The bands a metric's dial is drawn against.
 *
 * `values` is the window's readings, which only the personal scale uses: the
 * band is built from them, so the dial shows where today sits among the
 * window's own readings rather than against a threshold nobody agreed on. One
 * reading is not a range — "above average" said of a single measurement
 * compared with itself is nonsense dressed as insight — so it gets one band.
 */
export function zonesFor(scale, values = []) {
  switch (scale.kind) {
    case "bands":
      return scale.zones;
    case "stacked":
      return stackedZones(scale);
    case "goal":
      return [
        zone("Building", 0, scale.target * 0.7, OUTLINE),
        zone("Nearly",   scale.target * 0.7, scale.target, AMBER),
        zone("Goal met", scale.target, scale.target * 1.5, scale.color),
      ];
    case "personal": {
      const low  = values.length ? Math.min(...values) : 0;
      const high = values.length ? Math.max(...values) : 1;
      // A flat window still needs an axis with extent, or the arc draws nothing.
      const span = high - low > 0.001 ? high - low : Math.max(high, 1) * 0.1;
      if (values.length < 2) return [zone("Logged", low - span, high + span, scale.color)];
      const average = values.reduce((s, v) => s + v, 0) / values.length;
      return [
        zone("Below avg", low - span * 0.05, average, fade(scale.color, 0.55)),
        zone("Above avg", average, high + span * 0.05, scale.color),
      ];
    }
    default:
      throw new Error(`Unknown scale kind: ${scale.kind}`);
  }
}

/** A hex colour at an opacity, as #rrggbbaa. */
export function fade(hex, alpha) {
  const a = Math.round(Math.max(0, Math.min(1, alpha)) * 255).toString(16).padStart(2, "0");
  return `${hex.slice(0, 7)}${a}`;
}

/**
 * How far past the top of the scale a value sits, as an amount to draw on the
 * second ring — or null while it is still on the first. Saturated at one full
 * span: past two laps the shape's only job is to say "well over", and the
 * figure in the middle is the real number regardless.
 */
export function overflowOf(value, min, max) {
  const span = max - min;
  if (value == null || span <= 0) return null;
  const over = Math.min(Math.max(value - max, 0), span);
  return over > 0 ? over : null;
}

// ── What is current ──────────────────────────────────────────────────────────

/**
 * A calendar-cell Date as YYYY-MM-DD from its own fields — or, with no
 * argument, today in the account's zone (lib/today.js). Not toISOString,
 * which is the UTC date.
 */
export function localIso(d) {
  return d ? isoOfDay(d) : todayIso();
}

function daysBetween(fromIso, toIso) {
  const a = Date.UTC(...fromIso.split("-").map((n, i) => (i === 1 ? n - 1 : +n)));
  const b = Date.UTC(...toIso.split("-").map((n, i) => (i === 1 ? n - 1 : +n)));
  return Math.round((b - a) / 86_400_000);
}

/**
 * How long a watch reading stays current: a day, not none, because the day's
 * row does not exist until something is written to it — at nine in the
 * morning last night's resting heart rate is the current answer.
 */
export const FRESH_DAYS = 1;

/**
 * Whether a reading taken on `date` is still a reading about now.
 *
 * ## Why a dial may not simply show the last thing it heard
 *
 * Because on the phone it did, and said something false with it: SpO₂ had not
 * been recorded for most of a week and the dial went on showing the last
 * figure, in colour, under "Normal". Nothing was stale in any cache; the page
 * was reporting history as though it were current. So a reading expires and
 * the dial goes to an em dash — the history is still one click away.
 *
 * `freshDays` null never expires: weight is a standing fact about a body, not
 * an event. A date in the future is a clock ahead of the server's, which is not
 * staleness.
 */
export function isFresh(date, freshDays, today = localIso()) {
  if (!date) return false;
  if (freshDays == null) return true;
  return daysBetween(date, today) <= freshDays;
}

/**
 * One metric's readings over the window, oldest first, as parallel lists.
 * `select` maps a daily row to a number or null; nulls are skipped, so a day
 * without a reading is absent rather than zero.
 */
export function series(days, select) {
  const dates = [];
  const values = [];
  for (const day of days) {
    const v = select(day);
    if (v != null && Number.isFinite(v)) {
      dates.push(day.date);
      values.push(v);
    }
  }
  return { dates, values };
}

/** The newest reading, but only while it is still current — see isFresh. */
export function currentOf(trend, freshDays, today = localIso()) {
  const n = trend.values.length;
  if (!n) return null;
  return isFresh(trend.dates[n - 1], freshDays, today) ? trend.values[n - 1] : null;
}

/** The verdict a dial prints under its figure, or null with no reading. */
export function verdictFor(value, zones) {
  if (value == null || !zones?.length) return null;
  return zones.find(z => value < z.max) ?? zones[zones.length - 1];
}
