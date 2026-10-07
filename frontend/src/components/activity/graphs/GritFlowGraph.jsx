// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import React from "react";
import { ComposedChart, Area, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Cell, ReferenceLine } from "recharts";
import { xAxisProps, yAxisProps, TimeTooltip } from "../utils/chartHelpers.jsx";
import InfoTooltip from "../../ui/InfoTooltip";
import { CHART_GRID } from "../../../design/chartGrid";

/**
 * GritFlowGraph - Displays MTB Grit (terrain difficulty) and Flow (smoothness) over time
 */
export const GritFlowGraph = React.memo(function GritFlowGraph({ data, onHover, onLeave, lapTimes = [] }) {
  if (!data?.length) return null;

  // Only create lap lines if we have valid data points
  const maxElapsed = data.length > 0 ? Math.max(...data.map(d => d.elapsed || 0)) : 0;
  const lapLines = lapTimes
    .filter(t => t > 0 && t <= maxElapsed)
    .map((t, i) => (
      <ReferenceLine key={i} x={t} yAxisId="grit" stroke="#64748b" strokeDasharray="4 3" strokeWidth={1}
        label={{ value: `Lap ${i + 2}`, position: "insideTopRight", fill: "#94a3b8", fontSize: 9 }} />
    ));

  return (
    <div className="card">
      <div className="flex items-center gap-1.5 mb-3">
        <p className="section-title">Grit & Flow</p>
        <GritFlowInfo />
      </div>
      <ResponsiveContainer width="100%" height={120}>
        <ComposedChart data={data} syncId="activity-time" onMouseMove={onHover} onMouseLeave={onLeave}>
          <CartesianGrid {...CHART_GRID} />
          <XAxis {...xAxisProps} />
          <YAxis yAxisId="grit" orientation="left" tick={{ fontSize: 10, fill: "#f97316" }} axisLine={false} tickLine={false} width={32} />
          <YAxis yAxisId="flow" orientation="right" tick={{ fontSize: 10, fill: "#22d3ee" }} axisLine={false} tickLine={false} width={32} />
          <Tooltip
            contentStyle={{ background: "#0f172a", border: "none", borderRadius: 8, fontSize: 11 }}
            labelStyle={{ color: "#94a3b8" }}
            formatter={(value, name) => value == null ? ["—", name] : [Number(value).toFixed(1), name]}
          />
          {lapLines}
          <Area yAxisId="grit" type="monotone" dataKey="grit" stroke="#f97316" strokeWidth={1} fill="#f97316" fillOpacity={0.25} dot={false} isAnimationActive={false} name="Grit" />
          <Line yAxisId="flow" type="monotone" dataKey="flow" stroke="#22d3ee" strokeWidth={1.5} dot={false} isAnimationActive={false} name="Flow" />
        </ComposedChart>
      </ResponsiveContainer>
      <div className="flex items-center justify-center gap-4 mt-1 text-[10px] text-slate-500 dark:text-slate-400">
        <span className="flex items-center gap-1"><span className="inline-block w-2.5 h-2.5 rounded-sm bg-orange-500/40 border border-orange-500" />Grit</span>
        <span className="flex items-center gap-1"><span className="inline-block w-3 h-0.5 bg-cyan-400" />Flow</span>
      </div>
    </div>
  );
});

// The explainer for the two scores, in the app's shared "?".
function GritFlowInfo() {
  return (
    <InfoTooltip>
      <p><strong className="text-slate-800 dark:text-slate-100">Grit</strong> — terrain difficulty score. Measures how rough and demanding a descent is based on acceleration data.</p>
      <p><strong className="text-slate-800 dark:text-slate-100">Flow</strong> — descent smoothness score. Higher = smoother lines, less braking, better momentum.</p>
      <p className="text-slate-400 dark:text-slate-500">Both metrics are Garmin MTB-specific, recorded per second.</p>
    </InfoTooltip>
  );
}

export default GritFlowGraph;