// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Which dials the Health page shows, in which groups, against which scales —
// the same lists as the phone's HealthScreen.kt, so the two clients show the
// same reading the same way. The page owns fetching and layout; this owns what
// each dial means.

import {
  ACTIVE_CALORIE_GOAL, ACTIVE_COLOR, BODY_BATTERY_ZONES, CALORIE_COLOR, FRESH_DAYS,
  HRV_COLOR, HYDRATION_COLOR, HYDRATION_GOAL_ML, RESPIRATION_ZONES, RESTING_COLOR,
  RESTING_HR_ZONES, SLEEP_COLOR, SLEEP_GOAL_HOURS, SLEEP_ZONES, SPO2_ZONES, STEP_COLOR,
  STEP_GOAL, STRESS_ZONES, WEIGHT_COLOR, activityZones, currentOf, isFresh, series,
} from "./scales";
import { SLEEP_COLORS } from "./constants";
import { EXPLAIN } from "./explain";
import { SleepTrendChart } from "./HistoryCharts";
import StressHistory from "./StressHistory";
import { eatenSeries } from "./food";

const KG_TO_LB = 2.20462;

/** Whether a day holds anything a watch measured, as opposed to only what was logged by hand. */
export function hasMeasurements(d) {
  return d.resting_hr != null || d.hrv != null || d.sleep_hours != null || d.steps != null ||
    d.active_calories != null || d.avg_stress_level != null || d.avg_respiration_rate != null ||
    d.spo2 != null || d.body_battery_last != null;
}

/** The newest non-null value of a field anywhere in the window. */
function latestOf(days, field) {
  for (let i = days.length - 1; i >= 0; i--) if (days[i][field] != null) return days[i][field];
  return null;
}

/**
 * Calories burned: what the body spent existing, plus what was earned on top.
 *
 * One dial, two colours, because that is what the number is. Garmin's own
 * "calories" is the total, and showing only the active part made the app
 * disagree with the watch face about the same day. The resting part is always
 * filled — it is not an achievement — and the arc runs to resting plus a day's
 * active goal, so its remaining stretch is exactly the gap still to close.
 * Both parts are the *current* figures, so a day old enough to blank the
 * figure does not leave last week's split painted under an em dash.
 */
export function caloriesMetric(days) {
  const resting = currentOf(series(days, d => d.resting_calories), FRESH_DAYS);
  const active = currentOf(series(days, d => d.active_calories), FRESH_DAYS);
  // Totals per day, so the history plots the same number the dial shows.
  const trend = series(days, d =>
    d.active_calories == null && d.resting_calories == null
      ? null
      : (d.active_calories ?? 0) + (d.resting_calories ?? 0));

  return {
    key: "calories",
    label: "Calories",
    longLabel: "Calories burned",
    trend,
    unit: "kcal",
    scale: resting != null
      ? {
        kind: "stacked",
        parts: [
          { label: "Just existing", amount: resting, color: RESTING_COLOR },
          { label: "Earned", amount: active ?? 0, color: ACTIVE_COLOR },
        ],
        target: resting + ACTIVE_CALORIE_GOAL,
        remainderColor: ACTIVE_COLOR,
        remainderLabel: "Still to earn",
      }
      : { kind: "goal", target: ACTIVE_CALORIE_GOAL, color: ACTIVE_COLOR },
    // The arc's bands are the two halves of the total and cannot say whether
    // the day was a big one; these supply the word.
    verdict: activityZones(resting ?? 0),
    caption: active != null ? `+${Math.round(active)} active` : undefined,
    info: EXPLAIN.caloriesBurned,
  };
}

/**
 * Last night's sleep: the total on the dial, the stages in the arc.
 *
 * Duration bands on the ring answered "was that enough" and nothing else;
 * stacking the stages answers the other half in the same space — six and a
 * half hours with a solid block of deep is not the same night as six and a
 * half of almost all light. The verdict moves to the word under the figure.
 * Waking is deliberately not a band: it is not sleep, and the total in the
 * middle would stop matching the arc.
 */
export function sleepMetric(days, start) {
  const nights = days.filter(d => d.sleep_hours != null && d.sleep_hours > 0);
  const last = nights[nights.length - 1];
  // Only while it is last night: the stages on the arc have to describe the
  // same night as the figure, and a week-old night under an em dash would be
  // the stale-reading problem wearing a different shape.
  const night = last && isFresh(last.date, FRESH_DAYS) ? last : null;
  const parts = [];
  if (night) {
    const deep = night.sleep_deep_hours ?? 0;
    const rem = night.sleep_rem_hours ?? 0;
    const light = night.sleep_light_hours ?? 0;
    parts.push(
      { label: "Deep", amount: deep, color: SLEEP_COLORS.deep },
      { label: "REM", amount: rem, color: SLEEP_COLORS.rem },
      { label: "Light", amount: light, color: SLEEP_COLORS.light },
    );
    // Nights the watch timed but did not stage. Without this the arc would be
    // empty under a perfectly good total.
    const unstaged = night.sleep_hours - deep - rem - light;
    if (unstaged > 0.05) parts.push({ label: "Asleep", amount: unstaged, color: SLEEP_COLOR });
  }
  const hours = series(nights, d => d.sleep_hours);
  const score = series(nights, d => d.sleep_score);

  return {
    key: "sleep",
    label: "Sleep",
    trend: hours,
    unit: "h",
    decimals: 1,
    scale: { kind: "stacked", parts, target: SLEEP_GOAL_HOURS },
    verdict: SLEEP_ZONES,
    info: EXPLAIN.sleep,
    // Duration and score together. The stages have a card of their own on
    // the page, with room to draw them properly.
    chart: () => <SleepTrendChart hours={hours} score={score} start={start} />,
  };
}

