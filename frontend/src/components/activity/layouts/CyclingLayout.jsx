// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Cycling activity detail layout. One of the sport-specific layouts (siblings:
// Climbing/Bouldering/Strength/Rowing/Paddling) — arranges the shared activity
// building blocks (header, stat pills, HR/speed/elevation/power/cadence graphs,
// grit-flow + HR histogram) into the view tuned for a ride. Reads the parsed
// activity/track from props; the map cursor sync is wired via useMapCursor.
import React, { useMemo, useRef } from "react";
import { useMapCursor } from "../../../hooks/useMapCursor";
import { ActivityHeader } from "./ActivityHeader";
import { StatPill, StatPillsStrip } from "../pills/StatPills";
import { DurationDisplay } from "../pills/DurationDisplay";
import { HeartRateGraph } from "../graphs/HeartRateGraph";
import { SpeedGraph } from "../graphs/SpeedGraph";
import { ElevationGraph } from "../graphs/ElevationGraph";
import { PowerGraph } from "../graphs/PowerGraph";
import { CadenceGraph } from "../graphs/CadenceGraph";
import { GritFlowGraph } from "../graphs/GritFlowGraph";
import { HRHistogram } from "../charts/HRHistogram";
import { EffortZoneChart } from "../charts/EffortZoneChart";
import { ActivityTable } from "../tables/ActivityTable";
import ActivityRoute from "../../ActivityRoute";
import {
  formatDistance,
  formatElevation,
  formatSpeed,
} from "../../../utils/formatUtils";

