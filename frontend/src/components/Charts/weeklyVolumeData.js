// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Pure helpers for WeeklyVolumeChart.jsx.
import { isoOfDay } from "../../lib/today";

const mondayOf = ms => {
  const d = new Date(ms);
  d.setHours(0, 0, 0, 0);
  d.setDate(d.getDate() - ((d.getDay() + 6) % 7));
  return d;
};

// Every week of the window, the empty ones as zeros.
//
// The server sends a row only for a week that had an activity in it, so a
// fortnight off was simply absent: the duration line ran straight from the
// week before to the week after, as if the time off had been training at the
// average of the two. Filled from the week containing `fromMs` (or the first
// row, when there is no window start) through the week containing `toMs`, so
// time off at either end of the window drops to zero too rather than the line
// stopping short. The phone does the same — see `filledWeeks` in Charts.kt.
export function fillEmptyWeeks(rows, fromMs, toMs) {
  if (!rows.length) return rows;
  const byWeek = new Map(rows.map(r => [r.week_start, r]));
  const first = new Date(rows[0].week_start + "T00:00:00");
  const last = new Date(rows[rows.length - 1].week_start + "T00:00:00");
  const cur = fromMs != null ? mondayOf(Math.min(fromMs, first.getTime())) : first;
  const end = toMs != null ? mondayOf(Math.max(toMs, last.getTime())) : last;
  const out = [];
  // Stepped by calendar day rather than 7 × 86 400 000 ms, which drifts an
  // hour off Monday midnight across a daylight-saving change.
  while (cur.getTime() <= end.getTime()) {
    const iso = isoOfDay(cur);
    out.push(byWeek.get(iso) ?? { week_start: iso, distance_km: 0, duration_hours: 0, activity_count: 0 });
    cur.setDate(cur.getDate() + 7);
  }
  return out;
}

// How far into each gap the curve's control points sit, as a share of it.
// The phone uses the same number (GENTLE_SMOOTHING in Charts.kt), so the two
// clients draw the same shape.
export const GENTLE_SMOOTHING = 0.2;

// A d3 curve factory, for Recharts' `type`: straight runs with rounded joins.
//
// `monotone` — the fitness chart's curve — looks right over daily points and
// far too heavy over weekly ones: with a dozen points it draws each week as a
// bell, and the eye reads the curve rather than the numbers. Here both
// control points of a segment sit level with its ends, a fifth of the gap in,
// so a segment never rises above its higher end or dips below its lower one —
// the way into an empty week stops at zero rather than going under it.
export function gentleCurve(context) {
  let x0, y0, started;
  return {
    areaStart() {},
    areaEnd() {},
    lineStart() { started = false; },
    lineEnd() {},
    point(x, y) {
      x = +x; y = +y;
      if (!started) {
        context.moveTo(x, y);
        started = true;
      } else {
        const d = (x - x0) * GENTLE_SMOOTHING;
        context.bezierCurveTo(x0 + d, y0, x - d, y, x, y);
      }
      x0 = x; y0 = y;
    },
  };
}
