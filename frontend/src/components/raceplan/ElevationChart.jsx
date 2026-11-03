// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The race profile on the Race Plan page: the plan's target by distance —
// pace, power or HR ceiling, whichever the sport coaches by (courseProfile.js)
// — over the course's elevation when one is loaded, and the per-lap HR
// ceiling as a dashed third line when the plan sets one. It is the page's one
// view of the targets: the lap table it replaced ran to forty rows on a
// marathon. Hovering reads any point (distance, elevation, target, the ground
// and its gradient, the HR ceiling) and reports it to the page, which marks
// the same spot on the map. The phone draws the same plot (CourseProfileChart.kt).
//
// Two y-axes, deliberately: elevation and the target share nothing but
// distance, and reading one against the other at the same place is the plot's
// point. The HR line has no axis of its own — a third scale would crowd the
// plot — so its shape is drawn and its value is in the readout. Elevation is
// a neutral area behind, the lines in their metrics' colours from the
// activity graphs; that keeps them apart for every colour vision (an orange
// elevation was too close to the HR line's red, and the dashed HR line is
// orange for the same reason beside pink pace).
//
// Target lines are steps (stepAfter): each lap's target holds to the next
// lap's boundary, where courseProfile puts a point (see there).
//
// Pace runs faster-up, as everywhere in the app: a climb shows as the pace
// line dipping under the hill.

import { useMemo, useState } from "react";
import { ComposedChart, Area, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid, ReferenceLine } from "recharts";
import { courseProfile, targetMetric } from "./courseProfile";
import { fmtPace, fmtGradient } from "./format";
import { CHART_GRID } from "../../design/chartGrid";

const KM_PER_MILE = 1.60934;
const ELEVATION = "#94a3b8";
const HR = "#ef4444";
// The dashed HR third line: orange, not the HR red, because red beside the
// pink pace line failed the palette check (ΔE 11, too close even with full
// colour vision); orange clears both pace and power.
const HR_LINE = "#f97316";
const SERIES = {
  pace:  { color: "#ec4899", label: "Target pace" },
  swim:  { color: "#ec4899", label: "Target pace" },
  power: { color: "#a78bfa", label: "Target power" },
  hr:    { color: HR, label: "HR ceiling" },
};
const TICK = { fontSize: 10, fill: "#94a3b8" };

/**
 * A domain at least `span` wide around the data: a flat course's even power or
 * a constant HR ceiling otherwise gives an axis of 255–256 with the line
 * pinned to an edge. The phone pads the same (CourseProfileChart.kt).
 */
const MIN_SPAN = { power: 20, hr: 10, pace: 30, swim: 300, ele: 10 };
function domainOf(values, span) {
  const lo = Math.min(...values), hi = Math.max(...values);
  if (hi - lo >= span) return ["auto", "auto"];
  const mid = (lo + hi) / 2;
  return [Math.floor(mid - span / 2), Math.ceil(mid + span / 2)];
}

/** A swim pace per 100 m from sec/km: "1:45/100m". */
export function swimPace(secPerKm) {
  const s = secPerKm / 10;
  return `${Math.floor(s / 60)}:${String(Math.round(s % 60)).padStart(2, "0")}/100m`;
}

/**
 * `metric` overrides the sport's own (a triathlon's swim leg is "swim");
 * `elevation={false}` draws the target alone even over a course, for a leg
 * the course's hills do not belong to.
 */
