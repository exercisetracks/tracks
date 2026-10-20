// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The Health page's step, intensity-minute and floor goals: the account's own
// number, or Auto. A port of the phone's HealthGoals.kt, which has the
// research behind each band; the two must agree, since both read the same
// synced settings and should fill their dials toward the same number.

export const DEFAULT_GOALS = { steps: 8000, intensityWeekly: 150, floors: 10 };
export const WINDOW_DAYS = 28;
const MIN_DAYS = 7;

const median = (values) => {
  const s = [...values].sort((a, b) => a - b);
  const mid = Math.floor(s.length / 2);
  return s.length % 2 ? s[mid] : (s[mid - 1] + s[mid]) / 2;
};
// Kotlin's roundToInt rounds halves up, as Math.round does for positives.
const roundTo = (v, step) => Math.round(v / step) * step;
const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

const intensityOf = (d) =>
  d.intensity_moderate_min == null && d.intensity_vigorous_min == null
    ? null
    : (d.intensity_moderate_min ?? 0) + 2 * (d.intensity_vigorous_min ?? 0);

/** Auto, from the finished days of the last four weeks (today left out). */
export function autoGoals(days) {
  const worn = days.filter((d) => (d.steps ?? 0) > 0 || intensityOf(d) != null);
  const steps = days.map((d) => d.steps).filter((s) => s != null && s > 0);
  const floors = days.map((d) => d.floors_climbed).filter((f) => f != null);
  return {
    steps: steps.length < MIN_DAYS ? DEFAULT_GOALS.steps
      : clamp(roundTo(median(steps) * 1.05, 500), 5000, 12000),
    intensityWeekly: worn.length < MIN_DAYS ? DEFAULT_GOALS.intensityWeekly
      : clamp(roundTo((worn.reduce((n, d) => n + (intensityOf(d) ?? 0), 0) * 7) / worn.length * 1.1, 10), 150, 300),
    floors: floors.length < MIN_DAYS ? DEFAULT_GOALS.floors
      : clamp(Math.round(median(floors) * 1.1), 5, 20),
  };
}

/** The finished days Auto reads, from every day the page has. */
export function goalWindow(allDays, today) {
  const [y, m, d] = today.split("-").map(Number);
  const t = new Date(Date.UTC(y, m - 1, d - WINDOW_DAYS));
  const pad = (v) => String(v).padStart(2, "0");
  const from = `${t.getUTCFullYear()}-${pad(t.getUTCMonth() + 1)}-${pad(t.getUTCDate())}`;
  return allDays.filter((day) => day.date >= from && day.date < today);
}

/** The goals in force: manual where the setting says so, Auto otherwise. */
export function resolveGoals(auto, settings) {
  const manual = (key) =>
    settings?.[`${key}_mode`] === "manual" && settings?.[`${key}_manual`] != null
      ? Number(settings[`${key}_manual`]) : null;
  return {
    steps: manual("step_goal") ?? auto.steps,
    intensityWeekly: manual("intensity_goal") ?? auto.intensityWeekly,
    floors: manual("floors_goal") ?? auto.floors,
  };
}
