// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The Sleep card: every night in the window on a clock, and the chosen night
// minute by minute beside it. A port of the phone's SleepHistoryPanel
// (HealthSleep.kt), which draws the same picture behind its Sleep dial; the
// desktop has the room to keep it on the page, so the dial opens a plain
// duration-and-score chart instead.
//
// ## Why the y axis is a time of day and not a number of hours
//
// Bars measured from zero say how much somebody slept, which is half of what a
// fortnight of sleep is looked at for. The other half is when: a run of
// seven-hour nights that started at eleven and a run that wandered between ten
// and three are identical on a duration axis and completely different weeks to
// live through. So each night is drawn as the block of time it occupied — top
// at lights out, bottom at waking — on an axis fitted to the hours actually
// slept. The height is still the duration, so nothing is lost. Two dashed lines
// mark the average bedtime and waking, because consistency is what is being
// read for and the eye judges it far better against a reference.
//
// ## The stages inside the block
//
// Deep, REM, light, then waking, top to bottom — the same fixed order as the
// dial's arc and the legend, so a colour means a stage everywhere. The order is
// not a claim about when each stage happened; the hypnogram knows that. The
// parts are scaled to fill the block exactly: what the watch could not classify
// is the difference between the two, and left as a gap at the foot of every
// bar it would read as an hour awake.
//
// Nights sit where they happened, so a night the watch did not record is blank
// space of the right width. A night whose totals survived but whose timeline
// did not cannot be placed on a clock at all; those are counted under the
// chart rather than dropped in silence.

import { useEffect, useMemo, useRef, useState } from "react";
import { api } from "../../api/client";
import useWidth from "./useWidth";
import { SLEEP_COLORS } from "./constants";
import { SCORE_COLOR, localIso } from "./scales";
import { clockLabel, clockScale, hoursMinutes, laneOf, nightClock } from "./sleepClock";
import { dayMs } from "./HistoryCharts";
import { EXPLAIN } from "./explain";
import { InfoButton, InfoPanel } from "./ui";

const AWAKE = SLEEP_COLORS.other;
const PLOT_H = 280;
const AXIS_H = 20;
/** Room above the plot for half of its top clock label. */
const TOP = 8;
const GUTTER = 52;

export default function SleepPanel({ nights, start }) {
  const [selected, setSelected] = useState(null);
  const [showInfo, setShowInfo] = useState(false);
  const chosen = nights.find(n => n.date === selected) ?? nights[nights.length - 1] ?? null;

  return (
    <section data-tour="health-sleep">
      <div className="flex items-center gap-2 mb-3">
        <h2 className="section-title">Sleep</h2>
        <InfoButton open={showInfo} onToggle={() => setShowInfo(s => !s)} label="About the sleep chart" />
      </div>
      <div className="card">
        {showInfo && <InfoPanel body={EXPLAIN.sleepStages.body} className="mb-4" />}
        {!chosen ? (
          <p className="py-10 text-center text-sm text-slate-400 dark:text-slate-500">
            No sleep recorded in this window. Worn overnight, the watch records it while you sleep.
          </p>
        ) : (
          // minmax(0, …): the charts draw at a measured pixel width, and an auto
          // minimum would let that width hold a column open past the card.
          <div className="grid grid-cols-1 lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)] gap-6">
            <SleepClockChart nights={nights} start={start} selected={chosen.date} onSelect={setSelected} />
            <NightDetail night={chosen} />
          </div>
        )}
      </div>
    </section>
  );
}

// ── The run of nights ────────────────────────────────────────────────────────

