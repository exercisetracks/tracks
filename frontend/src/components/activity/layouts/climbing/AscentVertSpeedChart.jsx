// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * AscentVertSpeedChart — Climbing-specific "Ascent & Vert Speed per Route" chart.
 *
 * A purely presentational Recharts ComposedChart extracted verbatim from
 * ClimbingLayout so the layout file stays focused on data derivation + page
 * structure. This chart is unique to route climbing (it is the only activity
 * layout that uses a ComposedChart) and is therefore kept under climbing/
 * rather than shared/.
 *
 * Renders one bar per route (height = ascent, green if the route was sent)
 * overlaid with a line for average vertical speed on a secondary Y axis.
 *
 * Props:
 *   - ascentPerClimb: array of { idx, ascent, vert, send } rows (already
 *       unit-converted by the parent for imperial/metric).
 *   - imperial: boolean, drives unit labels only (data is pre-converted).
 *   - hasResults: boolean, controls whether the "Send" legend swatch shows.
 */
import React from "react";
import {
  ComposedChart,
  Bar,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  Cell,
} from "recharts";
import { ChartCard } from "../../utils/chartHelpers.jsx";
import { CHART_GRID } from "../../../../design/chartGrid";

export function AscentVertSpeedChart({ ascentPerClimb, imperial, hasResults }) {
  return (
    <ChartCard title={`Ascent & Vert Speed per Route`}>
      <ResponsiveContainer width="100%" height={200}>
        <ComposedChart data={ascentPerClimb} margin={{ top: 4, right: 40, bottom: 0, left: 0 }}>
          <CartesianGrid {...CHART_GRID} />
          <XAxis
            dataKey="idx"
            tick={{ fontSize: 10, fill: "#94a3b8" }}
            axisLine={false}
            tickLine={false}
            label={{ value: "Route #", position: "insideBottom", fill: "#94a3b8", fontSize: 9, dy: 6 }}
          />
          {/* Primary axis: total ascent per route */}
          <YAxis
            yAxisId="ascent"
            tick={{ fontSize: 10, fill: "#f97316" }}
            axisLine={false}
            tickLine={false}
            width={42}
            label={{ value: imperial ? "ft" : "m", angle: -90, position: "insideLeft", fill: "#f97316", fontSize: 9, dy: 12 }}
          />
          {/* Secondary axis: average vertical speed per route */}
          <YAxis
            yAxisId="vert"
            orientation="right"
            tick={{ fontSize: 10, fill: "#22d3ee" }}
            axisLine={false}
            tickLine={false}
            width={42}
            label={{ value: imperial ? "ft/s" : "m/s", position: "insideRight", fill: "#22d3ee", fontSize: 9 }}
          />
          <Tooltip
            contentStyle={{ background: "#0f172a", border: "none", borderRadius: 8, fontSize: 11 }}
            labelStyle={{ color: "#94a3b8" }}
            labelFormatter={(i) => `Route ${i}`}
            formatter={(v, name) => {
              if (name === "ascent") return [`${v} ${imperial ? "ft" : "m"}`, "Ascent"];
              if (name === "vert") return [`${v} ${imperial ? "ft/s" : "m/s"}`, "Vert Speed"];
              return [v, name];
            }}
          />
          {/* Send routes are highlighted green; attempts stay orange */}
          <Bar yAxisId="ascent" dataKey="ascent" radius={[3, 3, 0, 0]} name="ascent">
            {ascentPerClimb.map((d, i) => (
              <Cell key={i} fill={d.send ? "#10b981" : "rgba(249,115,22,0.7)"} />
            ))}
          </Bar>
          <Line yAxisId="vert" type="monotone" dataKey="vert" stroke="#22d3ee" strokeWidth={2} dot={{ fill: "#22d3ee", r: 3 }} name="vert" />
        </ComposedChart>
      </ResponsiveContainer>
      {/* Legend — the Send swatch only appears when result data is present */}
      <div className="flex items-center justify-center gap-4 mt-1 text-[10px] text-slate-500 dark:text-slate-400">
        {hasResults && (
          <span className="flex items-center gap-1">
            <span className="inline-block w-2.5 h-2.5 rounded-sm bg-accent-500" />Send
          </span>
        )}
        <span className="flex items-center gap-1">
          <span className="inline-block w-2.5 h-2.5 rounded-sm bg-orange-500/70" />Ascent
        </span>
        <span className="flex items-center gap-1">
          <span className="inline-block w-3 h-0.5 bg-cyan-400" />Vert Speed
        </span>
      </div>
    </ChartCard>
  );
}

export default AscentVertSpeedChart;
