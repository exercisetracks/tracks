// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The course profile under the Race Plan map: elevation by distance, and the
// plan's target on a second axis — pace, power or HR ceiling, whichever the
// sport coaches by (courseProfile.js) — so a climb and what the plan asks of
// it read together. Hovering reports the point to the page, which marks the
// same spot on the map. The phone draws the same plot (CourseProfileChart.kt).
//
// Two y-axes, deliberately: the two measures share nothing but distance, and
// the point of the plot is reading one against the other at the same place.
// Elevation is a neutral area behind, the target a line in its metric's
// colour from the activity graphs; that keeps them apart for every colour
// vision (an orange elevation was too close to the HR line's red).
//
// Pace runs faster-up, as everywhere in the app: a climb shows as the pace
// line dipping under the hill.

import { useMemo } from "react";
import { ComposedChart, Area, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid } from "recharts";
import { courseProfile, targetMetric } from "./courseProfile";
import { fmtPace } from "./format";
import { CHART_GRID } from "../../design/chartGrid";

const KM_PER_MILE = 1.60934;
const ELEVATION = "#94a3b8";
const SERIES = {
  pace:  { color: "#ec4899", label: "Target pace" },
  power: { color: "#a78bfa", label: "Target power" },
  hr:    { color: "#ef4444", label: "HR ceiling" },
};
const TICK = { fontSize: 10, fill: "#94a3b8" };

export default function ElevationChart({ coursePath, laps, sport, imperial = false, onHover }) {
  const metric = targetMetric(sport, laps);
  const data = useMemo(
    // An open-water swim has no plot at all, not even its elevation.
    () => (sport === "swimming" ? null : courseProfile(coursePath, laps, metric)),
    [coursePath, laps, metric, sport],
  );
  if (!data) return null;

  const distUnit = imperial ? "mi" : "km";
  const toDist = (m) => m / 1000 / (imperial ? KM_PER_MILE : 1);
  const eleOf = (m) => (imperial ? m * 3.28084 : m);
  const eleUnit = imperial ? "ft" : "m";
  const fmtTarget = (v) =>
    v == null ? "—"
    : metric === "pace" ? fmtPace(v, imperial)
    : metric === "power" ? `${Math.round(v)} W`
    : `${Math.round(v)} bpm`;
  const series = metric && data.some((d) => d.target != null) ? SERIES[metric] : null;

  const rows = data.map((d) => ({ ...d, x: toDist(d.m), e: Math.round(eleOf(d.ele)) }));
  const eles = rows.map((r) => r.e);
  const lo = Math.min(...eles), hi = Math.max(...eles);
  const pad = Math.max(5, (hi - lo) * 0.1);

  const tip = ({ active, payload }) => {
    const p = active && payload?.[0]?.payload;
    if (!p) return null;
    return (
      <div className="bg-slate-900/90 text-white text-xs rounded-lg px-2.5 py-1.5 shadow-lg pointer-events-none tabular-nums">
        <p className="text-slate-400">{p.x.toFixed(1)} {distUnit}</p>
        <p>{p.e} {eleUnit}</p>
        {series && <p>{fmtTarget(p.target)}</p>}
      </div>
    );
  };

  return (
    <div className="mt-3" data-testid="course-profile">
      <div className="flex items-center gap-4 mb-1 text-[11px] text-slate-500 dark:text-slate-400">
        <span className="flex items-center gap-1.5">
          <span className="w-3 h-2 rounded-sm inline-block" style={{ background: ELEVATION, opacity: 0.5 }} />
          Elevation ({eleUnit})
        </span>
        {series && (
          <span className="flex items-center gap-1.5">
            <span className="w-3 h-0.5 rounded inline-block" style={{ background: series.color }} />
            {series.label}
          </span>
        )}
      </div>
      <div style={{ height: 160 }} className="w-full">
        <ResponsiveContainer width="100%" height="100%">
          <ComposedChart data={rows} margin={{ top: 4, right: 0, left: 0, bottom: 0 }}
            onMouseMove={(s) => {
              const p = s?.activePayload?.[0]?.payload;
              if (p) onHover?.([p.lat, p.lon]);
            }}
            onMouseLeave={() => onHover?.(null)}>
            <CartesianGrid {...CHART_GRID} />
            <XAxis dataKey="x" type="number" domain={["dataMin", "dataMax"]} tick={TICK} axisLine={false}
              tickLine={false} tickFormatter={(v) => `${Math.round(v)}`} unit={` ${distUnit}`} tickCount={6} />
            <YAxis yAxisId="ele" domain={[Math.floor(lo - pad), Math.ceil(hi + pad)]} tick={TICK}
              axisLine={false} tickLine={false} width={40} />
            {series && (
              <YAxis yAxisId="target" orientation="right" reversed={metric === "pace"} domain={["auto", "auto"]}
                tick={TICK} axisLine={false} tickLine={false} width={metric === "pace" ? 52 : 40}
                tickFormatter={(v) => (metric === "pace" ? fmtPace(v, imperial).split("/")[0] : Math.round(v))} />
            )}
            <Tooltip cursor={{ stroke: "#64748b", strokeWidth: 1, strokeDasharray: "3 3" }} content={tip} />
            <Area yAxisId="ele" type="monotone" dataKey="e" stroke={ELEVATION} strokeWidth={1}
              fill={ELEVATION} fillOpacity={0.25} dot={false} isAnimationActive={false} />
            {series && (
              <Line yAxisId="target" type="monotone" dataKey="target" stroke={series.color} strokeWidth={2}
                dot={false} isAnimationActive={false} connectNulls />
            )}
          </ComposedChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}
