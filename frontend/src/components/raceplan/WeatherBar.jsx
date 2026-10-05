// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Race-day conditions widget: a live-vs-historical source badge, the raw
// temp/humidity/wind readout, and a breakdown of the heat/wind time penalties
// applied to the prediction. Driven entirely by the `weather` snapshot prop.

import { fmtAdjSec } from "./format";

export default function WeatherBar({ weather }) {
  if (!weather) return null;
  const temp    = weather.temperature_c != null ? `${weather.temperature_c.toFixed(1)}°C` : "—";
  const hum     = weather.humidity_pct  != null ? `${Math.round(weather.humidity_pct)}%` : "—";
  const wind    = weather.wind_mps      != null ? `${weather.wind_mps.toFixed(1)} m/s` : "—";
  const isHot   = (weather.temperature_c ?? 0) > 20;
  const isHumid = (weather.humidity_pct  ?? 0) > 70;
  const isHist  = weather.source === "historical_avg";

  const windNote  = weather.wind_course_note;    // "Mostly headwind" etc. — only when GPX loaded
  const heatSec   = weather.heat_added_sec;
  const windSec   = weather.wind_added_sec;
  const heatPct   = weather.heat_penalty_pct ?? 0;
  const windPct   = weather.wind_penalty_pct ?? 0;
  const totalPct  = Math.round((heatPct + windPct) * 10) / 10;
  const hasAdj    = totalPct > 0.05 || windPct < -0.05;

  return (
    <div className="space-y-3">
      {/* Source badge */}
      <div className="flex items-center gap-2 flex-wrap">
        {isHist ? (
          <span className="inline-flex items-center gap-1.5 text-xs font-medium px-2 py-1 rounded-full bg-amber-50 dark:bg-amber-900/20 text-amber-700 dark:text-amber-400">
            <svg className="w-3 h-3" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
            Historical average · {weather.years_sampled ?? "?"}-year avg for this date
          </span>
        ) : (
          <span className="inline-flex items-center gap-1.5 text-xs font-medium px-2 py-1 rounded-full bg-accent-50 dark:bg-accent-900/20 text-accent-700 dark:text-accent-400">
            <svg className="w-3 h-3" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
            Live forecast · Open-Meteo
          </span>
        )}
      </div>

      <p className="text-[10px] text-slate-400 dark:text-slate-500">
        Data fetched from Open-Meteo (free, no API key). Your race location coordinates
        are transmitted. Disable in Settings &rarr; Privacy &amp; Connectivity.
      </p>

      {/* Raw conditions */}
      <div className="flex flex-wrap items-center gap-4 text-sm">
        <span className={isHot ? "text-orange-600 dark:text-orange-400" : "text-slate-600 dark:text-slate-400"}>
          Temp {temp}
        </span>
        <span className={isHumid ? "text-blue-600 dark:text-blue-400" : "text-slate-600 dark:text-slate-400"}>
          Humidity {hum}
        </span>
        <span className="text-slate-600 dark:text-slate-400">
          Wind {wind}{windNote ? ` · ${windNote}` : ""}
        </span>
      </div>

      {/* Adjustment breakdown */}
      {hasAdj && (
        <div className="rounded-lg bg-slate-50 dark:bg-slate-800/60 px-2.5 py-1.5 space-y-1">
          <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wide mb-1.5">
            Time adjustments
          </p>
          {heatPct > 0.05 && (
            <div className="flex items-center justify-between text-xs">
              <span className="text-slate-600 dark:text-slate-400">
                Heat{isHumid ? " + humidity" : ""}
              </span>
              <span className="font-mono font-semibold text-orange-600 dark:text-orange-400">
                +{heatPct.toFixed(1)}%
                {fmtAdjSec(heatSec) ? <span className="ml-1.5 font-normal text-slate-400">({fmtAdjSec(heatSec)})</span> : null}
              </span>
            </div>
          )}
          {Math.abs(windPct) > 0.05 && (
            <div className="flex items-center justify-between text-xs">
              <span className="text-slate-600 dark:text-slate-400">
                Wind{windNote ? ` (${windNote.toLowerCase()})` : ""}
              </span>
              <span className={`font-mono font-semibold ${windPct > 0 ? "text-orange-600 dark:text-orange-400" : "text-accent-600 dark:text-accent-400"}`}>
                {windPct > 0 ? "+" : ""}{windPct.toFixed(1)}%
                {fmtAdjSec(windSec) ? <span className="ml-1.5 font-normal text-slate-400">({fmtAdjSec(windSec)})</span> : null}
              </span>
            </div>
          )}
          {totalPct > 0.1 && (
            <div className="flex items-center justify-between text-xs border-t border-slate-200 dark:border-slate-700 pt-1 mt-1">
              <span className="text-slate-500 dark:text-slate-400 font-medium">Total</span>
              <span className="font-mono font-semibold text-slate-700 dark:text-slate-300">
                +{totalPct.toFixed(1)}% vs ideal conditions
              </span>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
