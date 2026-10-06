// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Shared radial gauge: a 270° arc with classification bands shaded faintly
// behind a solid fill that runs from the scale minimum up to the current value,
// with the value and its band label in the centre. Used by the VO2max and
// Readiness dashboard widgets and by every dial on the Health page, so they all
// render identically. Fonts and offsets are scaled from the original 104px
// design via K, so the proportions hold at any size.
//
// ## Going past the end (`lap`)
//
// Some dials have a top that is passed regularly — a long day beats a step
// goal. Clamping turns 24,000 and 15,000 steps into the same picture on the
// page whose job is telling them apart. So with `lap`, the arc laps: past the
// maximum a thinner second ring appears outside the first and fills through
// the same bands again, while the first stays full. Its room is always
// reserved, so a dial does not resize the moment somebody crosses their goal,
// and it stops at two rings — the figure in the middle is the real number.
// The phone's RadialGauge does the same; see Charts.kt there.

import { overflowOf } from "./health/scales";

const BASE      = 104;
const START_DEG = 225;               // 7.5 o'clock — symmetric about 12
const ARC_SPAN  = 270;               // total sweep, leaving a 90° gap at bottom
const GAP       = 2;                 // pixels between arc segments
const LAP_STROKE = 0.45;             // the lap ring, as a fraction of the main stroke
const LAP_GAP   = 2;

// Resolve which classification band a value falls into (shared with the widgets
// for their history tooltips/legends). Returns a neutral swatch when null.
export function zoneFor(value, zones) {
  if (value == null) return { label: "—", color: "#94a3b8" };
  return zones.find(z => value < z.max) ?? zones[zones.length - 1];
}

/** The figure in the middle: whole numbers grouped, one decimal when asked. */
export function gaugeFigure(value, decimals = 0) {
  if (value == null) return "—";
  if (decimals === 0) return Math.round(value).toLocaleString();
  const r = Math.round(value * 10) / 10;
  return Number.isInteger(r) ? String(r) : r.toFixed(1);
}

