// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Pure (non-React) formatting + maths helpers for the Race Plan page: unit-aware
// pace/distance/gradient formatters and the frontend-only lap recomputation that
// lets the split slider update targets without a backend round-trip. Kept
// framework-free so they're trivially testable.

const KM_PER_MILE = 1.60934;

// Largest pace/power swing the split slider can apply, end to end. Must match
// the backend so generated and slider-previewed targets agree.
export const MAX_SPREAD = 0.08;

// ── Unit helpers ──────────────────────────────────────────────────────────────

export function fmtPace(secPerKm, imperial = false) {
  if (!secPerKm || secPerKm <= 0) return "—";
  const sec = imperial ? secPerKm * KM_PER_MILE : secPerKm;
  const m   = Math.floor(sec / 60);
  const s   = Math.round(sec % 60);
  return `${m}:${String(s).padStart(2, "0")}/${imperial ? "mi" : "km"}`;
}

export function fmtDist(meters, imperial = false) {
  if (imperial) return `${(meters / 1000 / KM_PER_MILE).toFixed(2)} mi`;
  return `${(meters / 1000).toFixed(2)} km`;
}

export function fmtDistShort(meters, imperial = false) {
  if (imperial) return `${(meters / 1000 / KM_PER_MILE).toFixed(1)} mi`;
  return `${(meters / 1000).toFixed(1)} km`;
}

// ── Gradient colour / formatting for pace-table rows ──────────────────────────

export function gradientColor(g) {
  if (g >  0.05) return "text-red-600 dark:text-red-400";
  if (g >  0.02) return "text-orange-500 dark:text-orange-400";
  if (g < -0.05) return "text-blue-600 dark:text-blue-400";
  if (g < -0.02) return "text-sky-500 dark:text-sky-400";
  return "text-slate-500 dark:text-slate-400";
}

export function fmtGradient(g) {
  const pct = (g * 100).toFixed(1);
  return g >= 0 ? `+${pct}%` : `${pct}%`;
}

// ── Weather time-adjustment formatting ────────────────────────────────────────

// Render a +/- seconds adjustment as "+12s" or "−1:05"; null when negligible.
export function fmtAdjSec(sec) {
  if (sec == null || Math.abs(sec) < 1) return null;
  const sign = sec > 0 ? "+" : "−";
  const abs  = Math.abs(Math.round(sec));
  if (abs < 60) return `${sign}${abs}s`;
  const m = Math.floor(abs / 60), s = abs % 60;
  return `${sign}${m}:${String(s).padStart(2, "0")}`;
}

// ── Realtime lap recomputation (frontend-only, no backend round-trip) ─────────

// Re-derive per-lap pace/power targets for the current split-slider value so the
// table updates live while dragging. `splitSpread` is the slider's -1..1 value;
// a linear ramp across the laps applies up to ±MAX_SPREAD to the flat-equivalent
// pace (and scales target watts proportionally for cycling).
export function recomputeLaps(baseLaps, predictedSec, splitSpread, imperial) {
  if (!baseLaps?.length || !predictedSec) return baseLaps ?? [];
  const n   = baseLaps.length;
  const mid = (n - 1) / 2;
  const slope = -splitSpread * MAX_SPREAD * 2 / Math.max(n - 1, 1);
  const ramp  = baseLaps.map((_, i) => 1 + slope * (i - mid));

  const denom = baseLaps.reduce((s, l, i) =>
    s + l.distance_m * (l.grade_multiplier ?? 1) * ramp[i], 0) / 1000;
  const baseFlatPace = predictedSec / denom;

  return baseLaps.map((lap, i) => {
    const tgt = baseFlatPace * (lap.grade_multiplier ?? 1) * ramp[i];
    const gap = baseFlatPace * ramp[i];
    // For cycling: scale watts proportionally to the ramp factor
    const scaledWatts = lap.target_watts ? Math.round(lap.target_watts * ramp[i]) : null;
    return {
      ...lap,
      target_sec_per_km: tgt,
      target_pace:    fmtPace(tgt, imperial),
      grade_adj_sec:  gap,
      grade_adj_pace: fmtPace(gap, imperial),
      ...(scaledWatts ? { target_watts: scaledWatts } : {}),
    };
  });
}
