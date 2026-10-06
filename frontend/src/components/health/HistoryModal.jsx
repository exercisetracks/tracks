// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The history behind a Health dial, in a popup — the desktop counterpart of
// the phone's MetricHistorySheet, and the same popup shape the dashboard's
// Readiness and VO₂max dials open.
//
// The dial answers "where am I now"; this answers "and how did I get here",
// which is the question that changes a decision. A resting heart rate of 52 is
// good; a 52 that was 47 a fortnight ago is worth a rest day.
//
// The explanation sits behind a "?" beside the title rather than above the
// chart: it is read once and scrolled past every time afterwards, and the
// chart is what the click was for.

import { useEffect, useRef, useState } from "react";
import { gaugeFigure, zoneFor } from "../RadialGauge";
import { isFresh, verdictFor } from "./scales";

const shortDate = iso => new Date(iso + "T00:00:00").toLocaleDateString(undefined, { month: "short", day: "numeric" });

export default function HistoryModal({
  title, info, trend, zones, verdict, unit = "", decimals = 0,
  freshDays, headline = true, breakdown = [], chart, strip = true, onClose,
}) {
  const overlayRef = useRef(null);
  const [showInfo, setShowInfo] = useState(false);

  useEffect(() => {
    function onKey(e) { if (e.key === "Escape") onClose(); }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose]);

  const n = trend.values.length;
  const latest = n ? trend.values[n - 1] : null;
  const latestDate = n ? trend.dates[n - 1] : null;
  const zone = verdictFor(latest, verdict ?? zones);
  // Whether the headline is a statement about now. The dial has already
  // blanked itself if it is not, and "94 %, Normal" under a dashed dial would
  // put the two back in disagreement — so a stale figure is shown as what it
  // is: a real reading, dated, without the verdict that has expired.
  const current = isFresh(latestDate, freshDays);
  const headColor = current && zone ? zone.color : "#94a3b8";

  return (
    <div
      ref={overlayRef}
      className="fixed inset-0 z-50 flex items-center justify-center p-3.5 bg-black/60 backdrop-blur-sm"
      onClick={e => { if (e.target === overlayRef.current) onClose(); }}
    >
      <div
        role="dialog" aria-modal="true" aria-label={`${title} history`}
        className="bg-white dark:bg-slate-900 rounded-2xl border border-slate-200 dark:border-slate-700 shadow-2xl w-full max-w-3xl max-h-[90vh] overflow-y-auto p-5 space-y-4"
      >
        <div className="flex items-start justify-between gap-3">
          <div className="flex items-center gap-2">
            <h2 className="text-base font-bold text-slate-900 dark:text-white">{title}</h2>
            {info && (
              <button
                type="button"
                onClick={() => setShowInfo(s => !s)}
                aria-expanded={showInfo}
                aria-label={`What is ${title}?`}
                className={`w-5 h-5 rounded-full text-[11px] font-bold flex items-center justify-center transition-colors ${
                  showInfo
                    ? "bg-slate-700 text-white dark:bg-slate-200 dark:text-slate-900"
                    : "bg-slate-100 text-slate-500 hover:bg-slate-200 dark:bg-slate-800 dark:text-slate-400 dark:hover:bg-slate-700"
                }`}
              >
                ?
              </button>
            )}
          </div>
          <button
            onClick={onClose}
            aria-label="Close"
            className="w-7 h-7 rounded-full flex items-center justify-center text-slate-400 hover:text-slate-700 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors text-lg leading-none"
          >
            ×
          </button>
        </div>

        {showInfo && info && (
          <div className="rounded-xl bg-slate-50 dark:bg-slate-800/60 px-4 py-3 space-y-2 text-sm text-slate-600 dark:text-slate-300">
            {info.body.map((p, i) => <p key={i}>{p}</p>)}
          </div>
        )}

        {n === 0 ? (
          <p className="py-10 text-center text-sm text-slate-400">No history recorded yet.</p>
        ) : (
          <>
            {headline && (
              <div>
                <div className="flex items-baseline gap-3 flex-wrap">
                  <span className="text-3xl font-bold tabular-nums" style={{ color: headColor }}>
                    {gaugeFigure(latest, decimals)}{unit && <span className="text-lg font-semibold"> {unit}</span>}
                  </span>
                  <span className="text-sm font-medium" style={{ color: headColor }}>
                    {current ? zone?.label : `last on ${shortDate(latestDate)}`}
                  </span>
                </div>
                <ChangeLine trend={trend} decimals={decimals} unit={unit} />
              </div>
            )}

            {chart}

            {/* A chart of the metric's own brings its own legend, and a duration
                scale under a chart of something else describes the wrong picture. */}
            {strip && zones && <ScaleStrip zones={zones} value={latest} decimals={decimals} />}

            {breakdown.length > 0 && (
              <div className="border-t border-slate-100 dark:border-slate-800 pt-3">
                <p className="text-[10px] font-semibold uppercase tracking-wider text-slate-400 mb-2">What made it</p>
                <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
                  {breakdown.map(([name, v]) => (
                    <div key={name}>
                      <p className="text-xs text-slate-500 dark:text-slate-400">{name}</p>
                      <p className="text-lg font-semibold text-slate-900 dark:text-white tabular-nums">{Math.round(v)}</p>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}

/**
 * How far it has moved, and over what — against the oldest reading in the
 * window rather than yesterday. Most of these move by fractions a day, so a
 * day-on-day delta is almost always zero and says nothing.
 */
function ChangeLine({ trend, decimals, unit }) {
  const n = trend.values.length;
  if (n < 2) return null;
  const delta = trend.values[n - 1] - trend.values[0];
  const since = shortDate(trend.dates[0]);
  const amount = `${gaugeFigure(Math.abs(delta), decimals)}${unit ? ` ${unit}` : ""}`;
  // Coloured by direction only, never by good or bad: whether up is good
  // depends on the metric, and the bands above already carry that verdict.
  if (Math.abs(delta) < 0.05) {
    return <p className="mt-0.5 text-sm text-slate-400">Unchanged since {since}</p>;
  }
  return (
    <p className="mt-0.5 text-sm" style={{ color: delta > 0 ? "#22c55e" : "#f97316" }}>
      {delta > 0 ? "Up" : "Down"} {amount} since {since}
    </p>
  );
}

/**
 * The whole scale as one bar, each band as wide as it is wide — so "Average is
 * a narrow slice and High is everything above it" is visible without reading a
 * number. The band the reading falls in is lit, the rest dimmed, and a notch
 * marks the reading itself. Band names sit under their band in its own colour;
 * the boundaries are numbers, because "where does Good start" is the question
 * a colour cannot answer.
 */
export function ScaleStrip({ zones, value, decimals }) {
  if (!zones?.length) return null;
  const min = zones[0].min;
  const max = zones[zones.length - 1].max;
  const span = max - min;
  if (!(span > 0)) return null;
  const here = value != null ? zoneFor(value, zones) : null;
  const pct = v => ((v - min) / span) * 100;
  // Every boundary once, then thinned: the two ends always stay — they are
  // what the strip is measured against — and an interior one too close to a
  // kept neighbour is dropped rather than printed on top of it.
  const all = [...new Set([min, ...zones.map(z => z.max)])];
  const MIN_GAP = 7; // percent of the strip
  const edges = [all[0]];
  for (const e of all.slice(1, -1)) {
    if (pct(e) - pct(edges[edges.length - 1]) >= MIN_GAP && pct(max) - pct(e) >= MIN_GAP) edges.push(e);
  }
  if (all.length > 1) edges.push(all[all.length - 1]);

  return (
    <div className="border-t border-slate-100 dark:border-slate-800 pt-4">
      <div className="relative h-2.5">
        {zones.map(z => (
          <div
            key={z.label}
            className="absolute top-0 h-full rounded-full"
            style={{
              left: `${pct(z.min)}%`,
              width: `calc(${pct(z.max) - pct(z.min)}% - 2px)`,
              background: z.color,
              opacity: z === here ? 1 : 0.3,
            }}
          />
        ))}
        {value != null && (
          <div
            className="absolute -top-0.5 w-0.5 h-3.5 rounded-full bg-slate-900 dark:bg-white"
            style={{ left: `clamp(1px, calc(${pct(Math.max(min, Math.min(max, value)))}% - 1px), calc(100% - 2px))` }}
          />
        )}
      </div>
      <div className="relative h-4 mt-1.5 text-[10px] font-medium">
        {/* Dropped where the band is too narrow for the word: a name spilling
            across two segments points at the wrong colour, which is worse than
            no name — the colour still places it. A rough per-character width,
            since the strip is never narrower than the popup. */}
        {zones.filter(z => pct(z.max) - pct(z.min) >= z.label.length * 1.1).map(z => (
          <span
            key={z.label}
            className="absolute -translate-x-1/2 whitespace-nowrap"
            style={{ left: `${(pct(z.min) + pct(z.max)) / 2}%`, color: z.color, opacity: z === here ? 1 : 0.7 }}
          >
            {z.label}
          </span>
        ))}
      </div>
      <div className="relative h-4 text-[10px] text-slate-400 tabular-nums">
        {edges.map((e, i) => (
          <span
            key={e}
            className={`absolute whitespace-nowrap ${i === 0 ? "" : i === edges.length - 1 ? "-translate-x-full" : "-translate-x-1/2"}`}
            style={{ left: `${pct(e)}%` }}
          >
            {gaugeFigure(e, decimals)}
          </span>
        ))}
      </div>
    </div>
  );
}
