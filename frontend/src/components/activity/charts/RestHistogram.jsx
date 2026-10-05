// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import React from "react";
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Cell } from "recharts";
import { yAxisProps, gridProps } from "../utils/chartHelpers.jsx";

/**
 * RestHistogram - Displays distribution of rest periods between sets
 */
export function RestHistogram({ data }) {
  if (!data?.length) return null;

  return (
    <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 p-3.5">
      <div className="flex items-center gap-1.5 mb-3">
        <p className="text-xs font-semibold text-slate-600 dark:text-slate-300 uppercase tracking-wider">Rest Period Distribution</p>
      </div>
      <ResponsiveContainer width="100%" height={180}>
        <BarChart data={data} margin={{ top: 4, right: 8, bottom: 0, left: 0 }}>
          <CartesianGrid {...gridProps} vertical={false} />
          <XAxis dataKey="range" tick={{ fontSize: 9, fill: "#94a3b8" }} axisLine={false} tickLine={false} />
          <YAxis {...yAxisProps} label={{ value: "count", angle: -90, position: "insideLeft", fill: "#94a3b8", fontSize: 10 }} />
          <Tooltip 
            contentStyle={{ 
              background: "#0f172a", 
              border: "none", 
              borderRadius: 8, 
              fontSize: 11,
              color: "#f1f5f9"
            }}
            itemStyle={{ color: "#f1f5f9" }}
          />
          <Bar dataKey="count" radius={[4, 4, 0, 0]}>
            {data.map((entry, i) => (
              <Cell key={i} fill="#06b6d4" />
            ))}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}