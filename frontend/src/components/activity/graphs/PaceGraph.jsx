// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import React from "react";
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, ReferenceLine } from "recharts";
import { xAxisProps, yAxisProps, gridProps, TimeTooltip } from "../utils/chartHelpers.jsx";

export function PaceGraph({ data, imperial = false, onHover, onLeave, lapTimes = [] }) {
  if (!data?.length) return null;

  const maxElapsed = data.length > 0 ? Math.max(...data.map(d => d.elapsed || 0)) : 0;
  const lapLines = lapTimes
    .filter(t => t > 0 && t <= maxElapsed)
    .map((t, i) => (
      <ReferenceLine key={i} x={t} stroke="#64748b" strokeDasharray="4 3" strokeWidth={1}
        label={{ value: `Lap ${i + 2}`, position: "insideTopRight", fill: "#94a3b8", fontSize: 9 }} />
    ));

  const unit = imperial ? "min/mi" : "min/km";

  return (
    <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 p-3.5">
      <div className="flex items-center gap-1.5 mb-3">
        <p className="text-xs font-semibold text-slate-600 dark:text-slate-300 uppercase tracking-wider">Pace</p>
      </div>
      <ResponsiveContainer width="100%" height={160}>
        <LineChart data={data} syncId="activity-time" onMouseMove={onHover} onMouseLeave={onLeave}>
          <CartesianGrid {...gridProps} />
          <XAxis {...xAxisProps} />
          <YAxis {...yAxisProps} reversed
            label={{ value: unit, angle: -90, position: "insideLeft", fill: "#94a3b8", fontSize: 10 }} />
          <Tooltip content={<TimeTooltip format={(v) => v != null ? `${Number(v).toFixed(2)} ${unit}` : "—"} />} />
          {lapLines}
          <Line type="monotone" dataKey="pace" stroke="#ec4899" strokeWidth={1.5} dot={false} />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}