export default function RadialGauge({
  value, min, max, zones, title,
  size = BASE,
  stroke = 8,
  unit = "",
  decimals = 0,
  /** Replaces the band's name under the figure, where that says more. */
  caption,
  /**
   * The caption's (and figure's) colour, when the band under the value is the
   * wrong one to borrow — a stacked arc's bands are its parts, so the verdict
   * beside the total has to be coloured by the scale that produced it.
   */
  captionColor,
  lap = false,
}) {
  const lo = min ?? zones[0].min;
  const hi = max ?? zones[zones.length - 1].max;
  const zone = zoneFor(value, zones);
  const K = size / BASE;
  const cx = size / 2;
  const cy = size / 2;
  const lapWidth = stroke * LAP_STROKE;
  // Room for the lap ring, held back whether or not it is used.
  const reserved = lap ? lapWidth + LAP_GAP : 2;
  const R = (size - stroke) / 2 - reserved;
  const lapR = (size - lapWidth) / 2;
  const gapDeg = (GAP / (2 * Math.PI * R)) * 360 / 2;

  const polar = (deg, r) => {
    const rad = (deg - 90) * Math.PI / 180;
    return [cx + r * Math.cos(rad), cy + r * Math.sin(rad)];
  };
  const arcD = (startDeg, endDeg, r) => {
    if (Math.abs(endDeg - startDeg) < 0.01) return "";
    const [sx, sy] = polar(startDeg, r);
    const [ex, ey] = polar(endDeg, r);
    const large = (endDeg - startDeg) > 180 ? 1 : 0;
    return `M ${sx.toFixed(2)} ${sy.toFixed(2)} A ${r} ${r} 0 ${large} 1 ${ex.toFixed(2)} ${ey.toFixed(2)}`;
  };
  const valueToDeg = v => {
    const clamped = Math.max(lo, Math.min(hi, v));
    return START_DEG + ((clamped - lo) / (hi - lo)) * ARC_SPAN;
  };

  const filledEnd = value != null ? valueToDeg(value) : null;
  const over = lap ? overflowOf(value, lo, hi) : null;
  const overEnd = over != null ? valueToDeg(lo + over) : null;
  const marker = overEnd ?? filledEnd;
  const [ix, iy] = marker != null ? polar(marker, overEnd != null ? lapR : R) : [null, null];
  const markerR = (overEnd != null ? lapWidth : stroke) / 2 + 2;

  const segments = zones.map(z => {
    const zStart = Math.max(z.min, lo);
    const zEnd = Math.min(z.max, hi);
    return { z, start: valueToDeg(zStart) + gapDeg, end: valueToDeg(zEnd) - gapDeg, empty: zEnd <= zStart };
  }).filter(s => !s.empty && s.end > s.start);

  const tint = captionColor ?? zone.color;

  // The figure shrinks to fit inside the ring rather than spilling across it:
  // "13,400" fits where "2,049 kcal" does not. Widths are estimated from
  // average glyph proportions, which is close enough to keep clear of the arc.
  const figure = gaugeFigure(value, decimals);
  const showUnit = Boolean(unit) && value != null;
  const inner = (R - stroke / 2) * 2 * 0.86;
  const unitSize = 10 * K;
  const wanted = figure.length * 22 * K * 0.58 + (showUnit ? unit.length * unitSize * 0.55 + 2 * K : 0);
  const figureSize = 22 * K * Math.min(1, inner / Math.max(wanted, 1));
  const figureY = title ? cy + 10 * K : cy + 4 * K;

  return (
    <svg width={size} height={size} className="overflow-visible">
      {/* Track */}
      <path d={arcD(START_DEG, START_DEG + ARC_SPAN, R)} fill="none"
        stroke="var(--ring-track, #1e293b)" strokeWidth={stroke} strokeLinecap="butt" />

      {/* Faint zone segments */}
      {segments.map(({ z, start, end }) => (
        <path key={z.label} d={arcD(start, end, R)} fill="none"
          stroke={z.color} strokeWidth={stroke} strokeLinecap="butt" strokeOpacity={0.35} />
      ))}

      {/* Solid fill from START up to the current value — all of it once lapped */}
      {filledEnd != null && segments.map(({ z, start, end }) => {
        const segEnd = overEnd != null ? end : Math.min(end, filledEnd);
        if (segEnd <= start) return null;
        return (
          <path key={`fill-${z.label}`} d={arcD(start, segEnd, R)} fill="none"
            stroke={z.color} strokeWidth={stroke} strokeLinecap="butt" />
        );
      })}

      {/* The second lap, outside the first. No track: an empty outer ring on
          every dial would be a permanent promise of a level nobody reached. */}
      {overEnd != null && segments.map(({ z, start, end }) => {
        const segEnd = Math.min(end, overEnd);
        if (segEnd <= start) return null;
        return (
          <path key={`lap-${z.label}`} d={arcD(start, segEnd, lapR)} fill="none"
            stroke={z.color} strokeWidth={lapWidth} strokeLinecap="butt" />
        );
      })}

      {/* Indicator dot, on whichever ring the value is actually on */}
      {ix != null && (
        // Dark on the light theme: white vanished against the pale bands.
        <circle cx={ix} cy={iy} r={markerR} className="fill-slate-800 dark:fill-white" opacity={0.95} />
      )}

      {/* Centre text. With no title the figure sits on the centre line —
          the Health dials carry their label under the ring instead, where a
          long name like "Body Battery" does not have to fit inside it. */}
      {title && (
        <text x={cx} y={cy - 10 * K} textAnchor="middle" fontSize={9 * K} fill="#94a3b8"
          fontFamily="inherit" letterSpacing="0.04em" fontWeight="600">
          {title}
        </text>
      )}
      <text x={cx} y={figureY} textAnchor="middle" fontSize={figureSize} fill={tint}
        fontFamily="inherit" fontWeight="700">
        {figure}
        {showUnit && (
          <tspan fontSize={unitSize} fontWeight="600" fill="#94a3b8" dx={2 * K}>{unit}</tspan>
        )}
      </text>
      {/* Only with a reading to classify: under an em dash, a second dash
          reads as a broken label rather than as absence. */}
      {value != null && (
        <text x={cx} y={figureY + 12 * K} textAnchor="middle" fontSize={9 * K} fill={tint}
          fontFamily="inherit" fontWeight="600">
          {caption ?? zone.label}
        </text>
      )}
    </svg>
  );
}
