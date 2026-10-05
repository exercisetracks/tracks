// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// FitnessChartParts.jsx
// ───────────────────────────────────────────────────────────────────────────
// Presentational sub-components for FitnessChart.jsx. Each is self-contained and
// receives everything it needs via props (axis maps, the chart's data array, or
// Recharts' tooltip payload) — none of them touch the parent chart's state, so
// pulling them out here introduces no prop-drilling and keeps the main file
// focused on data prep + render wiring.
//
//   • ZoneBackgrounds  — Form-zone tint rects drawn as raw SVG (no domain impact)
//   • ZoneColoredLine  — the TSB line, split + recoloured at zone boundaries
//   • InfoTooltip      — the little "?" hover popover used in each chart header
//   • LoadTooltip      — the shared CTL/ATL/TSB hover tooltip
//
// Behaviour is a verbatim lift-out from FitnessChart.jsx.
// ───────────────────────────────────────────────────────────────────────────

import { useState, useRef } from "react";
import { ZONES, BOUNDARIES, zoneFor, fmt, fmtTooltipDate } from "../fitnessChartData";

// Renders zone background rectangles as raw SVG using the live axis scale.
// Drawing via Customized (instead of ReferenceArea) means these rects can NEVER
// influence the YAxis domain — only the data does.
export function ZoneBackgrounds({ xAxisMap, yAxisMap }) {
  if (!xAxisMap || !yAxisMap) return null;
  const xAxis = Object.values(xAxisMap)[0];
  const yAxis = Object.values(yAxisMap)[0];
  if (!xAxis?.scale || !yAxis?.scale) return null;

  const xRange  = xAxis.scale.range();
  const xLeft   = Math.min(...xRange);
  const plotW   = Math.abs(xRange[1] - xRange[0]);

  const yRange  = yAxis.scale.range();
  const yTop    = Math.min(...yRange);
  const yBottom = Math.max(...yRange);

  return (
    <g>
      {ZONES.map(z => {
        const rawTop = isFinite(z.y2) ? yAxis.scale(z.y2) : yTop;
        const rawBot = isFinite(z.y1) ? yAxis.scale(z.y1) : yBottom;
        const top = Math.max(yTop, Math.min(yBottom, Math.min(rawTop, rawBot)));
        const bot = Math.max(yTop, Math.min(yBottom, Math.max(rawTop, rawBot)));
        if (bot <= top) return null;
        return (
          <rect key={z.label} x={xLeft} y={top} width={plotW} height={bot - top} fill={z.bg} />
        );
      })}
    </g>
  );
}

// Draws the TSB line as raw SVG <line> elements, splitting each day-to-day
// segment at zone boundaries so each sub-segment is coloured by its own zone.
export function ZoneColoredLine({ xAxisMap, yAxisMap, formData }) {
  if (!formData?.length || !xAxisMap || !yAxisMap) return null;

  const xAxis = Object.values(xAxisMap)[0];
  const yAxis = Object.values(yAxisMap)[0];
  if (!xAxis?.scale || !yAxis?.scale) return null;

  function px(d) {
    if (d.tsb == null) return null;
    const x = xAxis.scale(d.dateMs);
    const y = yAxis.scale(d.tsb);
    if (!Number.isFinite(x) || !Number.isFinite(y)) return null;
    return { x, y, tsb: d.tsb };
  }

  const segs = [];
  for (let i = 1; i < formData.length; i++) {
    const a = px(formData[i - 1]);
    const b = px(formData[i]);
    if (!a || !b) continue;

    const pts = [{ t: 0, tsb: a.tsb }];
    for (const bound of BOUNDARIES) {
      if ((a.tsb > bound) !== (b.tsb > bound)) {
        const t = (a.tsb - bound) / (a.tsb - b.tsb);
        if (t > 0 && t < 1) pts.push({ t, tsb: bound });
      }
    }
    pts.push({ t: 1, tsb: b.tsb });
    pts.sort((p, q) => p.t - q.t);

    for (let j = 0; j + 1 < pts.length; j++) {
      const p = pts[j], q = pts[j + 1];
      if (q.t - p.t < 1e-9) continue;
      segs.push({
        x1: a.x + p.t * (b.x - a.x), y1: a.y + p.t * (b.y - a.y),
        x2: a.x + q.t * (b.x - a.x), y2: a.y + q.t * (b.y - a.y),
        color: zoneFor((p.tsb + q.tsb) / 2).color,
      });
    }
  }

  return (
    <g>
      {segs.map((s, i) => (
        <line key={i} x1={s.x1} y1={s.y1} x2={s.x2} y2={s.y2}
          stroke={s.color} strokeWidth={2} strokeLinecap="round" />
      ))}
    </g>
  );
}

// A small "?" button that reveals an explanatory popover on hover. Flips to the
// left when it sits in the right half of the viewport so it never clips off-edge.
export function InfoTooltip({ children }) {
  const [open, setOpen]         = useState(false);
  const [openLeft, setOpenLeft] = useState(false);
  const btnRef                  = useRef(null);

  function handleEnter() {
    if (btnRef.current) {
      const rect = btnRef.current.getBoundingClientRect();
      setOpenLeft(rect.left > window.innerWidth / 2);
    }
    setOpen(true);
  }

  return (
    <span className="relative inline-flex shrink-0">
      <button
        ref={btnRef}
        onMouseEnter={handleEnter}
        onMouseLeave={() => setOpen(false)}
        className="w-4 h-4 rounded-full bg-slate-200 dark:bg-slate-700 text-slate-500 dark:text-slate-400 text-[10px] font-bold flex items-center justify-center hover:bg-slate-300 dark:hover:bg-slate-600 transition-colors"
      >
        ?
      </button>
      {open && (
        <div className={`absolute top-6 ${openLeft ? "right-0" : "left-0"} w-72 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-lg p-2.5 shadow-xl z-20 text-xs text-slate-600 dark:text-slate-300 space-y-2 pointer-events-none`}>
          {children}
        </div>
      )}
    </span>
  );
}

// Shared hover tooltip for both sub-charts: shows the date plus CTL / ATL / TSB
// (coloured by the hovered day's Form zone) and the raw TSS when present.
export const LoadTooltip = ({ active, payload }) => {
  if (!active || !payload?.length) return null;
  const row = payload[0]?.payload ?? {};
  const d   = Object.fromEntries(payload.map(p => [p.dataKey, p.value]));
  const tsb = row.tsb;
  const z   = zoneFor(tsb);
  return (
    <div className="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-lg px-2.5 py-1.5 shadow-lg text-xs pointer-events-none">
      <p className="font-semibold text-slate-700 dark:text-slate-200 mb-1.5">{fmtTooltipDate(row.dateMs)}</p>
      <div className="space-y-0.5">
        <p style={{ color: "#10b981" }}>Fitness (CTL) <strong>{fmt(d.ctl)}</strong></p>
        <p style={{ color: "#a855f7" }}>Fatigue (ATL) <strong>{fmt(d.atl)}</strong></p>
        <p style={{ color: z.color }}>
          Form (TSB) <strong>{fmt(tsb)}</strong>
          <span className="ml-1 opacity-70">— {z.label}</span>
        </p>
        {row.tss > 0 && <p className="text-slate-400 pt-0.5">TSS {fmt(row.tss, 0)}</p>}
      </div>
    </div>
  );
};
