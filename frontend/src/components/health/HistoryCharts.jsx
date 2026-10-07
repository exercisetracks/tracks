// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The charts behind a Health dial: the default one-metric history, and the
// two-line sleep chart (duration and score) the Sleep dial opens.
//
// ## Silence is drawn as silence
//
// Both charts follow the phone's HistoryChart rules, each of which exists
// because a chart once claimed data it did not have:
//
//   • Days are placed by date, so a fortnight between readings is a fortnight
//     of empty chart rather than two neighbouring points.
//   • The axis runs to today, not to the last reading, so a metric the watch
//     stopped recording six days ago ends six days short of the right edge
//     instead of finishing flush against it looking like this morning's number.
//   • The line breaks across a missing day — a stroke joining Monday to Friday
//     draws three days of readings nobody took — and a reading with no
//     neighbour is drawn as a dot, so it is visible rather than an invisible
//     endpoint of a line that is not there.

import {
  ComposedChart, Area, Line, XAxis, YAxis, CartesianGrid, Tooltip,
  ReferenceLine, ResponsiveContainer,
} from "recharts";
import { localIso, SLEEP_COLOR, SCORE_COLOR, SLEEP_GOAL_HOURS, verdictFor } from "./scales";
import { gaugeFigure } from "../RadialGauge";
import { hoursMinutes } from "./sleepClock";
import { CHART_GRID } from "../../design/chartGrid";

const DAY_MS = 86_400_000;

/** Local midnight of a YYYY-MM-DD, in milliseconds. */
export function dayMs(iso) {
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(y, m - 1, d).getTime();
}

/**
 * One row per calendar day from `start` to today, each carrying whatever the
 * `columns` hold for that date and null where nothing was recorded. The nulls
 * are what break the line; the dense rows are what let a lone reading be
 * recognised as one.
 */
export function dailyRows(start, columns) {
  const byDate = new Map();
  for (const [key, { dates, values }] of Object.entries(columns)) {
    dates.forEach((d, i) => {
      const row = byDate.get(d) ?? {};
      row[key] = values[i];
      byDate.set(d, row);
    });
  }
  const first = start ?? [...byDate.keys()].sort()[0];
  if (!first) return [];
  const rows = [];
  const today = dayMs(localIso());
  // Stepped by calendar date rather than by adding 24 hours, which a DST
  // change would knock off midnight.
  const [y, m, d] = first.split("-").map(Number);
  for (let i = 0; ; i++) {
    const at = new Date(y, m - 1, d + i);
    const ms = at.getTime();
    if (ms > today && i > 0) break;
    const iso = localIso(at);
    const row = { dateMs: ms, date: iso };
    for (const key of Object.keys(columns)) row[key] = byDate.get(iso)?.[key] ?? null;
    rows.push(row);
    if (i > 40_000) break; // a century; guards a malformed start date
  }
  return rows;
}

/** Evenly spaced date ticks — the middle of a chart needs a scale too. */
function dateTicks(rows, count = 5) {
  if (rows.length < 2) return rows.map(r => r.dateMs);
  const first = rows[0].dateMs;
  const last = rows[rows.length - 1].dateMs;
  const n = Math.min(count, rows.length);
  return Array.from({ length: n }, (_, i) => {
    const target = first + ((last - first) * i) / (n - 1);
    return Math.round((target - first) / DAY_MS) * DAY_MS + first;
  });
}

const fmtTick = ms => new Date(ms).toLocaleDateString(undefined, { month: "short", day: "numeric" });
const fmtLong = ms => new Date(ms).toLocaleDateString(undefined, { weekday: "short", month: "short", day: "numeric", year: "numeric" });

const AXIS = { fontSize: 10, fill: "#94a3b8" };

/**
 * A dot only where the line cannot be seen: a reading with no neighbour on
 * either side, and the newest one, which is the one the dial shows.
 */
function loneDot(key, color, rows) {
  const lastIndex = rows.findLastIndex(r => r[key] != null);
  return function Dot({ cx, cy, index }) {
    if (cx == null || cy == null || rows[index]?.[key] == null) return null;
    const lone = rows[index - 1]?.[key] == null && rows[index + 1]?.[key] == null;
    if (!lone && index !== lastIndex) return null;
    return <circle key={`${key}-${index}`} cx={cx} cy={cy} r={3.5} fill={color} />;
  };
}

/**
 * Faint lines on each day, while there are few enough days to count. A gap in
 * a date-positioned series is a stretch of empty chart, which is also what an
 * unremarkable Tuesday looks like; marking the days makes the gap countable.
 */
function dayMarks(rows, yAxisId) {
  if (rows.length > 32) return null;
  const opacity = rows.length > 15 ? 0.18 : 0.28;
  return rows.map(r => (
    // A chart with named y axes needs every reference line to name one.
    <ReferenceLine key={r.dateMs} x={r.dateMs} yAxisId={yAxisId} stroke="#94a3b8" strokeOpacity={opacity} />
  ));
}

function TooltipBox({ title, children }) {
  return (
    <div className="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-lg px-2.5 py-1.5 shadow-lg text-xs pointer-events-none">
      <p className="font-semibold text-slate-700 dark:text-slate-200 mb-0.5">{title}</p>
      {children}
    </div>
  );
}