function SleepClockChart({ nights, start, selected, onSelect }) {
  const [ref, width] = useWidth(700);

  const from = start ?? nights[0]?.date;
  // Inclusive of today, so last night has a slot of its own at the right edge.
  const slots = Math.max(1, Math.round((dayMs(localIso()) - dayMs(from)) / 86_400_000) + 1);

  const placed = useMemo(() => nights.flatMap(night => {
    const clock = nightClock(night);
    if (!clock) return [];
    const offset = Math.round((dayMs(night.date) - dayMs(from)) / 86_400_000);
    if (offset < 0 || offset >= slots) return [];
    return [{ night, clock, offset }];
  }), [nights, from, slots]);
  const untimed = nights.length - placed.length;

  if (!placed.length) {
    return (
      <div ref={ref} className="space-y-2">
        <p className="py-10 text-center text-sm text-slate-400">No night in this window has recorded times yet.</p>
        <Untimed count={untimed} />
      </div>
    );
  }

  const scale = clockScale(Math.min(...placed.map(p => p.clock.from)), Math.max(...placed.map(p => p.clock.to)));
  const habit = {
    from: placed.reduce((s, p) => s + p.clock.from, 0) / placed.length,
    to: placed.reduce((s, p) => s + p.clock.to, 0) / placed.length,
  };
  const plotW = Math.max(1, width - GUTTER);
  // A day wide, minus a gap, and never more — sized from the slot, never from
  // the number of nights, or uneven readings draw overlapping blobs.
  const barW = Math.max(2, Math.min(24, (plotW / slots) * 0.8));
  const xOf = offset => GUTTER + ((offset + 0.5) / slots) * plotW;
  const y = hours => PLOT_H * ((hours - scale.min) / (scale.max - scale.min));
  // The score rides over the nights on its own 0–100: full height is 100. It
  // has no ticks, so a score of 80 cannot be read as eight in the evening.
  const scoreY = s => PLOT_H * (1 - Math.max(0, Math.min(100, s)) / 100);

  const scored = placed.filter(p => p.night.score != null);

  function pick(e) {
    const rect = e.currentTarget.getBoundingClientRect();
    const x = e.clientX - rect.left;
    // Nearest by position, not by slot: the nights are not evenly spaced.
    let best = null;
    for (const p of placed) {
      const d = Math.abs(xOf(p.offset) - x);
      if (!best || d < best.d) best = { d, date: p.night.date };
    }
    if (best) onSelect(best.date);
  }

  return (
    <div ref={ref} className="space-y-2">
      <svg width={width} height={TOP + PLOT_H + AXIS_H} onClick={pick} className="cursor-pointer select-none"
        role="img" aria-label="Sleep history on a clock">
        <g transform={`translate(0 ${TOP})`}>
        {scale.ticks.map(t => (
          <g key={t}>
            {/* A shade stronger than the usual chart furniture: on a clock
                axis the gridline is the reading — a bar's top means nothing
                except against the hour beside it. */}
            <line x1={GUTTER} x2={width} y1={y(t)} y2={y(t)} stroke="#94a3b8" strokeOpacity={0.3} />
            <text x={GUTTER - 6} y={y(t)} dy="0.32em" textAnchor="end" fontSize={10} fill="#94a3b8">{clockLabel(t)}</text>
          </g>
        ))}

        {[habit.from, habit.to].map((h, i) => (
          <line key={i} x1={GUTTER} x2={width} y1={y(h)} y2={y(h)}
            stroke="#94a3b8" strokeOpacity={0.85} strokeWidth={1.5} strokeDasharray="8 6" />
        ))}

        {placed.map(({ night, clock, offset }) => {
          const top = y(clock.from);
          const blockH = Math.max(2, y(clock.to) - top);
          const left = xOf(offset) - barW / 2;
          const parts = [
            [night.deep, SLEEP_COLORS.deep],
            [night.rem, SLEEP_COLORS.rem],
            [night.light, SLEEP_COLORS.light],
            [night.restless, AWAKE],
          ].filter(([h]) => h > 0);
          const measured = parts.reduce((s, [h]) => s + h, 0);
          let at = top;
          const isSelected = night.date === selected;
          return (
            <g key={night.date}>
              <title>{tooltipFor(night, clock)}</title>
              {/* A hit area the full height of the plot, so a hairline bar on
                  a long window is still something you can point at. */}
              <rect x={left} y={0} width={barW} height={PLOT_H} fill="transparent" />
              {measured > 0 ? parts.map(([h, color]) => {
                const segH = (h / measured) * blockH;
                const r = <rect key={color} x={left} y={at} width={barW} height={segH} rx={2} fill={color} />;
                at += segH;
                return r;
              }) : (
                // Timed but never staged: still the truth about when it happened.
                <rect x={left} y={top} width={barW} height={blockH} rx={2} fill={SLEEP_COLORS.light} fillOpacity={0.45} />
              )}
              {/* The chosen night is marked under the bar rather than by
                  recolouring it: the colours already mean the stages. */}
              {isSelected && (
                <rect x={xOf(offset) - Math.max(barW, 8) / 2} y={PLOT_H - 3} width={Math.max(barW, 8)} height={3}
                  rx={1.5} className="fill-slate-700 dark:fill-slate-200" />
              )}
            </g>
          );
        })}

        {/* Joined only to the following night, never across a gap: a segment
            from Monday to Friday draws three nights that were never scored. */}
        {scored.map((p, i) => {
          const next = scored[i + 1];
          const x = xOf(p.offset);
          const ys = scoreY(p.night.score);
          return (
            <g key={`s-${p.night.date}`} pointerEvents="none">
              {next && next.offset === p.offset + 1 && (
                <line x1={x} y1={ys} x2={xOf(next.offset)} y2={scoreY(next.night.score)}
                  stroke={SCORE_COLOR} strokeWidth={1.5} strokeLinecap="round" />
              )}
              <circle cx={x} cy={ys} r={2.5} fill={SCORE_COLOR} />
            </g>
          );
        })}

        <DateAxis from={from} slots={slots} plotW={plotW} />
        </g>
      </svg>

      <p className="text-center text-[11px] text-slate-500 dark:text-slate-400">
        {/* Named: two unexplained dashed lines are furniture; two lines that
            say "this is your usual night" are the point of the chart. */}
        avg {clockLabel(habit.from)}–{clockLabel(habit.to)} · click a night
      </p>
      <div className="flex justify-center flex-wrap gap-3 text-[11px] text-slate-500 dark:text-slate-400">
        {[["Deep", SLEEP_COLORS.deep], ["REM", SLEEP_COLORS.rem], ["Light", SLEEP_COLORS.light], ["Awake", AWAKE], ["Score", SCORE_COLOR]].map(([label, color]) => (
          <span key={label} className="flex items-center gap-1">
            <span className="inline-block w-2 h-2 rounded-sm" style={{ background: color }} />
            {label}
          </span>
        ))}
      </div>
      <Untimed count={untimed} />
    </div>
  );
}

