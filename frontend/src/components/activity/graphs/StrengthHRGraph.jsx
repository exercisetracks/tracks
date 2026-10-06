// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import React from "react";
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, ReferenceLine, Customized } from "recharts";
import { HRColorLine, xAxisProps, yAxisProps, gridProps, TimeTooltip } from "../utils/chartHelpers.jsx";

/**
 * StrengthHRGraph - Displays heart rate during strength training workout
 */
export function StrengthHRGraph({ data, maxHR = 200, onHover, onLeave, lapTimes = [] }) {
  if (!data?.length) return null;

  const hrLineRender = (chartProps) => <HRColorLine {...chartProps} formData={data} maxHR={maxHR} />;

  const maxElapsed = data.length > 0 ? Math.max(...data.map(d => d.elapsed || 0)) : 0;
  const lapLines = lapTimes
    .filter(t => t > 0 && t <= maxElapsed)
    .map((t, i) => (
      <ReferenceLine key={i} x={t} stroke="#64748b" strokeDasharray="4 3" strokeWidth={1} />
    ));

  return (
    <div className="card">
      <div className="flex items-center gap-1.5 mb-3">
        <p className="section-title">Heart Rate</p>
      </div>
      <ResponsiveContainer width="100%" height={180}>
        <LineChart data={data} onMouseMove={onHover} onMouseLeave={onLeave}>
          <CartesianGrid {...gridProps} />
          <XAxis {...xAxisProps} />
          <YAxis {...yAxisProps} domain={[40, "auto"]} label={{ value: "bpm", angle: -90, position: "insideLeft", fill: "#94a3b8", fontSize: 10, dy: 15 }} />
          <Tooltip cursor={{ stroke: "#3b82f6", strokeWidth: 1, strokeDasharray: "3 3" }} content={<TimeTooltip format={(v) => v != null ? `${Math.round(v)} bpm` : "—" } />} />
          {lapLines}
          <Line dataKey="heart_rate" stroke="transparent" strokeWidth={0} dot={false} />
          <Customized component={hrLineRender} />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}