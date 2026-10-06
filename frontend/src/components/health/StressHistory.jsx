// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Stress over the window, at whatever resolution the window can carry — the
// chart behind the Stress dial. A port of the phone's StressHistoryPanel
// (HealthStress.kt); the reasoning below is condensed from there.
//
// ## Every reading, not one point per day
//
// A day's stress is a curve, and its mean is not the same reading: a calm
// morning and a shattering afternoon average to exactly the same 40 as a flat,
// mediocre day. Up to a month, the chart draws the watch's own readings (one
// every few minutes, from /health/stress). Past that, three-minute samples are
// a fifth of a pixel each and megabytes to fetch, so the daily averages already
// on the metric rows are drawn instead — and the footer says which.
//
// ## A line coloured by height
//
// The line is stroked with a vertical gradient of the four band colours, stops
// doubled at each boundary so the bands meet as hard edges: crossing 50 is a
// change of state, not a shade. The area under it takes the same gradient,
// faded, which answers the second question without a legend — a day that spent
// the afternoon in the orange has an orange afternoon on the chart.
//
// Readings are averaged into columns a couple of pixels wide rather than drawn
// one per point, so the shape does not depend on which of fifty overlapping
// readings happened to be drawn last. The line breaks wherever a column has no
// readings: a line drawn across a charging day would say the watch was worn.

import { useMemo } from "react";
import useWidth from "./useWidth";
import { STRESS_ZONES, localIso } from "./scales";
import { dayMs } from "./HistoryCharts";

const PLOT_H = 200;
const AXIS_H = 20;
/** Room above the plot for half of its top label. */
const TOP = 8;
const GUTTER = 30;
const COLUMN_PX = 2;
const MAX_COLUMNS = 900;
const BAND_ALPHA = 0.05;
const FILL_ALPHA = 0.35;

/** Days between two YYYY-MM-DD dates, ignoring DST. */
function dayOffset(fromIso, toIso) {
  return Math.round((dayMs(toIso) - dayMs(fromIso)) / 86_400_000);
}

/**
 * Every sample as a position along the window (0–1) and a level.
 *
 * The axis runs from the window's first day to the *end* of today, so this
 * afternoon's readings sit where this afternoon is rather than being pressed
 * against the right edge. Daily averages hang at midday: an average describes
 * the whole day, and putting it at midnight would draw a year of them half a
 * day early.
 */
export function stressSamples({ days, averages, start }) {
  const intraday = days.some(d => d.points?.length);
  const dates = intraday ? days.map(d => d.date) : averages.dates;
  if (!dates.length) return { intraday, samples: [], span: 0, from: null };
  const from = start ?? [...dates].sort()[0];
  const span = Math.max(1, dayOffset(from, localIso()) + 1);
  const at = (date, within) => {
    const offset = dayOffset(from, date);
    if (offset < 0 || offset >= span) return null;
    return (offset + within) / span;
  };

  const samples = [];
  if (intraday) {
    for (const day of days) {
      for (const pair of day.points ?? []) {
        // The wire form is two anonymous numbers; a short pair costs its own
        // reading rather than the chart.
        if (!Array.isArray(pair) || pair.length < 2) continue;
        const x = at(day.date, Math.min(Math.max(pair[0] / 1440, 0), 1));
        if (x != null) samples.push({ x, level: pair[1] });
      }
    }
  } else {
    averages.dates.forEach((d, i) => {
      const x = at(d, 0.5);
      if (x != null) samples.push({ x, level: averages.values[i] });
    });
  }
  samples.sort((a, b) => a.x - b.x);
  return { intraday, samples, span, from };
}

/** Averaged columns, and the runs of consecutive non-empty ones. */
export function stressRuns(samples, columns) {
  const total = new Float64Array(columns);
  const count = new Uint32Array(columns);
  for (const s of samples) {
    const c = Math.min(columns - 1, Math.max(0, Math.floor(s.x * columns)));
    total[c] += s.level;
    count[c]++;
  }
  const runs = [];
  let run = [];
  for (let c = 0; c < columns; c++) {
    if (!count[c]) {
      if (run.length) runs.push(run);
      run = [];
      continue;
    }
    run.push({ c, level: total[c] / count[c] });
  }
  if (run.length) runs.push(run);
  return runs;
}

