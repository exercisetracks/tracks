// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// A titled group of Health dials, each of which opens its own history.
//
// ## Why dials and not a chart per metric
//
// The page used to be a strip of bare numbers over a metric picker driving one
// chart. A number answers nothing for someone who has not memorised the
// ranges — resting heart rate 58 means nothing; 58 in a green band called Good
// means something immediately — and a picker makes you choose a metric before
// you know which one is worth looking at. So every reading is a dial against
// its scale, the whole set is visible at once, and the shape over time is one
// click behind each of them. The phone's Health screen settled on the same
// design (HealthMeters.kt); this is its port.
//
// A metric with no readings keeps its dial and shows an em dash. The grid is
// a fixed list rather than whatever happens to have data, because a tile that
// disappears when the watch has not reported makes a gap in the data look like
// a bug in the page.

import { useState } from "react";
import RadialGauge from "../RadialGauge";
import HistoryModal from "./HistoryModal";
import { InfoButton, InfoPanel } from "./ui";
import { MetricHistoryChart } from "./HistoryCharts";
import { FRESH_DAYS, currentOf, verdictFor, zonesFor } from "./scales";

const GAUGE_SIZE = 124;

/** Tailwind needs literal class names, so the column counts are spelled out. */
const COLUMNS = {
  3: "grid-cols-3",
  6: "grid-cols-3 sm:grid-cols-6",
};

export default function MetricGaugeGroup({ title, info, metrics, start, missingHint, aside, dataTour, columns = 3 }) {
  const [open, setOpen] = useState(null);
  const [showInfo, setShowInfo] = useState(false);
  const anyData = metrics.some(m => m.trend.values.length > 0);
  const openMetric = metrics.find(m => m.key === open);

  return (
    // A column that fills its grid cell, so groups side by side end level
    // whatever each one holds.
    <section data-tour={dataTour} className="flex flex-col h-full">
      <div className="flex items-center gap-2 mb-3">
        <h2 className="section-title">
          {title}
        </h2>
        {info && (
          <InfoButton open={showInfo} onToggle={() => setShowInfo(s => !s)} label={`About ${title}`} />
        )}
      </div>
      {/* The aside (Body's Log today) is a strip down the card's right edge
          rather than a row under the dials: as a row it made Body taller than
          Activity beside it, and the two groups' dials sat at different
          heights. Down the side, both cards are only as tall as their dials. */}
      <div className="card flex-1 flex gap-3">
        <div className="flex-1 min-w-0 flex flex-col">
          {showInfo && info && <InfoPanel body={info.body} className="mb-3" />}
          {/* Top-aligned, not centred: a group with a hint line under its
              dials (Body, before anything is logged) would otherwise centre
              them higher than its neighbour's, and side-by-side groups are
              meant to read as one row of dials. */}
          <div className={`grid ${COLUMNS[columns] ?? COLUMNS[3]} gap-1`}>
            {metrics.map(m => (
              <GaugeTile key={m.key} metric={m} start={start} onOpen={() => setOpen(m.key)} />
            ))}
          </div>
          {!anyData && missingHint && (
            <p className="mt-2 text-xs text-center text-slate-400 dark:text-slate-500">{missingHint}</p>
          )}
        </div>
        {aside && <div className="shrink-0 flex">{aside}</div>}
      </div>

      {openMetric && (
        <MetricHistory metric={openMetric} start={start} onClose={() => setOpen(null)} />
      )}
    </section>
  );
}

function freshDaysOf(metric) {
  return metric.freshDays === undefined ? FRESH_DAYS : metric.freshDays;
}

/**
 * One dial. The whole tile is the click target, label included, and it opens
 * on any reading ever — including one too old to show on the dial, since a
 * blanked dial is exactly when somebody wants to know when the watch last
 * managed one, and the history is where that is written.
 */
function GaugeTile({ metric, onOpen }) {
  // Past its freshness window a reading is no reading at all: the figure
  // blanks, the verdict word goes, and the arc shows its scale with nothing
  // filled.
  const latest = currentOf(metric.trend, freshDaysOf(metric));
  const zones = zonesFor(metric.scale, metric.trend.values);
  const verdict = metric.verdict ? verdictFor(latest, metric.verdict) : null;
  const clickable = metric.trend.values.length > 0;

  return (
    <button
      type="button"
      onClick={clickable ? onOpen : undefined}
      disabled={!clickable}
      aria-label={`${metric.longLabel ?? metric.label}: open history`}
      className="group flex flex-col items-center justify-center rounded-xl py-2 transition-transform duration-150 enabled:hover:scale-[1.04] enabled:hover:bg-slate-50 dark:enabled:hover:bg-slate-800/50 focus:outline-none focus-visible:ring-2 focus-visible:ring-teal-500 disabled:cursor-default"
    >
      <RadialGauge
        value={latest}
        zones={zones}
        size={GAUGE_SIZE}
        stroke={9}
        unit={metric.unit}
        decimals={metric.decimals ?? 0}
        caption={metric.caption ?? verdict?.label}
        // Without this the word would take the colour of whichever band the
        // total lands in, which on a stacked arc is the last stage or the dim
        // remainder — "Good" printed in grey.
        captionColor={verdict?.color}
        lap
      />
      <span className="-mt-3 text-[11px] font-semibold uppercase tracking-wide text-slate-500 dark:text-slate-400">
        {metric.label}
      </span>
    </button>
  );
}

function MetricHistory({ metric, start, onClose }) {
  const zones = zonesFor(metric.scale, metric.trend.values);
  const latest = metric.trend.values[metric.trend.values.length - 1];
  const colour = verdictFor(latest, metric.verdict ?? zones)?.color ?? "#14b8a6";

  return (
    <HistoryModal
      title={metric.longLabel ?? metric.label}
      info={metric.info}
      trend={metric.trend}
      zones={zones}
      // A custom chart brings its own legend, and a duration scale under a
      // chart of something else would describe the wrong picture.
      strip={!metric.chart}
      verdict={metric.verdict}
      unit={metric.unit}
      decimals={metric.decimals ?? 0}
      freshDays={freshDaysOf(metric)}
      headline={metric.headline ?? true}
      breakdown={metric.breakdown ?? []}
      onClose={onClose}
      chart={metric.chart ? metric.chart() : (
        <MetricHistoryChart
          trend={metric.trend}
          start={start}
          color={colour}
          zones={metric.verdict ?? zones}
          unit={metric.unit}
          decimals={metric.decimals ?? 0}
          anchorZero={zones[0]?.min === 0}
        />
      )}
    />
  );
}
