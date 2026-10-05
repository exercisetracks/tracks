// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Pure date/number helpers for the Dashboard page.
//
// These are stateless functions shared by the page and its widgets: they turn
// the selected period into API query dates, build the shared chart X axis, and
// format numbers. Kept out of the page file because they're self-contained and
// the X-axis builder in particular is long enough to obscure the page's layout.

import { PERIODS, DAY_MS } from "./constants";

// Turn the selected period into an ISO `after` date (YYYY-MM-DD) for the API,
// or null for the "lifetime" period (no lower bound).
export function afterDateFor(periodValue) {
  const p = PERIODS.find(x => x.value === periodValue);
  if (!p?.days) return null;
  const d = new Date();
  d.setDate(d.getDate() - p.days);
  return d.toISOString().slice(0, 10);
}

// Build the shared X-axis config (domain in ms timestamps + explicit ticks + label
// formatter) that both FitnessChart and WeeklyVolumeChart consume. Driving both
// charts off the same numeric X scale is what makes the Fitness chart's TSS spikes
// line up vertically with the Weekly Volume bars.
export function buildXAxisConfig(period, fallbackStartDate) {
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const maxMs = today.getTime();

  const p = PERIODS.find(x => x.value === period);
  const start = p?.days
    ? new Date(maxMs - (p.days - 1) * DAY_MS)
    : (fallbackStartDate ? new Date(fallbackStartDate + "T00:00:00") : new Date(maxMs - 365 * DAY_MS));
  start.setHours(0, 0, 0, 0);
  const minMs = start.getTime();
  const spanDays = (maxMs - minMs) / DAY_MS;

  const ticks = [];
  let format;

  if (spanDays > 2 * 365) {
    // Multi-year: one tick per Jan 1, labelled with the full year.
    const firstYear = start.getFullYear();
    const lastYear  = new Date(maxMs).getFullYear();
    for (let y = firstYear; y <= lastYear; y++) {
      const ms = new Date(y, 0, 1).getTime();
      if (ms >= minMs && ms <= maxMs) ticks.push(ms);
    }
    format = ms => String(new Date(ms).getFullYear());
  } else if (spanDays > 60) {
    // Multi-month: one tick per month start.
    const cur = new Date(start.getFullYear(), start.getMonth(), 1);
    if (cur.getTime() < minMs) cur.setMonth(cur.getMonth() + 1);
    while (cur.getTime() <= maxMs) {
      ticks.push(cur.getTime());
      cur.setMonth(cur.getMonth() + 1);
    }
    format = ms => new Date(ms).toLocaleDateString(undefined, { month: "short" });
  } else if (spanDays > 14) {
    // Weeks: one tick per Monday.
    const cur = new Date(start);
    while (cur.getDay() !== 1) cur.setDate(cur.getDate() + 1);
    while (cur.getTime() <= maxMs) {
      ticks.push(cur.getTime());
      cur.setDate(cur.getDate() + 7);
    }
    format = ms => new Date(ms).toLocaleDateString(undefined, { month: "short", day: "numeric" });
  } else {
    // Days: one tick per calendar day.
    const cur = new Date(start);
    while (cur.getTime() <= maxMs) {
      ticks.push(cur.getTime());
      cur.setDate(cur.getDate() + 1);
    }
    format = ms => new Date(ms).toLocaleDateString(undefined, { weekday: "short" });
  }

  return { domain: [minMs, maxMs], ticks, format };
}

// Format a number to a fixed number of decimals, returning null (so callers can
// fall back to an em dash) when the value is missing.
export function fmt(n, decimals = 1) {
  if (n == null) return null;
  return Number(n).toFixed(decimals);
}
