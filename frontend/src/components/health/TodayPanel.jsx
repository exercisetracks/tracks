// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// "Today" hero for the Health page: last night's sleep alongside a strip of the
// latest vitals (HRV, resting HR, SpO₂, weight), with an inline "+ Log entry"
// that reveals the manual daily-metric logger. This is the page's at-a-glance
// morning read; the Trends block below handles history.

import { useState } from "react";
import { fmt1, todaySummary, isoToday } from "./helpers";
import { SLEEP_COLORS } from "./constants";
import { Section, Card } from "./ui";
import ManualEntryPanel from "./ManualEntryPanel";

// Up-or-down arrow coloured by whether the move is good for this metric.
function TrendArrow({ trend, good, digits = 1 }) {
  if (trend == null) return null;
  const up = trend > 0;
  const isGood = good === "up" ? up : good === "down" ? !up : null;
  const color = isGood == null ? "text-slate-400" : isGood ? "text-accent-500" : "text-red-500";
  return (
    <span className={`text-xs font-medium ${color}`}>
      {up ? "▲" : "▼"} {Math.abs(trend).toFixed(digits)}
    </span>
  );
}

function Vital({ label, value, unit, trend, good, digits }) {
  return (
    <div>
      <p className="text-xs font-medium text-slate-500 dark:text-slate-400 uppercase tracking-wide">{label}</p>
      <div className="mt-1 flex items-baseline gap-1.5 flex-wrap">
        <span className="text-2xl font-bold text-slate-900 dark:text-white">{value ?? "—"}</span>
        {unit && <span className="text-sm text-slate-500 dark:text-slate-400">{unit}</span>}
        <TrendArrow trend={trend} good={good} digits={digits} />
      </div>
    </div>
  );
}

// Horizontal stacked proportion bar of last night's sleep stages.
function StageBar({ deep, rem, light, other, total }) {
  if (!total) return null;
  const seg = (v, color, key) =>
    v > 0 ? <div key={key} style={{ width: `${(v / total) * 100}%`, background: color }} /> : null;
  return (
    <div className="mt-3 flex h-2.5 w-full overflow-hidden rounded-full bg-slate-100 dark:bg-slate-800">
      {seg(deep, SLEEP_COLORS.deep, "d")}
      {seg(rem, SLEEP_COLORS.rem, "r")}
      {seg(light, SLEEP_COLORS.light, "l")}
      {seg(other, SLEEP_COLORS.other, "o")}
    </div>
  );
}

export default function TodayPanel({ metrics, imperial, onSaved }) {
  const [logOpen, setLogOpen] = useState(false);
  const today = isoToday();

  // Strictly today's row — the panel is a snapshot of today, not the last
  // reading on record (which could be days stale if the device wasn't synced).
  const todayRow = metrics.find(m => m.date === today) ?? null;
  const sleep = todayRow?.sleep_hours != null ? todayRow : null;
  const deep = sleep?.sleep_deep_hours ?? 0;
  const rem = sleep?.sleep_rem_hours ?? 0;
  const light = sleep?.sleep_light_hours ?? 0;
  const total = sleep?.sleep_hours ?? 0;
  const hasBreakdown = deep + rem + light > 0.01;

  const hrv = todaySummary(metrics, "hrv", today);
  const rhr = todaySummary(metrics, "resting_hr", today);
  const spo2 = todaySummary(metrics, "spo2", today);
  const weight = todaySummary(metrics, "weight_kg", today, imperial ? v => v * 2.20462 : undefined);

  return (
    <Section
      title="Today"
      action={
        <button
          onClick={() => setLogOpen(o => !o)}
          data-tour="health-log"
          className="btn btn-primary btn-sm"
        >
          {logOpen ? "Close" : "+ Log entry"}
        </button>
      }
    >
      <Card>
        <div className="grid grid-cols-1 md:grid-cols-[minmax(180px,240px)_1fr] gap-6">
          {/* Last night's sleep */}
          <div>
            <p className="text-xs font-medium text-slate-500 dark:text-slate-400 uppercase tracking-wide">Last night</p>
            {sleep ? (
              <>
                <div className="mt-1 flex items-baseline gap-1.5">
                  <span className="text-3xl font-bold text-slate-900 dark:text-white">{fmt1(total)}</span>
                  <span className="text-sm text-slate-500 dark:text-slate-400">h asleep</span>
                  {sleep.sleep_score != null && (
                    <span className="text-xs text-slate-400 dark:text-slate-500">· score {Math.round(sleep.sleep_score)}</span>
                  )}
                </div>
                <StageBar
                  deep={deep} rem={rem} light={light}
                  other={hasBreakdown ? 0 : total}
                  total={total}
                />
                {hasBreakdown && (
                  <div className="mt-2 flex gap-3 text-xs">
                    <span style={{ color: SLEEP_COLORS.deep }}>Deep {fmt1(deep)}h</span>
                    <span style={{ color: SLEEP_COLORS.rem }}>REM {fmt1(rem)}h</span>
                    <span style={{ color: SLEEP_COLORS.light }}>Light {fmt1(light)}h</span>
                  </div>
                )}
              </>
            ) : (
              <p className="mt-2 text-sm text-slate-400 dark:text-slate-500">No sleep data for today</p>
            )}
          </div>

          {/* Today's vitals */}
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-4 md:border-l md:border-slate-100 dark:md:border-slate-800 md:pl-5">
            <Vital label="HRV" value={hrv.value != null ? fmt1(hrv.value) : null} unit="ms" trend={hrv.trend} good="up" digits={1} />
            <Vital label="Resting HR" value={rhr.value != null ? Math.round(rhr.value) : null} unit="bpm" trend={rhr.trend} good="down" digits={0} />
            <Vital label="SpO₂" value={spo2.value != null ? Math.round(spo2.value) : null} unit="%" trend={spo2.trend} good="up" digits={0} />
            <Vital label="Weight" value={weight.value != null ? weight.value.toFixed(1) : null} unit={imperial ? "lbs" : "kg"} trend={weight.trend} good={null} digits={1} />
          </div>
        </div>

        {logOpen && (
          <div className="mt-5 pt-4 border-t border-slate-100 dark:border-slate-800">
            <ManualEntryPanel
              today={isoToday()}
              imperial={imperial}
              onSaved={() => { onSaved?.(); }}
            />
          </div>
        )}
      </Card>
    </Section>
  );
}
