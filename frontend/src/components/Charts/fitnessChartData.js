// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// fitnessChartData.js
// ───────────────────────────────────────────────────────────────────────────
// Pure, React-free helpers, constants and formatters for FitnessChart.jsx.
//
// Everything here is context-free: no component state, no hooks, no JSX. It is
// the CTL/ATL/TSB ("fitness / fatigue / form") maths, the Form-zone definitions,
// the tick-generation logic, and the shared Recharts style constants. Splitting
// these out keeps FitnessChart.jsx focused on stateful render wiring while the
// number-crunching and configuration live somewhere small and easy to scan.
//
// Nothing in here changes behaviour — it is a verbatim lift-out of the logic
// that previously sat at the top of FitnessChart.jsx.
// ───────────────────────────────────────────────────────────────────────────
import { TSB_BANDS } from "../../spec/zones";

// Midnight-today as an epoch-ms timestamp, used to draw the "today" reference
// line. Computed once at module load — the chart never needs sub-day precision.
export const TODAY_MS = (() => {
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  return d.getTime();
})();

// Sample dense data down to at most `max` points — CTL/ATL are smooth EMAs so
// visual fidelity is preserved, and Recharts renders fewer SVG nodes.
export function thin(pts, max = 600) {
  if (!pts || pts.length <= max) return pts;
  const step = Math.ceil(pts.length / max);
  return pts.filter((_, i) => i % step === 0 || i === pts.length - 1);
}

// Form (TSB) zones, ordered high → low. Each has a translucent background tint
// (`bg`), a solid accent (`color`) for the line/legend, and a human `desc`.
//
// Derived from the shared spec (spec/zones.yaml) rather than written out here,
// so the thresholds the phone shows are the thresholds the browser shows. The
// spec uses null for the unbounded ends; Recharts wants ±Infinity, which is the
// only reason this is a mapping rather than a re-export.
export const ZONES = TSB_BANDS.bands.map((b) => ({
  key: b.key,
  label: b.label,
  y1: b.min === null ? -Infinity : b.min,
  y2: b.max === null ? Infinity : b.max,
  bg: b.bg,
  color: b.color,
  desc: b.desc,
}));

// Zone boundaries in descending order, used for line segment colouring.
export const BOUNDARIES = ZONES.map((z) => z.y1).filter((y) => Number.isFinite(y));

const UNKNOWN_ZONE = ZONES.find((z) => z.key === TSB_BANDS.unknown) ?? ZONES[2];

// Map a TSB value to its zone (defaults to the neutral Grey Zone when null).
export function zoneFor(tsb) {
  if (tsb == null) return UNKNOWN_ZONE;
  // Bands are ordered high → low with an exclusive lower bound, so the first
  // whose floor the value clears is the match.
  return ZONES.find((z) => tsb > z.y1) ?? ZONES[ZONES.length - 1];
}

// Format a numeric value to `d` decimals, or an em-dash when null/undefined.
export function fmt(v, d = 1) { return v != null ? Number(v).toFixed(d) : "—"; }

// Format an epoch-ms timestamp as e.g. "Mar 5, 2026" for tooltips.
export function fmtTooltipDate(ms) {
  return new Date(ms).toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" });
}

// Build 4–5 round-number Y ticks that comfortably cover the data range.
// Returns both the tick array and the [lo, hi] domain those ticks span.
export function niceYTicks(min, max, targetCount = 4) {
  const span = Math.max(max - min, 1);
  const roughStep = span / targetCount;
  const exp = Math.floor(Math.log10(roughStep));
  const norm = roughStep / Math.pow(10, exp);
  let step;
  if (norm < 1.5)      step = 1;
  else if (norm < 3)   step = 2;
  else if (norm < 7)   step = 5;
  else                 step = 10;
  step *= Math.pow(10, exp);

  const lo = Math.floor(min / step) * step;
  const hi = Math.ceil(max / step) * step;
  const ticks = [];
  for (let v = lo; v <= hi + step * 0.5; v += step) ticks.push(Math.round(v * 100) / 100);
  return { ticks, domain: [lo, hi] };
}

// ── Shared Recharts style constants ─────────────────────────────────────────
// Kept together so both sub-charts (Training Load + Form) stay visually in sync.

export const CURSOR = { stroke: "#64748b60", strokeWidth: 1, strokeDasharray: "3 3" };
export const TICK   = { fontSize: 10, fill: "var(--chart-text)" };
export const AXIS   = { tick: TICK, tickLine: false, axisLine: false };

// Right margin of 52 matches WeeklyVolumeChart's right axis (16 outer + 36 axis)
// so the two charts' plot areas align horizontally on the page.
export const MARGIN = { top: 4, right: 52, bottom: 0, left: 0 };
export const Y_WIDTH = 45;
