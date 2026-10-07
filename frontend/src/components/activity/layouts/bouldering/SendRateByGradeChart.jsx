// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * SendRateByGradeChart
 * -------------------------------------------------------------------------
 * Bouldering-specific bar chart: for each V-grade attempted in a session it
 * plots the send rate (sends / total tries, as a percentage).
 *
 * This block was extracted verbatim from BoulderingLayout to keep the parent
 * layout focused on data derivation + composition. It is purely presentational
 * and low-coupling: it takes one already-computed data array and renders it.
 *
 * The chart is bouldering-only (V-grade buckets, send/attempt semantics), so it
 * lives under layouts/bouldering/ rather than the shared/ folder. It is NOT
 * imported by sibling layouts (ClimbingLayout uses different per-route charts).
 *
 * Props:
 *   data: Array<{
 *     label: string,     // e.g. "V4"
 *     sends: number,     // clean sends at this grade
 *     attempts: number,  // logged attempts (result === 2)
 *     tries: number,     // total tries at this grade
 *     sendRate: number,  // 0..100
 *   }>
 *     Pre-bucketed + sorted by the parent (see BoulderingLayout.gradeBreakdown).
 *     Render nothing when empty so the caller can drop it unconditionally.
 */

import React from "react";
import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  Cell,
} from "recharts";
import { ChartCard } from "../../utils/chartHelpers.jsx";
import { CHART_GRID } from "../../../../design/chartGrid";

export function SendRateByGradeChart({ data }) {
  if (!data?.length) return null;

  return (
    <ChartCard title="Send Rate by Grade">
      <ResponsiveContainer width="100%" height={200}>
        <BarChart data={data} margin={{ top: 4, right: 16, bottom: 0, left: 0 }}>
          <CartesianGrid {...CHART_GRID} />
          <XAxis dataKey="label" tick={{ fontSize: 10, fill: "#94a3b8" }} axisLine={false} tickLine={false} />
          <YAxis
            tick={{ fontSize: 10, fill: "#94a3b8" }}
            axisLine={false}
            tickLine={false}
            width={36}
            domain={[0, 100]}
            tickFormatter={(v) => `${v}%`}
          />
          <Tooltip
            contentStyle={{ background: "#0f172a", border: "none", borderRadius: 8, fontSize: 11 }}
            labelStyle={{ color: "#94a3b8" }}
            formatter={(v, name, p) => {
              if (name === "sendRate") {
                const d = p?.payload;
                return [`${v}% (${d?.sends ?? 0}/${d?.tries ?? 0})`, "Send Rate"];
              }
              return [v, name];
            }}
          />
          {/* Colour ramp encodes difficulty of the grade: green = high send
              rate, red = low. Keeps the bar chart readable without a legend. */}
          <Bar dataKey="sendRate" radius={[3, 3, 0, 0]}>
            {data.map((d, i) => (
              <Cell
                key={i}
                fill={d.sendRate >= 80 ? "#10b981" : d.sendRate >= 50 ? "#22d3ee" : d.sendRate >= 25 ? "#f59e0b" : "#ef4444"}
              />
            ))}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </ChartCard>
  );
}

export default SendRateByGradeChart;
