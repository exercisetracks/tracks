// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import React from "react";
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, ReferenceLine } from "recharts";
import { xAxisProps, yAxisProps, TimeTooltip } from "../utils/chartHelpers.jsx";
import { CHART_GRID } from "../../../design/chartGrid";

/**
 * RepGraph - Displays repetitions per set over time
 */
export function RepGraph({ data, onHover, onLeave, lapTimes = [] }) {
  if (!data?.length) return null;

  const maxElapsed = data.length > 0 ? Math.max(...data.map(d => d.elapsed || 0)) : 0;
  const lapLines = lapTimes
    .filter(t => t > 0 && t <= maxElapsed)
    .map((t, i) => (
      <ReferenceLine key={i} x={t} stroke="#64748b" strokeDasharray="4 3" strokeWidth={1} />
    ));

  return (
    <div className="card">
      <div className="flex items-center gap-1.5 mb-3">
        <p className="section-title">Repetitions</p>
      </div>
      <ResponsiveContainer width="100%" height={180}>
        <LineChart data={data} onMouseMove={onHover} onMouseLeave={onLeave}>
          <CartesianGrid {...CHART_GRID} />
          <XAxis {...xAxisProps} />
          <YAxis {...yAxisProps} label={{ value: "reps", angle: -90, position: "insideLeft", fill: "#94a3b8", fontSize: 10, dy: 15 }} domain={[0, "auto"]} />
          <Tooltip content={<TimeTooltip format={(v) => v != null ? `${v} reps` : "—" } />} />
          {lapLines}
          <Line dataKey="reps" stroke="#f59e0b" strokeWidth={2} dot={{ fill: "#f59e0b", r: 3 }} activeDot={{ r: 5 }} />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}