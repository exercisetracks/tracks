// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import React, { useMemo, useCallback } from "react";
import { ComposedChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Customized, ReferenceLine } from "recharts";
import { HRColorLine, xAxisProps, yAxisProps, TimeTooltip } from "../utils/chartHelpers.jsx";
import { CHART_GRID } from "../../../design/chartGrid";

export const HeartRateGraph = React.memo(function HeartRateGraph({
  data,
  maxHR = 200,
  hrYMin = 40,
  onHover,
  onLeave,
  children,
  lapTimes = [],
}) {
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

  const hrLineRender = useCallback(
    (chartProps) => <HRColorLine {...chartProps} formData={data} maxHR={maxHR} />,
    [data, maxHR]);

  if (!data?.length) return null;

  return (
    <div className="card">
      <div className="flex items-center gap-1.5 mb-3">
        <p className="section-title">Heart Rate</p>
      </div>
      <ResponsiveContainer width="100%" height={180}>
        <ComposedChart data={data} syncId="activity-time" onMouseMove={onHover} onMouseLeave={onLeave}>
          <CartesianGrid {...CHART_GRID} />
          <XAxis {...xAxisProps} />
          <YAxis {...yAxisProps} domain={[hrYMin, "auto"]}
            label={{ value: "bpm", angle: -90, position: "insideLeft", fill: "#94a3b8", fontSize: 10, dy: 15 }} />
          <Tooltip cursor={{ stroke: "#64748b", strokeWidth: 1, strokeDasharray: "3 3" }}
            content={<TimeTooltip format={(v) => v != null ? `${Math.round(v)} bpm` : "—"} />} />
          {lapLines}
          <Line dataKey="hr" stroke="transparent" strokeWidth={0} dot={false} isAnimationActive={false} />
          <Customized component={hrLineRender} />
          {children}
        </ComposedChart>
      </ResponsiveContainer>
    </div>
  );
});

export default HeartRateGraph;
