// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Indoor cycling / trainer activity detail layout. One of the sport-specific
// layouts — like CyclingLayout but with no GPS map (there's no route indoors),
// so it centres on the power/HR/cadence graphs and stat pills. Reads the parsed
// activity from props.
import React, { useMemo, useCallback } from "react";
import { ActivityHeader } from "./ActivityHeader";
import { StatPill, StatPillsStrip } from "../pills/StatPills";
import { DurationDisplay } from "../pills/DurationDisplay";
import { HeartRateGraph } from "../graphs/HeartRateGraph";
import { SpeedGraph } from "../graphs/SpeedGraph";
import { PowerGraph } from "../graphs/PowerGraph";
import { CadenceGraph } from "../graphs/CadenceGraph";
import { HRHistogram } from "../charts/HRHistogram";
import { EffortZoneChart } from "../charts/EffortZoneChart";
import { ActivityTable } from "../tables/ActivityTable";
import {
  formatDistance,
  formatSpeed,
} from "../../../utils/formatUtils";

export function IndoorCyclingLayout({
  activity,
  track,
  laps,
  imperial = false,
  sportType = "indoor_cycling",
}) {
  const onHover = useCallback(() => {}, []);
  const onLeave = useCallback(() => {}, []);

  if (!activity) return null;

  const avgPower = activity.avg_power;
  const normPower = activity.normalized_power;
  const avgHR = activity.avg_heart_rate;
  const maxHR = activity.max_heart_rate;
  const avgCadence = activity.avg_cadence;
  const calories = activity.total_calories;
  const tss = activity.training_stress_score;
  const vo2max = activity.vo2max_estimate;
  const avgSpeed = activity.avg_speed;

  const hrData = useMemo(() =>
    track?.filter((p) => p.heart_rate != null).map((p) => ({ elapsed: p.elapsed, hr: p.heart_rate })) || [],
    [track]);

  const powerData = useMemo(() =>
    track?.filter((p) => p.power != null && p.power > 0).map((p) => ({ elapsed: p.elapsed, power: p.power })) || [],
    [track]);

  const cadenceData = useMemo(() =>
    track?.filter((p) => p.cadence != null).map((p) => ({ elapsed: p.elapsed, cadence: p.cadence })) || [],
    [track]);

  const speedData = useMemo(() =>
    track?.filter((p) => p.speed != null).map((p) => ({
      elapsed: p.elapsed,
      speed: imperial ? p.speed * 2.23694 : p.speed * 3.6,
    })) || [],
    [track, imperial]);

  const lapTimes = useMemo(() => {
    if (!laps?.length || !activity?.started_at) return [];
    const t0 = new Date(activity.started_at).getTime();
    return laps.slice(1)
      .map((l) => (l.start_time ? Math.round((new Date(l.start_time).getTime() - t0) / 1000) : null))
      .filter(Boolean);
  }, [laps, activity]);

  const hrYMin = useMemo(() => {
    if (!hrData.length) return 40;
    let lo = hrData[0].hr;
    for (const p of hrData) { if (p.hr < lo) lo = p.hr; }
    return Math.max(0, Math.floor((lo - 10) / 5) * 5);
  }, [hrData]);

  const hrHistogram = useMemo(() => {
    if (!track?.length) return [];
    let lo = Infinity, hi = -Infinity;
    for (const p of track) {
      if (p.heart_rate == null) continue;
      if (p.heart_rate < lo) lo = p.heart_rate;
      if (p.heart_rate > hi) hi = p.heart_rate;
    }
    if (!isFinite(lo)) return [];
    const mn = Math.floor(lo / 2) * 2, mx = Math.ceil(hi / 2) * 2;
    const bins = [];
    for (let h = mn; h < mx; h += 2) bins.push({ range: `${h}–${h + 2}`, hr: h, count: 0 });
    for (const p of track) {
      if (p.heart_rate == null || p.heart_rate < mn || p.heart_rate >= mx) continue;
      bins[Math.floor((p.heart_rate - mn) / 2)].count++;
    }
    return bins;
  }, [track]);

  const ftp = normPower ?? avgPower ?? 250;

  const lapColumns = [
    { key: "number", label: "Lap", align: "left" },
    {
      key: "duration", label: "Time", align: "right",
      formatter: (v) => <DurationDisplay seconds={v} format="mmss" />,
    },
    {
      key: "avg_power", label: "Avg Power", align: "right",
      formatter: (v) => (v ? `${v} W` : "—"),
    },
    {
      key: "avg_heart_rate", label: "Avg HR", align: "right",
      formatter: (v) => (v ? `${v} bpm` : "—"),
    },
    {
      key: "avg_cadence", label: "Cadence", align: "right",
      formatter: (v) => (v ? `${v} rpm` : "—"),
    },
  ];

  const lapTableData = (laps || []).map((lap, i) => ({
    id: lap.id,
    number: lap.lap_number || i + 1,
    duration: lap.duration_seconds,
    avg_power: lap.avg_power,
    avg_heart_rate: lap.avg_heart_rate,
    max_heart_rate: lap.max_heart_rate,
    avg_cadence: lap.avg_cadence,
  }));

  return (
    <div>
      <ActivityHeader activity={activity} sportType={sportType} />

      <StatPillsStrip sportType={sportType}>
        <StatPill value={<DurationDisplay seconds={activity.duration_seconds} format="detailed" />} label="Duration" />
        {avgPower > 0 && <StatPill value={`${avgPower} W`} label="Avg Power" />}
        {normPower > 0 && <StatPill value={`${normPower} W`} label="Norm Power" />}
        {avgHR && <StatPill value={`${avgHR} bpm`} label="Avg HR" />}
        {maxHR && <StatPill value={`${maxHR} bpm`} label="Max HR" />}
        {avgCadence > 0 && <StatPill value={`${avgCadence} rpm`} label="Cadence" />}
        {calories > 0 && <StatPill value={`${calories} kcal`} label="Calories" />}
        {tss > 0 && <StatPill value={Math.round(tss)} label="TSS" />}
        {vo2max > 0 && <StatPill value={vo2max.toFixed(1)} label="VO₂ Max" />}
      </StatPillsStrip>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 mt-4">
        <div className="space-y-4">
          {hrData.length > 0 && (
            <HeartRateGraph
              data={hrData}
              maxHR={maxHR ?? 200}
              hrYMin={hrYMin}
              onHover={onHover}
              onLeave={onLeave}
              lapTimes={lapTimes}
            />
          )}

          {powerData.length > 0 && (
            <PowerGraph
              data={powerData}
              onHover={onHover}
              onLeave={onLeave}
              lapTimes={lapTimes}
            />
          )}

          {cadenceData.length > 0 && (
            <CadenceGraph
              data={cadenceData}
              onHover={onHover}
              onLeave={onLeave}
              lapTimes={lapTimes}
            />
          )}

          {speedData.length > 0 && (
            <SpeedGraph
              data={speedData}
              imperial={imperial}
              label="Virtual Speed"
              onHover={onHover}
              onLeave={onLeave}
              lapTimes={lapTimes}
            />
          )}
        </div>

        <div className="space-y-4">
          {hrHistogram.length > 0 && (
            <HRHistogram data={hrHistogram} maxHR={maxHR ?? 200} />
          )}

          {powerData.length > 0 && (
            <EffortZoneChart
              data={powerData}
              sportType="cycling"
              ftp={ftp}
            />
          )}

          {lapTableData.length > 0 && (
            <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 p-3.5">
              <h3 className="text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider mb-3">
                Lap Summary
              </h3>
              <ActivityTable
                columns={lapColumns}
                data={lapTableData}
                emptyMessage="No lap data available"
              />
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

export default IndoorCyclingLayout;
