// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Pure (non-React) helpers for the Health page: number/date formatting plus the
// sleep-stage chart data shaper. Kept framework-free so they're trivially
// testable and shareable across the Health sub-components.

export function fmt1(n) { return n != null ? Number(n).toFixed(1) : "—"; }

export function fmtDate(s) {
  if (!s) return "—";
  return new Date(s + "T00:00:00").toLocaleDateString(undefined, {
    month: "short", day: "numeric", year: "numeric",
  });
}

export function isoToday() { return new Date().toISOString().slice(0, 10); }

// Today's reading for a metric field: the value from the row dated `today`
// (null when today hasn't been synced/logged), plus its trend versus the mean
// of up to the previous 7 days with data. `transform` maps stored → display
// units (e.g. kg → lbs). The Today strip must show *today*, not the last value
// on record, so it reads the current day explicitly rather than "most recent".
export function todaySummary(metrics, field, today, transform) {
  const xf = transform || (v => v);
  const todayRow = metrics.find(m => m.date === today);
  const value = todayRow && todayRow[field] != null ? xf(todayRow[field]) : null;
  const prior = metrics
    .filter(m => m.date < today && m[field] != null)
    .slice(-7)
    .map(m => xf(m[field]));
  const base = prior.length ? prior.reduce((s, v) => s + v, 0) / prior.length : null;
  const trend = value != null && base != null ? value - base : null;
  return { value, trend };
}

// Convert an ISO "YYYY-MM-DD" date into a local-midnight timestamp (ms), so
// chart XAxis instances can use a numeric/time scale and space points by real
// elapsed time instead of by array index (data isn't imported every day).
export function dateToTs(d) { return new Date(d + "T00:00:00").getTime(); }

// Right edge for history-chart XAxis domains: always today, so a gap since
// the last import shows as trailing blank space instead of shrinking the axis.
export function todayTs() { return dateToTs(isoToday()); }

// Shape the last `n` nights of daily metrics into stacked-bar chart rows.
// Falls back to an "other" bucket when a night has a total but no stage
// breakdown (e.g. older device data).
export function sleepChartData(metrics, n) {
  return metrics
    .filter(m => m.sleep_hours != null)
    .slice(-n)
    .map(m => {
      const deep  = m.sleep_deep_hours  ?? 0;
      const rem   = m.sleep_rem_hours   ?? 0;
      const light = m.sleep_light_hours ?? 0;
      const total = m.sleep_hours       ?? 0;
      const hasBreakdown = deep + rem + light > 0.01;
      return {
        date:  dateToTs(m.date),
        score: m.sleep_score,
        total,
        deep:  hasBreakdown ? deep  : 0,
        rem:   hasBreakdown ? rem   : 0,
        light: hasBreakdown ? light : 0,
        // Catches totals with no stage breakdown (e.g. older device data)
        other: hasBreakdown ? 0 : total,
      };
    });
}
