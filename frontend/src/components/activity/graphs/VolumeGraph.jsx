// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import React from "react";
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Cell, ReferenceLine } from "recharts";
import { xAxisProps, yAxisProps, TimeTooltip } from "../utils/chartHelpers.jsx";
import { CHART_GRID } from "../../../design/chartGrid";

/**
 * VolumeGraph - Displays volume (weight × reps) per set
 */
export function VolumeGraph({ data, onHover, onLeave, lapTimes = [] }) {
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
        <p className="section-title">Volume per Set</p>
      </div>
      <ResponsiveContainer width="100%" height={180}>
        <BarChart data={data} onMouseMove={onHover} onMouseLeave={onLeave}>
          <CartesianGrid {...CHART_GRID} />
          <XAxis {...xAxisProps} />
          <YAxis {...yAxisProps} label={{ value: "kg-reps", angle: -90, position: "insideLeft", fill: "#94a3b8", fontSize: 10, dy: 15 }} />
          <Tooltip content={<TimeTooltip format={(v) => v != null ? `${v} kg·reps` : "—" } />} />
          {lapLines}
          <Bar dataKey="volume" radius={[4, 4, 0, 0]}>
            {data.map((entry, i) => (
              <Cell key={i} fill={i % 2 === 0 ? "#8b5cf6" : "#a78bfa"} />
            ))}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}