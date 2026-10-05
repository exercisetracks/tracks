// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * EffortVsRecoveryChart
 * -------------------------------------------------------------------------
 * Bouldering-specific scatter chart plotting, per problem, the time spent on
 * the wall (x) against the rest taken afterwards (y). Marker size encodes peak
 * heart rate, and colour separates sends from attempts. It surfaces pacing /
 * recovery habits across a session at a glance.
 *
 * Extracted verbatim from BoulderingLayout: this is a self-contained,
 * low-coupling presentational block that consumes one pre-computed data array
 * (BoulderingLayout.effortVsRest) plus renders its own legend.
 *
 * The metaphor (on-wall vs rest-after, send/attempt) is bouldering-specific, so
 * it lives under layouts/bouldering/ rather than shared/. Not used by siblings.
 *
 * Props:
 *   data: Array<{
 *     idx: number,      // 1-based problem index
 *     duration: number, // minutes on the wall
 *     rest: number,     // minutes rested after (always non-null; caller filters)
 *     peakHr: number,   // peak HR during the attempt -> marker size (ZAxis)
 *     send: boolean,    // true = clean send, false = attempt
 *   }>
 *     Render nothing when empty so the caller can drop it unconditionally.
 */

import React from "react";
import {
  ScatterChart,
  Scatter,
  XAxis,
  YAxis,
  ZAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
} from "recharts";
import { ChartCard, gridProps } from "../../utils/chartHelpers.jsx";

export function EffortVsRecoveryChart({ data }) {
  if (!data?.length) return null;

  return (
    <ChartCard title="Effort vs Recovery">
      <ResponsiveContainer width="100%" height={220}>
        <ScatterChart margin={{ top: 8, right: 16, bottom: 24, left: 8 }}>
          <CartesianGrid {...gridProps} />
          <XAxis
            type="number"
            dataKey="duration"
            tick={{ fontSize: 10, fill: "#94a3b8" }}
            axisLine={false}
            tickLine={false}
            label={{ value: "Time on wall (min)", position: "insideBottom", fill: "#94a3b8", fontSize: 9, dy: 14 }}
          />
          <YAxis
            type="number"
            dataKey="rest"
            tick={{ fontSize: 10, fill: "#94a3b8" }}
            axisLine={false}
            tickLine={false}
            width={42}
            label={{ value: "Rest after (min)", angle: -90, position: "insideLeft", fill: "#94a3b8", fontSize: 9, dy: 36 }}
          />
          {/* Marker area is driven by peak HR: harder efforts render larger. */}
          <ZAxis type="number" dataKey="peakHr" range={[40, 280]} />
          <Tooltip
            cursor={{ strokeDasharray: "3 3" }}
            contentStyle={{ background: "#0f172a", border: "none", borderRadius: 8, fontSize: 11 }}
            labelStyle={{ color: "#94a3b8" }}
            formatter={(v, name) => {
              if (name === "duration") return [`${v} min`, "On wall"];
              if (name === "rest")     return [`${v} min`, "Rest"];
              if (name === "peakHr")   return [`${v} bpm`, "Peak HR"];
              return [v, name];
            }}
            labelFormatter={() => ""}
          />
          {/* Two series so sends vs attempts get distinct colours. */}
          <Scatter data={data.filter((p) => p.send)} fill="#10b981" name="Send" />
          <Scatter data={data.filter((p) => !p.send)} fill="rgba(148,163,184,0.6)" name="Attempt" />
        </ScatterChart>
      </ResponsiveContainer>
      <div className="flex items-center justify-center gap-4 mt-1 text-[10px] text-slate-500 dark:text-slate-400">
        <span className="flex items-center gap-1">
          <span className="inline-block w-2.5 h-2.5 rounded-full bg-accent-500" />Send
        </span>
        <span className="flex items-center gap-1">
          <span className="inline-block w-2.5 h-2.5 rounded-full bg-slate-400/70" />Attempt
        </span>
        <span className="text-slate-400">marker size = peak HR</span>
      </div>
    </ChartCard>
  );
}

export default EffortVsRecoveryChart;