/** One metric over the window, coloured by the band its latest reading sits in. */
export function MetricHistoryChart({ trend, start, color, zones, unit, decimals, anchorZero }) {
  const rows = dailyRows(start, { v: trend });
  if (!rows.length) return null;
  const gradientId = `hist-${color.replace("#", "")}`;

  const tooltip = ({ active, payload }) => {
    const row = payload?.[0]?.payload;
    if (!active || row?.v == null) return null;
    const verdict = verdictFor(row.v, zones);
    return (
      <TooltipBox title={fmtLong(row.dateMs)}>
        <p style={{ color: verdict?.color ?? color }}>
          <strong>{gaugeFigure(row.v, decimals)}</strong>{unit && ` ${unit}`}
          {verdict && <span className="opacity-70"> — {verdict.label}</span>}
        </p>
      </TooltipBox>
    );
  };

  return (
    <ResponsiveContainer width="100%" height={240}>
      <ComposedChart data={rows} margin={{ top: 8, right: 12, bottom: 0, left: 0 }}>
        <defs>
          <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor={color} stopOpacity={0.28} />
            <stop offset="100%" stopColor={color} stopOpacity={0.02} />
          </linearGradient>
        </defs>
        <CartesianGrid {...CHART_GRID} />
        {dayMarks(rows)}
        <XAxis dataKey="dateMs" type="number" scale="time"
          domain={[rows[0].dateMs, rows[rows.length - 1].dateMs]}
          ticks={dateTicks(rows)} tickFormatter={fmtTick}
          tick={AXIS} axisLine={false} tickLine={false} />
        <YAxis
          // Counted things start at zero: 9,000 steps against 10,000 is not
          // "nearly nothing", which is what a floor at 8,900 would show.
          domain={anchorZero ? [0, "auto"] : ["auto", "auto"]}
          tick={AXIS} axisLine={false} tickLine={false} width={44}
          tickFormatter={v => gaugeFigure(v, decimals)} />
        <Tooltip content={tooltip} />
        <Area type="linear" dataKey="v" stroke={color} strokeWidth={2}
          fill={`url(#${gradientId})`} connectNulls={false}
          dot={loneDot("v", color, rows)} activeDot={{ r: 4 }} isAnimationActive={false} />
      </ComposedChart>
    </ResponsiveContainer>
  );
}

/**
 * Sleep behind the Sleep dial: how long, and how good, on one time axis.
 *
 * Two lines on two scales — hours on the left, the watch's 0–100 score on the
 * right — because the question is whether they move together: a run of short
 * nights the score did not mind, or a long night it marked down, is exactly
 * what a single line cannot show. The stage breakdown lives in the Sleep card
 * on the page, which has the room to draw it properly.
 */
export function SleepTrendChart({ hours, score, start }) {
  const rows = dailyRows(start, { hours, score });
  if (!rows.length) return null;
  const peak = Math.max(SLEEP_GOAL_HOURS + 1, ...hours.values);

  const tooltip = ({ active, payload }) => {
    const row = payload?.[0]?.payload;
    if (!active || (row?.hours == null && row?.score == null)) return null;
    return (
      <TooltipBox title={fmtLong(row.dateMs)}>
        {row.hours != null && <p style={{ color: SLEEP_COLOR }}>Asleep <strong>{hoursMinutes(row.hours)}</strong></p>}
        {row.score != null && <p style={{ color: SCORE_COLOR }}>Score <strong>{Math.round(row.score)}</strong></p>}
      </TooltipBox>
    );
  };

  return (
    <div>
      <ResponsiveContainer width="100%" height={260}>
        <ComposedChart data={rows} margin={{ top: 8, right: 0, bottom: 0, left: 0 }}>
          <CartesianGrid {...CHART_GRID} />
          {dayMarks(rows, "h")}
          <XAxis dataKey="dateMs" type="number" scale="time"
            domain={[rows[0].dateMs, rows[rows.length - 1].dateMs]}
            ticks={dateTicks(rows)} tickFormatter={fmtTick}
            tick={AXIS} axisLine={false} tickLine={false} />
          <YAxis yAxisId="h" domain={[0, Math.ceil(peak)]} allowDecimals={false}
            tick={{ ...AXIS, fill: SLEEP_COLOR }} axisLine={false} tickLine={false} width={34}
            tickFormatter={v => `${v}h`} />
          <YAxis yAxisId="s" orientation="right" domain={[0, 100]} ticks={[0, 25, 50, 75, 100]}
            tick={{ ...AXIS, fill: SCORE_COLOR }} axisLine={false} tickLine={false} width={30} />
          <ReferenceLine yAxisId="h" y={SLEEP_GOAL_HOURS} stroke={SLEEP_COLOR}
            strokeOpacity={0.6} strokeDasharray="6 4" />
          <Tooltip content={tooltip} />
          <Line yAxisId="h" type="linear" dataKey="hours" stroke={SLEEP_COLOR} strokeWidth={2}
            connectNulls={false} dot={loneDot("hours", SLEEP_COLOR, rows)}
            activeDot={{ r: 4 }} isAnimationActive={false} />
          <Line yAxisId="s" type="linear" dataKey="score" stroke={SCORE_COLOR} strokeWidth={2}
            connectNulls={false} dot={loneDot("score", SCORE_COLOR, rows)}
            activeDot={{ r: 4 }} isAnimationActive={false} />
        </ComposedChart>
      </ResponsiveContainer>
      <div className="mt-2 flex justify-center gap-4 text-[11px] text-slate-500 dark:text-slate-400">
        <LegendLine color={SLEEP_COLOR} label="Duration" />
        <LegendLine color={SCORE_COLOR} label="Score" />
        <LegendLine color={SLEEP_COLOR} label={`${SLEEP_GOAL_HOURS}h goal`} dashed />
      </div>
    </div>
  );
}

function LegendLine({ color, label, dashed }) {
  return (
    <span className="flex items-center gap-1.5">
      <svg width="16" height="6" aria-hidden="true">
        <line x1="0" y1="3" x2="16" y2="3" stroke={color} strokeWidth="2"
          strokeDasharray={dashed ? "4 3" : undefined} strokeOpacity={dashed ? 0.6 : 1} />
      </svg>
      {label}
    </span>
  );
}
