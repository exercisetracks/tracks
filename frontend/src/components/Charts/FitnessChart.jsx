// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// FitnessChart.jsx
// ───────────────────────────────────────────────────────────────────────────
// Two stacked, cursor-synced Recharts sub-charts that visualise training load:
//
//   1. "Training load" — Fitness (CTL, 42-day EMA) and Fatigue (ATL, 7-day EMA)
//      drawn as overlapping filled Areas.
//   2. "Form"          — TSB (= CTL − ATL) drawn as a zone-coloured line over
//      translucent Form-zone bands, plus a "today" and a zero reference line.
//
// Both charts share one numeric (time-scale) X axis passed down from Dashboard,
// which — together with syncId + snapToNearestDay — keeps their cursors paired
// by date and their plot areas aligned with the sibling WeeklyVolumeChart.
//
// This file owns only the stateful/render wiring: data prep memos, axis scaling,
// the sync/handler callbacks, and the JSX. The context-free maths, constants and
// formatters live in ./fitnessChartData, and the self-contained presentational
// pieces (zone rects, zone-coloured line, tooltips, info popover) live in
// ./fitness/FitnessChartParts — see those files for the details of each.
// ───────────────────────────────────────────────────────────────────────────

import { useCallback, useMemo } from "react";
import {
  ComposedChart,
  Area,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ReferenceLine,
  ResponsiveContainer,
  Customized,
} from "recharts";

import {
  TODAY_MS,
  ZONES,
  zoneFor,
  thin,
  niceYTicks,
  CURSOR,
  AXIS,
  GRID,
  MARGIN,
  Y_WIDTH,
} from "./fitnessChartData";
import {
  ZoneBackgrounds,
  ZoneColoredLine,
  InfoTooltip,
  LoadTooltip,
} from "./fitness/FitnessChartParts";

