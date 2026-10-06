// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import React, { useMemo } from "react";
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, ReferenceLine } from "recharts";
import { xAxisProps, yAxisProps, gridProps, TimeTooltip } from "../utils/chartHelpers.jsx";

export const SpeedGraph = React.memo(function SpeedGraph({ data, imperial = false, label, onHover, onLeave, lapTimes = [] }) {
  const unit = imperial ? "mph" : "km/h";

  const maxElapsed = useMemo(() =>
    data?.length ? Math.max(...data.map(d => d.elapsed || 0)) : 0,
    [data]);

  const lapLines = useMemo(() =>
    lapTimes
      .filter(t => t > 0 && t <= maxElapsed)
      .map((t, i) => (
        <ReferenceLine key={i} x={t} stroke="#64748b" strokeDasharray="4 3" strokeWidth={1}
          label={{ value: `Lap ${i + 2}`, position: "insideTopRight", fill: "#94a3b8", fontSize: 9 }} />
      )),
    [lapTimes, maxElapsed]);

  if (!data?.length) return null;

  return (
    <div className="card">
      <div className="flex items-center gap-1.5 mb-3">
        <p className="section-title">{label || "Speed"}</p>
      </div>
      <ResponsiveContainer width="100%" height={160}>
        <LineChart data={data} syncId="activity-time" onMouseMove={onHover} onMouseLeave={onLeave}>
          <CartesianGrid {...gridProps} />
          <XAxis {...xAxisProps} />
          <YAxis {...yAxisProps}
            label={{ value: unit, angle: -90, position: "insideLeft", fill: "#94a3b8", fontSize: 10 }} />
          <Tooltip content={<TimeTooltip format={(v) => v != null ? `${Number(v).toFixed(1)} ${unit}` : "—"} />} />
          {lapLines}
          <Line type="monotone" dataKey="speed" stroke="#3b82f6" strokeWidth={1.5} dot={false} isAnimationActive={false} />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
});

export default SpeedGraph;
