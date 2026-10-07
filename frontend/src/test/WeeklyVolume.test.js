// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from "vitest";
import { fillEmptyWeeks, gentleCurve } from "../components/Charts/weeklyVolumeData";
import { isoOfDay } from "../lib/today";

// Weeks with no activity are zeros on the weekly volume chart, not holes: the
// server sends no row for an empty week, and the duration line used to run
// straight across the gap as if the time off had been training.

// Anchored to the current week, never a fixed date.
const monday = (() => {
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  d.setDate(d.getDate() - ((d.getDay() + 6) % 7));
  return d;
})();
const weeksAgo = n => {
  const d = new Date(monday);
  d.setDate(d.getDate() - 7 * n);
  return d;
};
const row = (d, hours = 2) => ({ week_start: isoOfDay(d), distance_km: 20, duration_hours: hours, activity_count: 1 });

describe("fillEmptyWeeks", () => {
  it("an empty week in the middle is a zero", () => {
    const out = fillEmptyWeeks([row(weeksAgo(2)), row(monday)], null, null);

    expect(out.map(r => r.week_start)).toEqual([weeksAgo(2), weeksAgo(1), monday].map(isoOfDay));
    expect(out[1].duration_hours).toBe(0);
    expect(out[1].distance_km).toBe(0);
  });

  it("time off at the end of the window drops to zero", () => {
    const today = new Date(monday);
    today.setDate(today.getDate() + 3);
    const out = fillEmptyWeeks([row(weeksAgo(3))], null, today.getTime());

    expect(out).toHaveLength(4);
    expect(out.at(-1).week_start).toBe(isoOfDay(monday));
    expect(out.slice(1).every(r => r.duration_hours === 0)).toBe(true);
  });

  it("time off at the start of the window drops to zero", () => {
    const start = weeksAgo(4);
    start.setDate(start.getDate() + 2);
    const out = fillEmptyWeeks([row(weeksAgo(1))], start.getTime(), monday.getTime());

    expect(out[0].week_start).toBe(isoOfDay(weeksAgo(4)));
    expect(out).toHaveLength(5);
  });

  it("keeps real rows as they came", () => {
    const rows = [row(weeksAgo(1), 3.5), row(monday, 1.25)];

    expect(fillEmptyWeeks(rows, null, null)).toEqual(rows);
  });

  it("leaves an empty window empty", () => {
    expect(fillEmptyWeeks([], 0, Date.now())).toEqual([]);
  });
});

describe("gentleCurve", () => {
  const trace = pts => {
    const calls = [];
    const ctx = {
      moveTo: (...a) => calls.push(["M", ...a]),
      bezierCurveTo: (...a) => calls.push(["C", ...a]),
    };
    const curve = gentleCurve(ctx);
    curve.lineStart();
    pts.forEach(([x, y]) => curve.point(x, y));
    curve.lineEnd();
    return calls;
  };

  it("passes through every point", () => {
    const calls = trace([[0, 50], [100, 0], [200, 30]]);

    expect(calls[0]).toEqual(["M", 0, 50]);
    expect(calls.slice(1).map(c => c.slice(5))).toEqual([[100, 0], [200, 30]]);
  });

  it("never overshoots a segment's ends", () => {
    // Control points level with the ends are what keep the line into an
    // empty week from dipping below zero.
    const [, [, , c1y, , c2y]] = trace([[0, 80], [100, 0]]);

    expect(c1y).toBe(80);
    expect(c2y).toBe(0);
  });

  it("rounds only the joins, a fifth of the way in", () => {
    const [, [, c1x, , c2x]] = trace([[0, 80], [100, 0]]);

    expect(c1x).toBeCloseTo(20);
    expect(c2x).toBeCloseTo(80);
  });
});
