// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import React from "react";
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer, Cell } from "recharts";
import { ChartCard } from "../utils/chartHelpers.jsx";
import { CHART_GRID } from "../../../design/chartGrid";

export function ClimbGradeHistogram({ activeSplits }) {
  if (!activeSplits?.length) return null;

  const hasGrades = activeSplits.some((c) => c.grade_level != null);
  if (!hasGrades) return null;

  const gradeMap = {};
  for (const c of activeSplits) {
    const g = c.grade_level ?? 0;
    if (!gradeMap[g]) gradeMap[g] = { sends: 0, attempts: 0 };
    if (c.climb_result === 3) gradeMap[g].sends++;
    else gradeMap[g].attempts++;
  }

  const data = Object.keys(gradeMap)
    .map(Number)
    .sort((a, b) => a - b)
    .map((g) => ({
      grade: `V${g}`,
      sends: gradeMap[g].sends,
      attempts: gradeMap[g].attempts,
    }));

  return (
    <ChartCard title="Grade Distribution">
      <ResponsiveContainer width="100%" height={200}>
        <BarChart data={data} margin={{ top: 4, right: 8, bottom: 0, left: 0 }} barSize={18} barGap={2}>
          <CartesianGrid {...CHART_GRID} />
          <XAxis dataKey="grade" tick={{ fontSize: 10, fill: "#94a3b8" }} axisLine={false} tickLine={false} />
          <YAxis tick={{ fontSize: 10, fill: "#94a3b8" }} axisLine={false} tickLine={false} width={28} allowDecimals={false} />
          <Tooltip
            contentStyle={{ background: "#0f172a", border: "none", borderRadius: 8, fontSize: 11 }}
            labelStyle={{ color: "#94a3b8" }}
            formatter={(v, name) => [v, name === "sends" ? "Sends" : "Attempts"]}
          />
          <Bar dataKey="sends" stackId="a" fill="#10b981" radius={[0, 0, 0, 0]} name="sends" />
          <Bar dataKey="attempts" stackId="a" fill="rgba(148,163,184,0.35)" radius={[3, 3, 0, 0]} name="attempts" />
        </BarChart>
      </ResponsiveContainer>
      <div className="flex items-center justify-center gap-4 mt-1 text-[10px] text-slate-500 dark:text-slate-400">
        <span className="flex items-center gap-1">
          <span className="inline-block w-2.5 h-2.5 rounded-sm bg-accent-500" />Send
        </span>
        <span className="flex items-center gap-1">
          <span className="inline-block w-2.5 h-2.5 rounded-sm bg-slate-300/60 border border-slate-400 dark:border-slate-600" />Attempt
        </span>
      </div>
    </ChartCard>
  );
}

export default ClimbGradeHistogram;
