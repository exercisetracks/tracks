// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import React from "react";
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ReferenceLine, ResponsiveContainer, Cell } from "recharts";
import { ChartCard } from "../utils/chartHelpers.jsx";
import { CHART_GRID } from "../../../design/chartGrid";

function strokeColor(strokes) {
  if (strokes <= 1) return "#10b981"; // ace / hole-in-one
  if (strokes <= 2) return "#22c55e"; // eagle or better (approx)
  if (strokes <= 3) return "#3b82f6"; // birdie range
  if (strokes === 4) return "#94a3b8"; // par range
  if (strokes === 5) return "#f59e0b"; // bogey range
  return "#ef4444";                   // double bogey+
}

export function GolfScorecard({ holes, imperial }) {
  if (!holes?.length) return null;

  const data = holes.map((h) => ({
    hole: h.hole_number,
    strokes: h.total_strokes ?? 0,
    putts: h.total_putts ?? 0,
  })).filter((d) => d.strokes > 0);

  if (!data.length) return null;

  const avgStrokes = data.reduce((s, d) => s + d.strokes, 0) / data.length;

  return (
    <ChartCard title="Strokes per Hole">
      <ResponsiveContainer width="100%" height={220}>
        <BarChart data={data} margin={{ top: 4, right: 8, bottom: 0, left: 0 }} barSize={18}>
          <CartesianGrid {...CHART_GRID} />
          <XAxis
            dataKey="hole"
            tick={{ fontSize: 10, fill: "#94a3b8" }}
            axisLine={false}
            tickLine={false}
            label={{ value: "Hole", position: "insideBottom", fill: "#94a3b8", fontSize: 9, dy: 6 }}
          />
          <YAxis
            tick={{ fontSize: 10, fill: "#94a3b8" }}
            axisLine={false}
            tickLine={false}
            width={28}
            allowDecimals={false}
            label={{ value: "Strokes", angle: -90, position: "insideLeft", fill: "#94a3b8", fontSize: 9, dy: 30 }}
          />
          <ReferenceLine
            y={Math.round(avgStrokes * 10) / 10}
            stroke="#94a3b8"
            strokeDasharray="4 3"
            strokeWidth={1}
            label={{ value: `avg ${(avgStrokes).toFixed(1)}`, position: "right", fill: "#94a3b8", fontSize: 9 }}
          />
          <Tooltip
            contentStyle={{ background: "#0f172a", border: "none", borderRadius: 8, fontSize: 11 }}
            labelStyle={{ color: "#94a3b8" }}
            labelFormatter={(v) => `Hole ${v}`}
            formatter={(v, name) => [v, name === "strokes" ? "Strokes" : "Putts"]}
          />
          <Bar dataKey="strokes" radius={[3, 3, 0, 0]} name="strokes">
            {data.map((d, i) => (
              <Cell key={i} fill={strokeColor(d.strokes)} />
            ))}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
      <div className="flex items-center justify-center gap-3 mt-1 text-[10px] text-slate-500 dark:text-slate-400 flex-wrap">
        <span className="flex items-center gap-1"><span className="inline-block w-2.5 h-2.5 rounded-sm bg-accent-500" />≤3</span>
        <span className="flex items-center gap-1"><span className="inline-block w-2.5 h-2.5 rounded-sm bg-blue-500" />4</span>
        <span className="flex items-center gap-1"><span className="inline-block w-2.5 h-2.5 rounded-sm bg-slate-400" />5</span>
        <span className="flex items-center gap-1"><span className="inline-block w-2.5 h-2.5 rounded-sm bg-amber-500" />6</span>
        <span className="flex items-center gap-1"><span className="inline-block w-2.5 h-2.5 rounded-sm bg-red-500" />7+</span>
      </div>
    </ChartCard>
  );
}

export default GolfScorecard;