export default function ElevationChart({ coursePath, laps, sport, metric: forced, elevation = true,
  imperial = false, onHover, height = 160, handles = null, onHandles }) {
  // `handles`: metres along the course a triathlon's T1 and T2 sit at,
  // dragged to correct the automatic split; `onHandles` gets the new pair on
  // release. Pressing near a handle picks it up; anywhere else reads the plot.
  const [drag, setDrag] = useState(null);     // { i, m } while a handle is held
  const metric = forced ?? targetMetric(sport, laps);
  const data = useMemo(
    () => courseProfile(elevation ? coursePath : null, laps, metric),
    [coursePath, laps, metric, elevation],
  );
  if (!data) return null;

  const distUnit = imperial ? "mi" : "km";
  const toDist = (m) => m / 1000 / (imperial ? KM_PER_MILE : 1);
  const eleUnit = imperial ? "ft" : "m";
  const hasEle = data.some((d) => d.ele != null);
  const fmtTarget = (v) =>
    v == null ? "—"
    : metric === "pace" ? fmtPace(v, imperial)
    : metric === "swim" ? swimPace(v)
    : metric === "power" ? `${Math.round(v)} W`
    : `${Math.round(v)} bpm`;
  const series = metric && data.some((d) => d.target != null) ? SERIES[metric] : null;
  const hasHr = data.some((d) => d.hr != null);
  if (!series && !hasEle) return null;

  const rows = data.map((d) => ({
    ...d, x: toDist(d.m), e: d.ele != null ? Math.round(imperial ? d.ele * 3.28084 : d.ele) : null,
  }));
  const faster = metric === "pace" || metric === "swim";
  const toM = (x) => x * 1000 * (imperial ? KM_PER_MILE : 1);
  const span = rows.length ? rows[rows.length - 1].x - rows[0].x : 1;
  const shown = handles?.map((m, i) => (drag?.i === i ? drag.m : m));
  const nearest = (x) => {
    if (!shown) return null;
    let best = null;
    shown.forEach((m, i) => {
      const d = Math.abs(toDist(m) - x);
      if (d <= span * 0.04 && (best == null || d < best.d)) best = { i, d };
    });
    return best?.i ?? null;
  };

  const tip = ({ active, payload }) => {
    const p = active && payload?.[0]?.payload;
    if (!p) return null;
    const ground = p.lap?.label ?? (p.lap?.gradient ? fmtGradient(p.lap.gradient) : null);
    return (
      <div className="bg-slate-900/90 text-white text-xs rounded-lg px-2.5 py-1.5 shadow-lg pointer-events-none tabular-nums space-y-0.5">
        <p className="text-slate-400">{p.x.toFixed(1)} {distUnit}{ground ? ` · ${ground}` : ""}</p>
        {p.e != null && <p>{p.e} {eleUnit}</p>}
        {series && <p>{fmtTarget(p.target)}</p>}
        {p.hr != null && <p>HR ≤ {Math.round(p.hr)}</p>}
      </div>
    );
  };

  return (
    <div className="mt-3" data-testid="course-profile">
      <div className="flex items-center gap-4 mb-1 text-[11px] text-slate-500 dark:text-slate-400">
        {hasEle && (
          <span className="flex items-center gap-1.5">
            <span className="w-3 h-2 rounded-sm inline-block" style={{ background: ELEVATION, opacity: 0.5 }} />
            Elevation ({eleUnit})
          </span>
        )}
        {series && (
          <span className="flex items-center gap-1.5">
            <span className="w-3 h-0.5 rounded inline-block" style={{ background: series.color }} />
            {series.label}
          </span>
        )}
        {hasHr && (
          <span className="flex items-center gap-1.5">
            <span className="w-3 h-0 inline-block border-t-2 border-dashed" style={{ borderColor: HR_LINE }} />
            HR ceiling
          </span>
        )}
      </div>
      <div style={{ height }} className="w-full">
        <ResponsiveContainer width="100%" height="100%">
          <ComposedChart data={rows} margin={{ top: shown ? 18 : 4, right: 0, left: 0, bottom: 0 }}
            onMouseDown={(s) => {
              const i = s?.activeLabel != null ? nearest(s.activeLabel) : null;
              if (i != null) setDrag({ i, m: shown[i] });
            }}
            onMouseMove={(s) => {
              const p = s?.activePayload?.[0]?.payload;
              if (p?.lat != null) onHover?.([p.lat, p.lon]);
              if (drag && s?.activeLabel != null) {
                // T1 stays before T2, and both on the course.
                const m = toM(s.activeLabel);
                const lo = drag.i === 1 ? shown[0] + 50 : 0;
                const hi = drag.i === 0 ? shown[1] - 50 : toM(rows[rows.length - 1].x);
                setDrag({ i: drag.i, m: Math.max(lo, Math.min(hi, m)) });
              }
            }}
            onMouseUp={() => {
              if (!drag) return;
              const next = shown.map((m) => Math.round(m * 10) / 10);
              setDrag(null);
              onHandles?.(next);
            }}
            onMouseLeave={() => { onHover?.(null); setDrag(null); }}
            style={shown ? { cursor: drag ? "grabbing" : "default" } : undefined}>
            <CartesianGrid {...CHART_GRID} />
            <XAxis dataKey="x" type="number" domain={["dataMin", "dataMax"]} tick={TICK} axisLine={false}
              tickLine={false} unit={` ${distUnit}`} tickCount={6}
              // Tenths on a short race, where whole units repeat ("0 km, 0 km, 1 km").
              tickFormatter={(v) => (span < 10 ? v.toFixed(1) : `${Math.round(v)}`)} />
            {hasEle && (
              <YAxis yAxisId="ele" domain={domainOf(rows.filter((r) => r.e != null).map((r) => r.e), MIN_SPAN.ele)} tick={TICK} axisLine={false} tickLine={false} width={40} />
            )}
            {series && (
              <YAxis yAxisId="target" orientation={hasEle ? "right" : "left"} reversed={faster}
                domain={domainOf(rows.filter((r) => r.target != null).map((r) => r.target), MIN_SPAN[metric])} tick={TICK} axisLine={false} tickLine={false} width={faster ? 52 : 40}
                tickFormatter={(v) => (faster ? fmtTarget(v).split("/")[0] : Math.round(v))} />
            )}
            {hasHr && <YAxis yAxisId="hr" hide domain={domainOf(rows.filter((r) => r.hr != null).map((r) => r.hr), MIN_SPAN.hr)} />}
            <Tooltip cursor={{ stroke: "#64748b", strokeWidth: 1, strokeDasharray: "3 3" }} content={tip} />
            {hasEle && (
              <Area yAxisId="ele" type="monotone" dataKey="e" stroke={ELEVATION} strokeWidth={1}
                fill={ELEVATION} fillOpacity={0.25} dot={false} isAnimationActive={false} />
            )}
            {series && (
              <Line yAxisId="target" type="stepAfter" dataKey="target" stroke={series.color} strokeWidth={2}
                dot={false} isAnimationActive={false} connectNulls />
            )}
            {shown?.map((m, i) => (
              <ReferenceLine key={i} yAxisId={hasEle ? "ele" : "target"} x={toDist(m)} stroke="#475569"
                strokeWidth={drag?.i === i ? 3 : 2} style={{ cursor: "ew-resize" }}
                label={{ value: `T${i + 1}`, position: "top", fill: "#64748b", fontSize: 11, fontWeight: 600 }} />
            ))}
            {hasHr && (
              <Line yAxisId="hr" type="stepAfter" dataKey="hr" stroke={HR_LINE} strokeWidth={1.5}
                strokeDasharray="5 4" dot={false} isAnimationActive={false} connectNulls />
            )}
          </ComposedChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}