function tooltipFor(night, clock) {
  const lines = [
    longDate(night.date),
    `${clockLabel(clock.from)} – ${clockLabel(clock.to)}`,
    `${hoursMinutes(night.total)} asleep`,
  ];
  if (night.restless > 0.02) lines.push(`${hoursMinutes(night.restless)} awake`);
  if (night.score != null) lines.push(`Score ${Math.round(night.score)}`);
  return lines.join("\n");
}

function Untimed({ count }) {
  if (count <= 0) return null;
  return (
    <p className="text-center text-[11px] text-slate-400 dark:text-slate-500">
      {count === 1
        ? "1 night has totals but no recorded times, so it is not on the chart."
        : `${count} nights have totals but no recorded times, so they are not on the chart.`}
    </p>
  );
}

function DateAxis({ from, slots, plotW }) {
  const count = Math.max(2, Math.min(6, Math.floor(plotW / 100)));
  const [y, m, d] = from.split("-").map(Number);
  return Array.from({ length: count }, (_, i) => {
    const day = Math.round((i / (count - 1)) * (slots - 1));
    const text = new Date(y, m - 1, d + day).toLocaleDateString(undefined, { month: "short", day: "numeric" });
    const anchor = i === 0 ? "start" : i === count - 1 ? "end" : "middle";
    const x = i === 0 ? GUTTER : i === count - 1 ? GUTTER + plotW : GUTTER + ((day + 0.5) / slots) * plotW;
    return <text key={i} x={x} y={PLOT_H + 14} textAnchor={anchor} fontSize={10} fill="#94a3b8">{text}</text>;
  });
}

// ── The chosen night ─────────────────────────────────────────────────────────

/**
 * One night, minute by minute — or its totals when the timeline is missing.
 * Every night recorded before the server began keeping stage timelines has
 * totals and nothing else, which is not an error and must not read as one.
 */
function NightDetail({ night }) {
  // Cached per night for the life of the page: opening a night twice should
  // not fetch it twice, and an empty answer is an answer worth keeping.
  const cache = useRef(new Map());
  const [stages, setStages] = useState(() => cache.current.get(night.date) ?? null);

  useEffect(() => {
    let live = true;
    const hit = cache.current.get(night.date);
    if (hit) { setStages(hit); return undefined; }
    setStages(null);
    api.getSleepNight(night.date)
      .then(r => { cache.current.set(night.date, r?.stages ?? []); if (live) setStages(r?.stages ?? []); })
      .catch(() => { if (live) setStages([]); });
    return () => { live = false; };
  }, [night.date]);

  return (
    <div className="space-y-4">
      <div>
        <p className="text-xs font-medium text-slate-500 dark:text-slate-400">{longDate(night.date)}</p>
        <div className="mt-1 flex items-baseline gap-3 flex-wrap">
          <span className="text-2xl font-bold text-slate-900 dark:text-white tabular-nums">{hoursMinutes(night.total)}</span>
          <span className="text-sm text-slate-500 dark:text-slate-400">asleep</span>
          {/* Only when there is waking to report: "0m awake" is noise,
              "1h 10m awake" is the whole story of the night. */}
          {night.restless > 0.02 && (
            <span className="text-sm font-medium" style={{ color: AWAKE }}>{hoursMinutes(night.restless)} awake</span>
          )}
          {night.score != null && (
            <span className="text-sm font-medium" style={{ color: SCORE_COLOR }}>score {Math.round(night.score)}</span>
          )}
        </div>
        <StageSplit night={night} />
      </div>

      {stages == null ? (
        <div className="h-28 rounded-lg bg-slate-50 dark:bg-slate-800/50 animate-pulse" />
      ) : stages.length ? (
        <Hypnogram stages={stages} />
      ) : (
        <p className="text-sm text-slate-400 dark:text-slate-500">No stage timeline recorded for this night.</p>
      )}
    </div>
  );
}