export default function StressHistory({ days, averages, start }) {
  const [ref, width] = useWidth(640);
  const { intraday, samples, span, from } = useMemo(
    () => stressSamples({ days, averages, start }),
    [days, averages, start],
  );

  if (!samples.length) {
    // Inside the measured element, so the observer is still attached when the
    // readings arrive and the chart replaces this line.
    return (
      <div ref={ref}>
        <p className="py-10 text-center text-sm text-slate-400">No stress readings in this window.</p>
      </div>
    );
  }

  const plotW = Math.max(1, width - GUTTER);
  const columns = Math.max(1, Math.min(MAX_COLUMNS, Math.floor(plotW / COLUMN_PX)));
  const runs = stressRuns(samples, columns);
  const average = samples.reduce((s, p) => s + p.level, 0) / samples.length;

  // Fixed 0–100, never fitted to the window: the bands mean fixed numbers, and
  // a chart that rescaled to a calm fortnight would put the same 30 in a
  // different place from one week to the next.
  const y = level => PLOT_H * (1 - level / 100);
  const xOf = c => GUTTER + ((c + 0.5) / columns) * plotW;
  const runPath = run => run.map((p, i) => `${i ? "L" : "M"}${xOf(p.c).toFixed(1)},${y(p.level).toFixed(1)}`).join("");

  const stops = [...STRESS_ZONES].reverse().flatMap(z => [
    { offset: 1 - z.max / 100, color: z.color },
    { offset: 1 - z.min / 100, color: z.color },
  ]);

  // Midnights, while they are few enough to read as midnights. They are what
  // turn a trace into a habit: the same 3pm peak four days out of seven is
  // only visible against the day it sits in.
  const marks = span <= 31 ? Array.from({ length: span - 1 }, (_, i) => GUTTER + ((i + 1) / span) * plotW) : [];
  const labels = dateLabels(from, span, plotW);

  return (
    <div ref={ref} className="space-y-2">
      <svg width={width} height={TOP + PLOT_H + AXIS_H} role="img" aria-label="Stress history chart">
        <g transform={`translate(0 ${TOP})`}>
        <defs>
          <linearGradient id="stress-line" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="0" y2={PLOT_H}>
            {stops.map((s, i) => <stop key={i} offset={s.offset} stopColor={s.color} />)}
          </linearGradient>
          <linearGradient id="stress-fill" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="0" y2={PLOT_H}>
            {stops.map((s, i) => <stop key={i} offset={s.offset} stopColor={s.color} stopOpacity={FILL_ALPHA} />)}
          </linearGradient>
        </defs>

        {/* The bands, faint: ground rather than figure. They say what a height
            means in the stretches where there is no line. */}
        {STRESS_ZONES.map(z => (
          <rect key={z.label} x={GUTTER} y={y(z.max)} width={plotW} height={y(z.min) - y(z.max)}
            fill={z.color} fillOpacity={BAND_ALPHA} />
        ))}
        {[0, 25, 50, 75, 100].map(t => (
          <g key={t}>
            <line x1={GUTTER} x2={width} y1={y(t)} y2={y(t)} stroke="#94a3b8" strokeOpacity={0.2} />
            <text x={GUTTER - 6} y={y(t)} dy="0.32em" textAnchor="end" fontSize={10} fill="#94a3b8">{t}</text>
          </g>
        ))}
        {marks.map(x => (
          <line key={x} x1={x} x2={x} y1={0} y2={PLOT_H} stroke="#94a3b8"
            strokeOpacity={span > 14 ? 0.2 : 0.3} />
        ))}

        {runs.map((run, i) => run.length > 1 ? (
          <g key={i}>
            <path
              d={`${runPath(run)}L${xOf(run[run.length - 1].c).toFixed(1)},${PLOT_H}L${xOf(run[0].c).toFixed(1)},${PLOT_H}Z`}
              fill="url(#stress-fill)" />
            <path d={runPath(run)} fill="none" stroke="url(#stress-line)" strokeWidth={2}
              strokeLinejoin="round" strokeLinecap="round" />
          </g>
        ) : (
          // A single worn hour in a blank week is still there, as a dot.
          <circle key={i} cx={xOf(run[0].c)} cy={y(run[0].level)} r={2.5} fill="url(#stress-line)" />
        ))}

        {/* The window's average — a reference, not a reading. */}
        <line x1={GUTTER} x2={width} y1={y(average)} y2={y(average)}
          stroke="#94a3b8" strokeOpacity={0.85} strokeWidth={1.5} strokeDasharray="8 6" />

        {labels.map(l => (
          <text key={l.x} x={l.x} y={PLOT_H + 14} textAnchor={l.anchor} fontSize={10} fill="#94a3b8">{l.text}</text>
        ))}
        </g>
      </svg>

      <p className="text-center text-[11px] text-slate-500 dark:text-slate-400">
        {/* Said out loud: a spike is twenty minutes in one, a whole day in the other. */}
        avg {Math.round(average)} · {intraday ? "every reading" : "daily average"}
      </p>
      <div className="flex justify-center gap-3 text-[11px] text-slate-500 dark:text-slate-400">
        {STRESS_ZONES.map(z => (
          <span key={z.label} className="flex items-center gap-1">
            <span className="inline-block w-2 h-2 rounded-sm" style={{ background: z.color }} />
            {z.label}
          </span>
        ))}
      </div>
    </div>
  );
}

/** Up to five dates under the plot, the ends aligned inwards so they stay inside it. */
function dateLabels(from, span, plotW) {
  if (!from) return [];
  const count = Math.max(2, Math.min(5, Math.floor(plotW / 110)));
  const [y, m, d] = from.split("-").map(Number);
  return Array.from({ length: count }, (_, i) => {
    const f = i / (count - 1);
    const day = Math.min(span - 1, Math.round(f * (span - 1)));
    const text = new Date(y, m - 1, d + day).toLocaleDateString(undefined, { month: "short", day: "numeric" });
    // Centred on the middle of the day it names.
    const x = GUTTER + ((day + 0.5) / span) * plotW;
    return { x: i === 0 ? GUTTER : i === count - 1 ? GUTTER + plotW : x, text, anchor: i === 0 ? "start" : i === count - 1 ? "end" : "middle" };
  });
}
