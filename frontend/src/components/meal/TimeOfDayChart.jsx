// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Presentational bar chart of calories by hour of day, overlaying today's intake
// against the 7-day average. Takes two 24-length arrays (one calorie total per
// hour) and renders nothing but the chart — no data fetching, no state.

import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer,
} from "recharts";
import { HOUR_LABELS } from "./constants";

export default function TimeOfDayChart({ todayByHour, weeklyByHour }) {
  // Build one row per hour, then drop hours with zero on both series so the
  // chart isn't padded out with empty overnight bars.
  const data = HOUR_LABELS.map((label, h) => ({
    hour:   label,
    Today:  todayByHour[h]   || 0,
    Weekly: weeklyByHour[h]  || 0,
  })).filter((_, h) => todayByHour[h] > 0 || weeklyByHour[h] > 0);

  if (data.length === 0) {
    return <p className="text-xs text-slate-400 text-center py-5">No meal entries yet today</p>;
  }

  return (
    <ResponsiveContainer width="100%" height={140}>
      <BarChart data={data} margin={{ top: 4, right: 8, left: 0, bottom: 0 }} barCategoryGap="20%">
        <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" strokeOpacity={0.4} vertical={false} />
        <XAxis dataKey="hour" tick={{ fontSize: 10, fill: "#94a3b8" }} axisLine={false} tickLine={false} />
        <YAxis tick={{ fontSize: 10, fill: "#94a3b8" }} axisLine={false} tickLine={false} width={30} />
        <Tooltip
          formatter={(v, name) => [`${v} kcal`, name]}
          contentStyle={{ fontSize: 12, borderRadius: 8 }}
        />
        <Legend wrapperStyle={{ fontSize: 11 }} />
        <Bar dataKey="Today"  fill="#10b981" radius={[3, 3, 0, 0]} />
        <Bar dataKey="Weekly" fill="#94a3b8" radius={[3, 3, 0, 0]} opacity={0.6} />
      </BarChart>
    </ResponsiveContainer>
  );
}