function StageSplit({ night }) {
  const parts = [
    ["Deep", night.deep, SLEEP_COLORS.deep],
    ["REM", night.rem, SLEEP_COLORS.rem],
    ["Light", night.light, SLEEP_COLORS.light],
  ].filter(([, h]) => h > 0);
  if (!parts.length) return null;
  return (
    <div className="mt-2 flex gap-4 text-xs">
      {parts.map(([label, h, color]) => (
        <span key={label} style={{ color }}>{label} <strong>{hoursMinutes(h)}</strong></span>
      ))}
    </div>
  );
}

const LANES = [
  { label: "Awake", color: AWAKE },
  { label: "REM", color: SLEEP_COLORS.rem },
  { label: "Light", color: SLEEP_COLORS.light },
  { label: "Deep", color: SLEEP_COLORS.deep },
];
const LANE_H = 26;
const LANE_LABEL_W = 44;

/**
 * The night as the watch saw it, deepest at the bottom — the arrangement
 * Garmin Connect uses, and the one that makes the shape legible: cycles read
 * as a sawtooth, a broken night as a row of marks along the top lane. Every
 * span is drawn at its true width, so a twenty-minute REM block is a fifth the
 * width of a hundred-minute deep one.
 */
function Hypnogram({ stages }) {
  const [ref, width] = useWidth(400);
  const spans = stages
    .map(s => ({ start: new Date(s.start).getTime(), end: new Date(s.end).getTime(), lane: laneOf(s.level) }))
    .filter(s => Number.isFinite(s.start) && Number.isFinite(s.end) && s.end > s.start)
    .sort((a, b) => a.start - b.start);
  if (!spans.length) {
    return <p className="text-sm text-slate-400 dark:text-slate-500">No stage timeline recorded for this night.</p>;
  }
  const from = spans[0].start;
  const to = spans[spans.length - 1].end;
  const total = Math.max(1, to - from);
  const plotW = Math.max(1, width - LANE_LABEL_W);
  const x = t => LANE_LABEL_W + ((t - from) / total) * plotW;
  const clock = t => new Date(t).toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" });

  return (
    <div ref={ref} className="space-y-1.5">
      <svg width={width} height={LANE_H * LANES.length} role="img" aria-label="Sleep stages through the night">
        {LANES.map((lane, i) => (
          <g key={lane.label}>
            {/* A faint band per lane, so a lane with no time in it is still a lane. */}
            <rect x={LANE_LABEL_W} y={i * LANE_H} width={plotW} height={LANE_H - 1} fill="#94a3b8" fillOpacity={0.08} />
            <text x={0} y={i * LANE_H + LANE_H / 2} dy="0.32em" fontSize={10} fill="#94a3b8">{lane.label}</text>
          </g>
        ))}
        {spans.map((s, i) => (
          <rect key={i} x={x(s.start)} y={s.lane * LANE_H + LANE_H * 0.18}
            // A one-minute waking is worth seeing, so nothing goes sub-pixel.
            width={Math.max(1.5, x(s.end) - x(s.start))} height={LANE_H * 0.64} rx={2}
            fill={LANES[s.lane].color} />
        ))}
      </svg>
      <div className="flex justify-between text-[11px] text-slate-500 dark:text-slate-400" style={{ paddingLeft: LANE_LABEL_W }}>
        <span>{clock(from)}</span>
        <span>{hoursMinutes((to - from) / 3_600_000)} in bed</span>
        <span>{clock(to)}</span>
      </div>
    </div>
  );
}

function longDate(iso) {
  return new Date(iso + "T00:00:00").toLocaleDateString(undefined, { weekday: "short", month: "short", day: "numeric" });
}
