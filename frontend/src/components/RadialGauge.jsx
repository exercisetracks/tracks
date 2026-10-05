// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Shared radial gauge: a 270° arc with classification bands shaded faintly
// behind a solid fill that runs from the scale minimum up to the current value,
// with the value and its band label in the centre. Used by the VO2max and
// Readiness dashboard widgets so the two gauges render identically — the size is
// the single SIZE constant below (fonts/offsets are scaled from the original
// 160px design via K so the proportions hold at any size).

const SIZE      = 104;
const STROKE    = 8;
const GAP       = 2;                 // pixels between arc segments
const R         = (SIZE - STROKE) / 2 - 2;
const CX        = SIZE / 2;
const CY        = SIZE / 2;
const START_DEG = 225;               // 7.5 o'clock — symmetric about 12
const ARC_SPAN  = 270;               // total sweep, leaving a 90° gap at bottom

// Resolve which classification band a value falls into (shared with the widgets
// for their history tooltips/legends). Returns a neutral swatch when null.
export function zoneFor(value, zones) {
  if (value == null) return { label: "—", color: "#94a3b8" };
  return zones.find(z => value < z.max) ?? zones[zones.length - 1];
}

function toRad(deg) { return (deg - 90) * Math.PI / 180; }

function polarXY(deg, r) {
  const rad = toRad(deg);
  return [CX + r * Math.cos(rad), CY + r * Math.sin(rad)];
}

function arcD(startDeg, endDeg, r) {
  if (Math.abs(endDeg - startDeg) < 0.01) return "";
  const [sx, sy] = polarXY(startDeg, r);
  const [ex, ey] = polarXY(endDeg, r);
  const large = (endDeg - startDeg) > 180 ? 1 : 0;
  return `M ${sx.toFixed(2)} ${sy.toFixed(2)} A ${r} ${r} 0 ${large} 1 ${ex.toFixed(2)} ${ey.toFixed(2)}`;
}

export default function RadialGauge({ value, min, max, zones, title }) {
  const zone   = zoneFor(value, zones);
  const gapDeg = (GAP / (2 * Math.PI * R)) * 360 / 2;

  const valueToDeg = v => {
    const clamped = Math.max(min, Math.min(max, v));
    return START_DEG + ((clamped - min) / (max - min)) * ARC_SPAN;
  };

  const indicDeg = value != null ? valueToDeg(value) : null;
  const [ix, iy] = indicDeg != null ? polarXY(indicDeg, R) : [null, null];
  const filledEnd = value != null ? valueToDeg(value) : null;

  return (
    <svg width={SIZE} height={SIZE} className="overflow-visible">
      {/* Track */}
      <path
        d={arcD(START_DEG, START_DEG + ARC_SPAN, R)}
        fill="none"
        stroke="var(--ring-track, #1e293b)"
        strokeWidth={STROKE}
        strokeLinecap="butt"
      />

      {/* Faint zone segments */}
      {zones.map(z => {
        const sd = valueToDeg(Math.max(z.min, min)) + gapDeg;
        const ed = valueToDeg(Math.min(z.max, max)) - gapDeg;
        if (ed <= sd) return null;
        return (
          <path key={z.label} d={arcD(sd, ed, R)} fill="none"
            stroke={z.color} strokeWidth={STROKE} strokeLinecap="butt" strokeOpacity={0.35} />
        );
      })}

      {/* Solid fill from START up to the current value */}
      {filledEnd != null && zones.map(z => {
        const zStart = Math.max(z.min, min);
        const zEnd   = Math.min(z.max, max);
        if (zEnd <= zStart) return null;
        const segStart = valueToDeg(zStart) + gapDeg;
        const segEnd   = Math.min(valueToDeg(zEnd) - gapDeg, filledEnd);
        if (segEnd <= segStart) return null;
        return (
          <path key={`fill-${z.label}`} d={arcD(segStart, segEnd, R)} fill="none"
            stroke={z.color} strokeWidth={STROKE} strokeLinecap="butt" />
        );
      })}

      {/* Indicator dot */}
      {ix != null && (
        <circle cx={ix} cy={iy} r={STROKE / 2 + 2} fill="#fff" opacity={0.95} />
      )}

      {/* Centre text */}
      <text x={CX} y={CY - 10} textAnchor="middle" fontSize={9} fill="#94a3b8"
        fontFamily="inherit" letterSpacing="0.04em" fontWeight="600">
        {title}
      </text>
      <text x={CX} y={CY + 10} textAnchor="middle" fontSize={22} fill={zone.color}
        fontFamily="inherit" fontWeight="700">
        {value != null ? Math.round(value) : "—"}
      </text>
      <text x={CX} y={CY + 22} textAnchor="middle" fontSize={9} fill={zone.color}
        fontFamily="inherit" fontWeight="600">
        {zone.label}
      </text>
    </svg>
  );
}
