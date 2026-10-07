// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useMemo, useCallback } from "react";
import { useTheme } from "../../context/ThemeContext";
import { complementOf, currentAccentRgb } from "../../lib/complement";
import {
  ComposedChart,
  Bar,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ResponsiveContainer,
} from "recharts";
import { CHART_GRID } from "../../design/chartGrid";
import { fillEmptyWeeks, gentleCurve } from "./weeklyVolumeData";

function fmtTooltipDate(ms) {
  return new Date(ms).toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" });
}

function CustomTooltip({ active, payload, label, imperial }) {
  if (!active || !payload?.length) return null;
  const distUnit = imperial ? "mi" : "km";
  return (
    <div className="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-lg px-2.5 py-1.5 text-xs shadow">
      <p className="font-semibold text-slate-700 dark:text-slate-200 mb-1">
        Week of {fmtTooltipDate(label)}
      </p>
      {payload.map(p => (
        <div key={p.dataKey} className="flex gap-2 justify-between">
          <span style={{ color: p.color }}>{p.name}</span>
          <span className="font-medium text-slate-800 dark:text-slate-100">
            {p.value != null ? `${Number(p.value).toFixed(1)} ${p.dataKey === "distance_km" ? distUnit : "h"}` : "—"}
          </span>
        </div>
      ))}
    </div>
  );
}

export default function WeeklyVolumeChart({ data = [], imperial = false, xAxis }) {
  const distUnit = imperial ? "mi" : "km";
  // Bars in the accent, the duration line in its complement — they were a
  // fixed green and indigo, ignoring the accent, and the phone drew both in
  // the accent. Recomputed when the theme or accent changes; the theme
  // applies the new CSS variables before this re-renders.
  const { accent, colorScheme } = useTheme();
  const colors = useMemo(() => {
    const rgb = currentAccentRgb();
    const dark = document.documentElement.classList.contains("dark");
    return { bar: `rgb(${rgb.join(" ")})`, line: complementOf(rgb, dark) };
  }, [accent, colorScheme]); // eslint-disable-line react-hooks/exhaustive-deps

  // Convert ISO week-start strings to ms timestamps so this chart shares an
  // identical numeric X scale with FitnessChart — that's what makes the
  // bars line up vertically with the Fitness/Form lines on the same date.
  //
  // Missing weeks become zeros, and so does a week's missing duration: a
  // null breaks the line, and a week with no hours in it is zero hours.
  const [fromMs, toMs] = xAxis?.domain ?? [];
  const chartData = useMemo(() => fillEmptyWeeks(data, fromMs, toMs).map(d => ({
    weekMs: new Date(d.week_start + "T00:00:00").getTime(),
    distance_km: d.distance_km != null
      ? (imperial ? +(d.distance_km * 0.621371).toFixed(2) : d.distance_km)
      : null,
    duration_hours: d.duration_hours ?? 0,
  })), [data, imperial, fromMs, toMs]);

  // When the user hovers on the daily FitnessChart, Recharts broadcasts the
  // hovered day's timestamp. Using syncMethod="value" would only show a cursor
  // here on the one day per week where timestamps match exactly. Instead, this
  // function snaps to the nearest week so the cursor always follows.
  const snapToNearestWeek = useCallback((tooltipTicks, syncData) => {
    const hoveredMs = syncData?.activeLabel;
    if (!hoveredMs || !tooltipTicks.length) return 0;
    let closestIdx = 0;
    let minDiff = Infinity;
    tooltipTicks.forEach((tick, i) => {
      const diff = Math.abs(tick.value - hoveredMs);
      if (diff < minDiff) { minDiff = diff; closestIdx = i; }
    });
    return closestIdx;
  }, []);

  if (!data.length) {
    return (
      <p className="text-sm text-slate-400 dark:text-slate-500 text-center py-5">
        No data for this period
      </p>
    );
  }

  return (
    <ResponsiveContainer width="100%" height={220}>
      <ComposedChart
        data={chartData}
        syncId="fitness"
        syncMethod={snapToNearestWeek}
        margin={{ top: 4, right: 16, left: 0, bottom: 0 }}
      >
        <CartesianGrid {...CHART_GRID} />
        <XAxis
          dataKey="weekMs"
          type="number"
          scale="time"
          domain={xAxis?.domain ?? ["dataMin", "dataMax"]}
          ticks={xAxis?.ticks}
          tickFormatter={xAxis?.format}
          allowDataOverflow
          tick={{ fontSize: 11, fill: "#94a3b8" }}
          axisLine={false}
          tickLine={false}
        />
        <YAxis
          yAxisId="dist"
          orientation="left"
          tick={{ fontSize: 11, fill: "#94a3b8" }}
          axisLine={false}
          tickLine={false}
          tickFormatter={v => `${v}${distUnit}`}
          width={45}
        />
        <YAxis
          yAxisId="dur"
          orientation="right"
          tick={{ fontSize: 11, fill: "#94a3b8" }}
          axisLine={false}
          tickLine={false}
          tickFormatter={v => `${v}h`}
          width={36}
        />
        <Tooltip content={<CustomTooltip imperial={imperial} />} />
        <Legend
          wrapperStyle={{ fontSize: 11, paddingTop: 8 }}
          formatter={name => name === "distance_km" ? `Distance (${distUnit})` : "Duration (h)"}
        />
        <Bar
          yAxisId="dist"
          dataKey="distance_km"
          name="distance_km"
          fill={colors.bar}
          fillOpacity={0.85}
          radius={[3, 3, 0, 0]}
          maxBarSize={40}
        />
        <Line
          yAxisId="dur"
          dataKey="duration_hours"
          name="duration_hours"
          // Lighter than the fitness chart's monotone, which suits daily
          // points and turns weekly ones into a row of bells — see
          // gentleCurve.
          type={gentleCurve}
          stroke={colors.line}
          strokeWidth={2}
          dot={false}
          activeDot={{ r: 4 }}
        />
      </ComposedChart>
    </ResponsiveContainer>
  );
}
