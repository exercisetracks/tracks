// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import React, { useMemo } from "react";
import { AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, ReferenceLine } from "recharts";
import { xAxisProps, yAxisProps, gridProps, TimeTooltip } from "../utils/chartHelpers.jsx";

export const ElevationGraph = React.memo(function ElevationGraph({ data, imperial = false, onHover, onLeave, lapTimes = [] }) {
  const unit = imperial ? "ft" : "m";
  const padding = imperial ? 330 : 100;

  const maxElapsed = useMemo(() =>
    data?.length ? Math.max(...data.map(d => d.elapsed || 0)) : 0,
    [data]);

  const lapLines = useMemo(() =>
    lapTimes
      .filter(t => t > 0 && t <= maxElapsed && Number.isFinite(t))
      .map((t, i) => (
        <ReferenceLine key={i} x={t} stroke="#64748b" strokeDasharray="4 3" strokeWidth={1}
          label={{ value: `Lap ${i + 2}`, position: "insideTopRight", fill: "#94a3b8", fontSize: 9 }} />
      )),
    [lapTimes, maxElapsed]);

  const yDomain = useMemo(() => {
    const elevVals = data?.map(d => d.elevation).filter(v => v != null && isFinite(v)) ?? [];
    if (!elevVals.length) return [0, 100];
    const elevMin = Math.min(...elevVals);
    const elevMax = Math.max(...elevVals);
    return [
      Math.floor((elevMin - padding) / 10) * 10,
      Math.ceil((elevMax + padding) / 10) * 10,
    ];
  }, [data, padding]);

  if (!data?.length) return null;

  return (
    <div className="card">
      <div className="flex items-center gap-1.5 mb-3">
        <p className="section-title">Elevation</p>
      </div>
      <ResponsiveContainer width="100%" height={160}>
        <AreaChart data={data} syncId="activity-time" onMouseMove={onHover} onMouseLeave={onLeave}>
          <defs>
            <linearGradient id="elevGrad" x1="0" y1="0" x2="0" y2="1">
              <stop offset="5%" stopColor="#f97316" stopOpacity={0.4} />
              <stop offset="95%" stopColor="#f97316" stopOpacity={0.05} />
            </linearGradient>
          </defs>
          <CartesianGrid {...gridProps} />
          <XAxis {...xAxisProps} />
          <YAxis {...yAxisProps}
            domain={yDomain}
            label={{ value: unit, angle: -90, position: "insideLeft", fill: "#94a3b8", fontSize: 10 }} />
          <Tooltip content={<TimeTooltip format={(v) => v != null ? `${Math.round(v)} ${unit}` : "—"} />} />
          {lapLines}
          <Area type="monotone" dataKey="elevation" stroke="#f97316" fill="url(#elevGrad)" strokeWidth={1.5} dot={false} isAnimationActive={false} />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
});

export default ElevationGraph;