export default function FitnessChart({ data, xAxis }) {
  // Pre-filter clearly impossible values, then convert ISO date strings to ms
  // timestamps so we can drive both charts off a shared numeric X scale.
  const chartData = useMemo(() => {
    const clean = (data ?? []).filter(p =>
      (p.ctl == null || Math.abs(p.ctl) < 1000) &&
      (p.atl == null || Math.abs(p.atl) < 1000) &&
      (p.tsb == null || Math.abs(p.tsb) < 1000)
    );
    const thinned = thin(clean);
    return thinned.map(p => ({ ...p, dateMs: new Date(p.date + "T00:00:00").getTime() }));
  }, [data]);

  const animKey = `${data?.[0]?.date ?? ''}-${data?.length ?? 0}`;

  // Auto-scale the Form Y axis to the actual TSB range with nice round ticks.
  // Explicit ticks + tickFormatter guarantee Recharts can't surprise us with
  // weird auto-generated tick values regardless of internal scale quirks.
  const tsbAxis = useMemo(() => {
    let min = Infinity, max = -Infinity, seen = false;
    for (const p of chartData) {
      if (p.tsb == null) continue;
      seen = true;
      if (p.tsb < min) min = p.tsb;
      if (p.tsb > max) max = p.tsb;
    }
    if (!seen) return niceYTicks(-30, 25);
    // Ensure the zone landmarks (±25/±30) are always visible for context.
    return niceYTicks(Math.min(min, -30), Math.max(max, 25));
  }, [chartData]);

  // Auto-scale the Training Load Y axis too — using identical width keeps it
  // visually aligned with the Form Y axis below.
  const loadAxis = useMemo(() => {
    let max = 0;
    for (const p of chartData) {
      if (p.ctl != null && p.ctl > max) max = p.ctl;
      if (p.atl != null && p.atl > max) max = p.atl;
    }
    return niceYTicks(0, Math.max(max, 50));
  }, [chartData]);

  const ZoneBgs = useCallback(
    (props) => <ZoneBackgrounds {...props} />,
    [],
  );
  const ZoneLine = useCallback(
    (props) => <ZoneColoredLine {...props} formData={chartData} />,
    [chartData],
  );

  // When the user scrubs WeeklyVolumeChart (weekly data), it broadcasts a
  // week-start timestamp. snapToNearestDay snaps to the closest daily point
  // so the cursor never flashes in/out (mirror of WeeklyVolumeChart's snap).
  const snapToNearestDay = useCallback((tooltipTicks, syncData) => {
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

  if (!data?.length) {
    return (
      <div className="flex items-center justify-center h-48 text-sm text-slate-400 dark:text-slate-500">
        No fitness data yet
      </div>
    );
  }

  // Shared X axis props for both sub-charts — keeps them perfectly aligned and
  // makes syncMethod={snapToNearestDay} pair cursors by date instead of by array index.
  const xAxisProps = {
    dataKey: "dateMs",
    type: "number",
    scale: "time",
    domain: xAxis?.domain ?? ["dataMin", "dataMax"],
    ticks: xAxis?.ticks,
    tickFormatter: xAxis?.format,
    allowDataOverflow: true,
  };

  return (
    <div className="space-y-2">

      {/* Training load header + legend */}
      <div className="flex items-center justify-between gap-4">
        <p className="text-xs font-medium text-slate-400 dark:text-slate-500 uppercase tracking-wide shrink-0">
          Training load
        </p>
        <div className="flex items-center gap-4 flex-wrap justify-end">
          <div className="flex items-center gap-1.5 text-xs text-slate-500 dark:text-slate-400">
            <span className="inline-block w-5 h-0.5 rounded shrink-0" style={{ background: "#10b981" }} />
            Fitness (CTL)
          </div>
          <div className="flex items-center gap-1.5 text-xs text-slate-500 dark:text-slate-400">
            <span className="inline-block w-5 h-0.5 rounded shrink-0" style={{ background: "#a855f7" }} />
            Fatigue (ATL)
          </div>
          <InfoTooltip>
            <p><strong className="text-slate-800 dark:text-white">Fitness (CTL)</strong> is a 42-day exponential moving average of your daily training stress. It rises slowly as you train consistently and decays gradually during rest.</p>
            <p><strong className="text-slate-800 dark:text-white">Fatigue (ATL)</strong> is a 7-day exponential moving average. It spikes quickly after hard sessions and drops rapidly during rest weeks.</p>
            <p className="text-slate-400 dark:text-slate-500 pt-1">To build fitness, keep the purple line regularly above the green line.</p>
          </InfoTooltip>
        </div>
      </div>

      {/* Training load chart */}
      <div key={`tl-${animKey}`} style={{ animation: "chartReveal 300ms ease-out both" }}>
        <ResponsiveContainer width="100%" height={190}>
          <ComposedChart data={chartData} syncId="fitness" syncMethod={snapToNearestDay} margin={MARGIN}>
            <CartesianGrid {...GRID} />
            <XAxis {...xAxisProps} hide />
            <YAxis {...AXIS} width={Y_WIDTH} ticks={loadAxis.ticks} domain={loadAxis.domain} />
            <Tooltip content={<LoadTooltip />} cursor={CURSOR} />
            <ReferenceLine x={TODAY_MS} stroke="#64748b60" strokeDasharray="4 2" />
            <Area type="monotone" dataKey="atl" stroke="#a855f7" fill="#a855f714"
              strokeWidth={1.5} dot={false} isAnimationActive={false}
              activeDot={{ r: 4, fill: "#a855f7", stroke: "#a855f740", strokeWidth: 3 }} />
            <Area type="monotone" dataKey="ctl" stroke="#10b981" fill="#10b98120"
              strokeWidth={2.5} dot={false} isAnimationActive={false}
              activeDot={{ r: 4, fill: "#10b981", stroke: "#10b98140", strokeWidth: 3 }} />
          </ComposedChart>
        </ResponsiveContainer>
      </div>

      {/* Form header + zone legend */}
      <div className="flex items-center justify-between gap-4 pt-1">
        <p className="text-xs font-medium text-slate-400 dark:text-slate-500 uppercase tracking-wide shrink-0">
          Form
        </p>
        <div className="flex items-center gap-3 flex-wrap justify-end">
          {ZONES.map(z => (
            <div key={z.label} className="flex items-center gap-1 text-xs" style={{ color: z.color }}>
              <span className="w-2 h-2 rounded-sm shrink-0" style={{ background: z.color, opacity: 0.85 }} />
              <span>{z.label}</span>
              <span className="text-slate-400 dark:text-slate-500">{z.desc}</span>
            </div>
          ))}
          <InfoTooltip>
            <p><strong className="text-slate-800 dark:text-white">Form (TSB)</strong> = Fitness − Fatigue. Negative means you are carrying fatigue; positive means you are rested.</p>
            <p>The <span style={{ color: "#4ade80" }}>Optimal zone</span> (-30 to -5) is where most fitness gains happen — training hard without overdoing it.</p>
            <p>The <span style={{ color: "#60a5fa" }}>Fresh zone</span> is ideal before a race. Avoid prolonged time in the <span style={{ color: "#ef4444" }}>High Risk zone</span> to prevent overtraining.</p>
          </InfoTooltip>
        </div>
      </div>

      {/* Form chart */}
      <div key={`form-${animKey}`} style={{ animation: "chartReveal 300ms 50ms ease-out both" }}>
        <ResponsiveContainer width="100%" height={170}>
          <ComposedChart data={chartData} syncId="fitness" syncMethod={snapToNearestDay} margin={{ ...MARGIN, bottom: 4 }}>
            <CartesianGrid {...GRID} vertical={false} />
            <XAxis {...xAxisProps} {...AXIS} />
            <YAxis
              {...AXIS}
              width={Y_WIDTH}
              ticks={tsbAxis.ticks}
              domain={tsbAxis.domain}
              tickFormatter={v => `${Math.round(v)}`}
            />
            <Tooltip content={() => null} cursor={CURSOR} />
            <ReferenceLine x={TODAY_MS} stroke="#64748b60" strokeDasharray="4 2" />
            <ReferenceLine y={0} stroke="#64748b50" />

            {/* Zone backgrounds drawn as SVG rects — no domain side-effects */}
            <Customized component={ZoneBgs} />

            {/* Invisible line so Recharts tracks hover position for the activeDot */}
            <Line
              dataKey="tsb"
              stroke="none"
              dot={false}
              activeDot={(props) => {
                const z = zoneFor(props.payload?.tsb);
                return (
                  <circle
                    key={props.cx}
                    cx={props.cx} cy={props.cy}
                    r={4}
                    fill={z.color}
                    stroke={z.color + "50"}
                    strokeWidth={3}
                  />
                );
              }}
              isAnimationActive={false}
              legendType="none"
            />

            {/* Zone-coloured TSB line */}
            <Customized component={ZoneLine} />
          </ComposedChart>
        </ResponsiveContainer>
      </div>

    </div>
  );
}