/**
 * The page's dial groups. `days` is the window's rows, oldest first; `stress`
 * the intraday curve (empty on windows too long to draw one); `food` the meal
 * log, which is what the Eaten dial counts — see food.js.
 */
export function healthGroups({ days, imperial, start, stress, food = [] }) {
  const stressAverages = series(days, d => d.avg_stress_level);

  const activity = {
    title: "Activity",
    dataTour: "health-activity",
    missingHint: "These come from the watch's daily monitoring files, which sync separately from activities.",
    metrics: [
      {
        key: "steps", label: "Steps", unit: "",
        trend: series(days, d => d.steps),
        scale: { kind: "goal", target: STEP_GOAL, color: STEP_COLOR },
        info: EXPLAIN.steps,
      },
      caloriesMetric(days),
      {
        key: "body_battery", label: "Body Battery", unit: "",
        trend: series(days, d => d.body_battery_last),
        scale: { kind: "bands", zones: BODY_BATTERY_ZONES },
        info: EXPLAIN.bodyBattery,
        // The day's shape, behind the dial rather than in a card of its own.
        // Charge and drain do not reduce to high minus low — a day can rise
        // and fall several times — so they are worth carrying.
        breakdown: [
          ["High", latestOf(days, "body_battery_high")],
          ["Low", latestOf(days, "body_battery_low")],
          ["Charged", latestOf(days, "body_battery_charged")],
          ["Drained", latestOf(days, "body_battery_drained")],
        ].filter(([, v]) => v != null),
      },
    ],
  };

  const vitals = {
    title: "Vitals",
    dataTour: "health-vitals",
    missingHint: "Worn overnight, the watch records these while you sleep.",
    metrics: [
      sleepMetric(days, start),
      {
        key: "resting_hr", label: "Resting HR", longLabel: "Resting heart rate", unit: "bpm",
        trend: series(days, d => d.resting_hr),
        scale: { kind: "bands", zones: RESTING_HR_ZONES },
        info: EXPLAIN.restingHr,
      },
      {
        key: "hrv", label: "HRV", longLabel: "Heart rate variability", unit: "ms",
        trend: series(days, d => d.hrv),
        // Personal, deliberately: a good HRV is whatever is normal for you,
        // which is exactly how the watch's own HRV status works.
        scale: { kind: "personal", color: HRV_COLOR },
        info: EXPLAIN.hrv,
      },
      {
        key: "spo2", label: "SpO₂", longLabel: "Blood oxygen", unit: "%",
        trend: series(days, d => d.spo2),
        scale: { kind: "bands", zones: SPO2_ZONES },
        info: EXPLAIN.spo2,
      },
      {
        key: "respiration", label: "Respiration", unit: "br/min",
        trend: series(days, d => d.avg_respiration_rate),
        scale: { kind: "bands", zones: RESPIRATION_ZONES },
        info: EXPLAIN.respiration,
      },
      {
        key: "stress", label: "Stress", unit: "",
        trend: stressAverages,
        scale: { kind: "bands", zones: STRESS_ZONES },
        info: EXPLAIN.stress,
        chart: () => <StressHistory days={stress} averages={stressAverages} start={start} />,
      },
    ],
  };

  const body = {
    title: "Body",
    dataTour: "health-body",
    info: EXPLAIN.body,
    missingHint: "Nothing here is measured — log a day and the dials start from there.",
    metrics: [
      {
        key: "weight", label: "Weight", unit: imperial ? "lbs" : "kg", decimals: 1,
        trend: series(days, d => (d.weight_kg == null ? null : imperial ? d.weight_kg * KG_TO_LB : d.weight_kg)),
        // No bands, and there will not be any: a dial telling somebody their
        // body mass is red would be medically worthless and a nasty thing to
        // open a health page to.
        scale: { kind: "personal", color: WEIGHT_COLOR },
        info: EXPLAIN.weight,
        // The one reading that does not expire. A body mass is a standing
        // fact, and blanking it because nobody stood on the scales this
        // morning would be pedantry rather than honesty.
        freshDays: null,
      },
      {
        key: "hydration", label: "Water", longLabel: "Hydration", unit: "ml",
        trend: series(days, d => d.hydration_ml),
        scale: { kind: "goal", target: HYDRATION_GOAL_ML, color: HYDRATION_COLOR },
        info: EXPLAIN.hydration,
      },
      {
        key: "calories_in", label: "Eaten", longLabel: "Calories eaten", unit: "kcal",
        trend: eatenSeries(days, food, start),
        scale: { kind: "personal", color: CALORIE_COLOR },
        info: EXPLAIN.caloriesIn,
      },
    ],
  };

  return { activity, vitals, body };
}
