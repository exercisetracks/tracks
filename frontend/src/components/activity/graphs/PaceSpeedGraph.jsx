// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import React, { useMemo, useCallback } from "react";
import {
  ComposedChart, Line, XAxis, YAxis, CartesianGrid,
  Tooltip, ResponsiveContainer, ReferenceLine,
} from "recharts";
import { xAxisProps, yAxisProps, gridProps } from "../utils/chartHelpers.jsx";
import { fmtElapsed } from "../../../utils/formatUtils";

function fmtPaceTick(v) {
  if (!v || !isFinite(v)) return "";
  const mins = Math.floor(v);
  const secs = Math.round((v - mins) * 60);
  return `${mins}:${secs.toString().padStart(2, "0")}`;
}

export const PaceSpeedGraph = React.memo(function PaceSpeedGraph({ data, imperial = false, onHover, onLeave, lapTimes = [] }) {
  const speedFactor = imperial ? 2.23694 : 3.6;
  const paceFactor  = imperial ? 1609.34 : 1000;
  const paceUnit    = imperial ? "min/mi" : "min/km";
  const speedUnit   = imperial ? "mph"    : "km/h";

  const chartData = useMemo(() => {
    if (!data?.length) return [];
    return data.map(p => {
      const moving = p.speed != null && p.speed > 0.3;
      return {
        elapsed: p.elapsed,
        speed:   moving ? parseFloat((p.speed * speedFactor).toFixed(2)) : null,
        // Snap to 30 when stopped so the line stays connected instead of breaking
        pace:    p.speed != null
          ? parseFloat(Math.min(paceFactor / (Math.max(p.speed, 0.001) * 60), 30).toFixed(3))
          : null,
      };
    });
  }, [data, speedFactor, paceFactor]);

  const paceValues = useMemo(() =>
    chartData.map(d => d.pace).filter(v => v != null && isFinite(v) && v > 0 && v < 25),
    [chartData]);

  const paceMin = useMemo(() =>
    paceValues.length > 0 ? Math.max(0, Math.floor(Math.min(...paceValues) - 0.5)) : 3,
    [paceValues]);
  const paceMax = useMemo(() =>
    paceValues.length > 0 ? Math.ceil(Math.max(...paceValues) + 1) : 15,
    [paceValues]);

  const maxElapsed = chartData[chartData.length - 1]?.elapsed ?? 0;
  const lapLines = useMemo(() =>
    lapTimes
      .filter(t => t > 0 && t <= maxElapsed && Number.isFinite(t))
      .map((t, i) => (
        <ReferenceLine key={i} x={t} yAxisId="pace" stroke="#64748b" strokeDasharray="4 3" strokeWidth={1}
          label={{ value: `Lap ${i + 2}`, position: "insideTopRight", fill: "#94a3b8", fontSize: 9 }} />
      )),
    [lapTimes, maxElapsed]);

  const chartDataMap = useMemo(() => {
    const m = new Map();
    for (const d of chartData) m.set(d.elapsed, d);
    return m;
  }, [chartData]);

  // Tooltip has access to chartData via closure so it can show speed without a second axis
  const renderTooltip = useCallback(({ active, payload, label }) => {
    if (!active || !payload?.length) return null;
    const pace  = payload.find(p => p.dataKey === "pace")?.value;
    const point = chartDataMap.get(label);
    return (
      <div className="bg-slate-900/90 text-white text-xs rounded-lg px-2.5 py-1.5 shadow-lg pointer-events-none">
        <p className="text-slate-400 mb-1">{fmtElapsed(label)}</p>
        {pace  != null && <p className="font-medium" style={{ color: "#ec4899" }}>{fmtPaceTick(pace)} {paceUnit}</p>}
        {point?.speed != null && <p style={{ color: "#3b82f6" }}>{point.speed.toFixed(1)} {speedUnit}</p>}
      </div>
    );
  }, [chartDataMap, paceUnit, speedUnit]);

  if (!paceValues.length) return null;

  return (
    <div className="card">
      <div className="flex items-center justify-between mb-3">
        <p className="section-title">
          Pace &amp; Speed
        </p>
        <div className="flex items-center gap-3">
          <span className="flex items-center gap-1 text-[10px] text-slate-500 dark:text-slate-400">
            <span className="w-3 h-0.5 rounded" style={{ background: "#ec4899", display: "inline-block" }} />
            {paceUnit}
          </span>
          <span className="flex items-center gap-1 text-[10px] text-slate-500 dark:text-slate-400">
            <span className="w-3 h-0.5 rounded" style={{ background: "#3b82f6", display: "inline-block" }} />
            {speedUnit} (tooltip)
          </span>
        </div>
      </div>

      <ResponsiveContainer width="100%" height={180}>
        <ComposedChart data={chartData} syncId="activity-time" onMouseMove={onHover} onMouseLeave={onLeave}>
          <CartesianGrid {...gridProps} />
          <XAxis {...xAxisProps} />
          <YAxis
            {...yAxisProps}
            yAxisId="pace"
            orientation="left"
            reversed
            domain={[paceMin, paceMax]}
            tickFormatter={fmtPaceTick}
            label={{ value: paceUnit, angle: -90, position: "insideLeft", fill: "#94a3b8", fontSize: 10, dy: 30 }}
          />
          <Tooltip
            cursor={{ stroke: "#64748b", strokeWidth: 1, strokeDasharray: "3 3" }}
            content={renderTooltip}
          />
          {lapLines}
          <Line
            yAxisId="pace"
            type="monotone"
            dataKey="pace"
            stroke="#ec4899"
            strokeWidth={1.5}
            dot={false}
            isAnimationActive={false}
            name="Pace"
          />
        </ComposedChart>
      </ResponsiveContainer>
    </div>
  );
});

export default PaceSpeedGraph;