export function CyclingLayout({
  activity,
  track,
  laps,
  imperial = false,
  sportType = "cycling",
}) {
  const routeRef = useRef(null);
  const { trackRef, onHover, onLeave } = useMapCursor(routeRef);

  if (!activity) return null;

  const gpsTrack = track?.filter((p) => p.lat && p.lng) || [];
  trackRef.current = gpsTrack;
  const isMTB = sportType === "mtb" || /mountain|mtb/i.test(activity?.sub_sport ?? "");
  const effectiveSportType = isMTB ? "mtb" : sportType;

  const avgSpeed = activity.avg_speed;
  const maxSpeed = activity.max_speed;
  const avgPower = activity.avg_power;
  const normPower = activity.normalized_power;
  const avgHR = activity.avg_heart_rate;
  const maxHR = activity.max_heart_rate;
  const distance = activity.distance_meters;
  const elevation = activity.total_ascent;
  const tss = activity.training_stress_score;
  const totalGrit = activity.total_grit;
  const avgFlow = activity.avg_flow;
  const calories = activity.total_calories;
  const vo2max = activity.vo2max_estimate;

  const hrData = useMemo(() =>
    track?.filter((p) => p.heart_rate != null).map((p) => ({ elapsed: p.elapsed, hr: p.heart_rate })) || [],
    [track]);

  const speedData = useMemo(() =>
    track?.filter((p) => p.speed != null).map((p) => ({
      elapsed: p.elapsed,
      speed: imperial ? p.speed * 2.23694 : p.speed * 3.6,
    })) || [],
    [track, imperial]);

  const powerData = useMemo(() =>
    track?.filter((p) => p.power != null && p.power > 0).map((p) => ({ elapsed: p.elapsed, power: p.power })) || [],
    [track]);

  const cadenceData = useMemo(() =>
    track?.filter((p) => p.cadence != null).map((p) => ({ elapsed: p.elapsed, cadence: p.cadence })) || [],
    [track]);

  const elevData = useMemo(() =>
    track?.filter((p) => p.altitude != null).map((p) => ({
      elapsed: p.elapsed,
      elevation: imperial ? p.altitude * 3.28084 : p.altitude,
    })) || [],
    [track, imperial]);

  const gritFlowData = useMemo(() => {
    if (!isMTB || !track?.length) return [];
    // Raw grit/flow from Garmin alternates between real values and zero between
    // descents (it's only computed while descending). The raw signal is unusable
    // — treat zeros as "no measurement" and apply a centered moving average so
    // the graph reads as a continuous trend over the ride.
    const raw = track
      .filter((p) => p.grit != null || p.flow != null)
      .map((p) => ({
        elapsed: p.elapsed,
        grit: p.grit && p.grit > 0 ? p.grit : null,
        flow: p.flow && p.flow > 0 ? p.flow : null,
      }));
    if (!raw.length) return [];

    const WINDOW = 15; // ±15 samples ≈ 30s window at 1Hz
    const smooth = (key) => {
      const out = new Array(raw.length).fill(null);
      for (let i = 0; i < raw.length; i++) {
        let sum = 0, n = 0;
        const lo = Math.max(0, i - WINDOW);
        const hi = Math.min(raw.length - 1, i + WINDOW);
        for (let j = lo; j <= hi; j++) {
          const v = raw[j][key];
          if (v != null) { sum += v; n++; }
        }
        if (n > 0) out[i] = sum / n;
      }
      return out;
    };
    const gritS = smooth("grit");
    const flowS = smooth("flow");
    return raw.map((p, i) => ({ elapsed: p.elapsed, grit: gritS[i], flow: flowS[i] }));
  }, [track, isMTB]);

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

  const timelineSplits = useMemo(() => {
    if (!laps?.length) {
      if (!track?.length) return [];
      return [{ duration_seconds: activity.duration_seconds, split_type: "active", max_heart_rate: avgHR }];
    }
    return laps.map((lap) => ({
      duration_seconds: lap.duration_seconds,
      split_type: "active",
      max_heart_rate: lap.max_heart_rate,
    }));
  }, [laps, track, activity, avgHR]);

  const lapColumns = [
    { key: "number", label: "Lap", align: "left" },
    {
      key: "duration", label: "Time", align: "right",
      formatter: (v) => <DurationDisplay seconds={v} format="mmss" />,
    },
    {
      key: "distance", label: "Distance", align: "right",
      formatter: (v, row) => formatDistance(row.distance_meters, imperial),
    },
    {
      key: "speed", label: "Avg Speed", align: "right",
      formatter: (_, row) => formatSpeed(row.avg_speed, imperial),
    },
    {
      key: "power", label: "Power", align: "right",
      formatter: (_, row) => (row.avg_power ? `${row.avg_power} W` : "—"),
    },
    {
      key: "avg_heart_rate", label: "Avg HR", align: "right",
      formatter: (v) => (v ? `${v} bpm` : "—"),
    },
    {
      key: "max_heart_rate", label: "Max HR", align: "right",
      formatter: (v) => (v ? `${v} bpm` : "—"),
    },
    {
      key: "ascent", label: "Ascent", align: "right",
      formatter: (_, row) => formatElevation(row.total_ascent, imperial),
    },
  ];

  const lapTableData = (laps || []).map((lap, i) => ({
    id: lap.id,
    number: lap.lap_number || i + 1,
    duration: lap.duration_seconds,
    distance_meters: lap.distance_meters,
    avg_speed: lap.avg_speed,
    avg_power: lap.avg_power,
    avg_heart_rate: lap.avg_heart_rate,
    max_heart_rate: lap.max_heart_rate,
    total_ascent: lap.total_ascent,
  }));

  return (
    <div>
      <ActivityHeader activity={activity} sportType={effectiveSportType} />

      <StatPillsStrip sportType={effectiveSportType}>
        <StatPill value={formatDistance(distance, imperial)} label="Distance" />
        <StatPill value={<DurationDisplay seconds={activity.duration_seconds} format="detailed" />} label="Duration" />
        <StatPill value={formatSpeed(avgSpeed, imperial)} label="Avg Speed" />
        {maxSpeed > 0 && <StatPill value={formatSpeed(maxSpeed, imperial)} label="Max Speed" />}
        {avgPower && <StatPill value={`${avgPower} W`} label="Avg Power" />}
        {normPower && <StatPill value={`${normPower} W`} label="Norm Power" />}
        {avgHR && <StatPill value={`${avgHR} bpm`} label="Avg HR" />}
        {maxHR && <StatPill value={`${maxHR} bpm`} label="Max HR" />}
        {elevation > 0 && <StatPill value={formatElevation(elevation, imperial)} label="Elevation" />}
        {tss > 0 && <StatPill value={Math.round(tss)} label="TSS" />}
        {totalGrit > 0 && <StatPill value={totalGrit.toFixed(1)} label="Grit" />}
        {avgFlow > 0 && <StatPill value={avgFlow.toFixed(1)} label="Flow" />}
        {calories > 0 && <StatPill value={`${calories} kcal`} label="Calories" />}
        {vo2max > 0 && <StatPill value={vo2max.toFixed(1)} label={"VO\u2082 Max"} />}
      </StatPillsStrip>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 mt-4 lg:items-stretch">
        {/* LEFT: time-series graphs */}
        <div className="flex flex-col gap-4 h-full">
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

          {cadenceData.length > 0 && (
            <CadenceGraph data={cadenceData} onHover={onHover} onLeave={onLeave} lapTimes={lapTimes} />
          )}

          {speedData.length > 0 && (
            <SpeedGraph data={speedData} imperial={imperial} label="Speed" onHover={onHover} onLeave={onLeave} lapTimes={lapTimes} />
          )}

          {elevData.length > 0 && (
            <ElevationGraph data={elevData} imperial={imperial} onHover={onHover} onLeave={onLeave} lapTimes={lapTimes} />
          )}

          {powerData.length > 0 && (
            <PowerGraph data={powerData} onHover={onHover} onLeave={onLeave} lapTimes={lapTimes} />
          )}

          {isMTB && gritFlowData.length > 0 && (
            <GritFlowGraph data={gritFlowData} onHover={onHover} onLeave={onLeave} lapTimes={lapTimes} />
          )}
        </div>

        {/* RIGHT: map grows to fill, then analytics */}
        <div className="flex flex-col gap-4 h-full">
          {gpsTrack.length > 0 && (
            <div className="flex-1 min-h-[320px] rounded-xl overflow-hidden bg-slate-900">
              <ActivityRoute ref={routeRef} track={gpsTrack} height="100%" />
            </div>
          )}

          {hrHistogram.length > 0 && (
            <HRHistogram data={hrHistogram} maxHR={maxHR ?? 200} />
          )}

          {powerData.length > 0 && (
            <EffortZoneChart data={powerData} sportType="cycling" ftp={normPower ?? avgPower ?? 250} />
          )}
        </div>
      </div>

      {/* Laps table — full width at bottom */}
      {lapTableData.length > 0 && (
        <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 p-3.5 mt-4">
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
  );
}

export default CyclingLayout;
