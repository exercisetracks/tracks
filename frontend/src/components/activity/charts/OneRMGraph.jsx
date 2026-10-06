// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import React from "react";
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, ReferenceLine } from "recharts";
import { xAxisProps, yAxisProps, gridProps, TimeTooltip } from "../utils/chartHelpers.jsx";

/**
 * OneRMGraph - Displays estimated 1-rep max over time (calculated from weight/reps)
 * Uses Epley formula: 1RM = weight × (1 + reps/30)
 */
export function OneRMGraph({ data, onHover, onLeave, lapTimes = [] }) {
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
        <p className="section-title">Estimated 1RM</p>
      </div>
      <ResponsiveContainer width="100%" height={180}>
        <LineChart data={data} onMouseMove={onHover} onMouseLeave={onLeave}>
          <CartesianGrid {...gridProps} />
          <XAxis {...xAxisProps} />
          <YAxis {...yAxisProps} label={{ value: "kg", angle: -90, position: "insideLeft", fill: "#94a3b8", fontSize: 10, dy: 15 }} />
          <Tooltip content={<TimeTooltip format={(v) => v != null ? `${v.toFixed(1)} kg` : "—" } />} />
          {lapLines}
          <Line dataKey="oneRM" stroke="#ef4444" strokeWidth={2} dot={{ fill: "#ef4444", r: 3 }} activeDot={{ r: 5 }} />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}