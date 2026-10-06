// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Hiking activity detail layout. One of the sport-specific layouts — arranges
// the shared activity building blocks (header, stat pills, elevation/HR/speed
// graphs, HR histogram) into the view tuned for a hike, emphasising elevation
// gain. Reads the parsed activity/track from props; map cursor sync is wired via
// useMapCursor.
import React, { useMemo, useRef } from "react";
import { useMapCursor } from "../../../hooks/useMapCursor";
import { ActivityHeader } from "./ActivityHeader";
import { StatPill, StatPillsStrip } from "../pills/StatPills";
import { DurationDisplay } from "../pills/DurationDisplay";
import { HeartRateGraph } from "../graphs/HeartRateGraph";
import { PaceSpeedGraph } from "../graphs/PaceSpeedGraph";
import { ElevationGraph } from "../graphs/ElevationGraph";
import { CadenceGraph } from "../graphs/CadenceGraph";
import { HRHistogram } from "../charts/HRHistogram";
import { ActivityTable } from "../tables/ActivityTable";
import ActivityRoute from "../../ActivityRoute";
import {
  formatDistance,
  formatElevation,
  formatPace,
  fmtElapsed,
} from "../../../utils/formatUtils";

export function HikingLayout({
  activity,
  track,
  laps,
  imperial = false,
  sportType = "hiking",
}) {
  const routeRef = useRef(null);
  const { trackRef, onHover, onLeave } = useMapCursor(routeRef);

  if (!activity) return null;

  const gpsTrack = track?.filter((p) => p.lat && p.lng) || [];
  trackRef.current = gpsTrack;

  const avgHR = activity.avg_heart_rate;
  const maxHR = activity.max_heart_rate;
  const distance = activity.distance_meters;
  const elevation = activity.total_ascent;
  const descent = activity.total_descent;
  const calories = activity.total_calories;
  const cadence = activity.avg_cadence;
  const tss = activity.training_stress_score;

  const hrData = useMemo(() =>
    track?.filter((p) => p.heart_rate != null).map((p) => ({ elapsed: p.elapsed, hr: p.heart_rate })) || [],
    [track]);

  const elevData = useMemo(() =>
    track?.filter((p) => p.altitude != null).map((p) => ({
      elapsed: p.elapsed,
      elevation: imperial ? p.altitude * 3.28084 : p.altitude,
    })) || [],
    [track, imperial]);

  const cadenceData = useMemo(() =>
    track?.filter((p) => p.cadence != null).map((p) => ({ elapsed: p.elapsed, cadence: p.cadence })) || [],
    [track]);

  const paceSpeedData = useMemo(() => track || [], [track]);

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
      formatter: (_, row) => formatDistance(row.distance_meters, imperial),
    },
    {
      key: "ascent", label: "Ascent", align: "right",
      formatter: (_, row) => formatElevation(row.total_ascent, imperial),
    },
    {
      key: "avg_heart_rate", label: "Avg HR", align: "right",
      formatter: (v) => (v ? `${v} bpm` : "—"),
    },
  ];

  const lapTableData = (laps || []).map((lap, i) => ({
    id: lap.id,
    number: lap.lap_number || i + 1,
    duration: lap.duration_seconds,
    distance_meters: lap.distance_meters,
    avg_speed: lap.avg_speed,
    avg_heart_rate: lap.avg_heart_rate,
    max_heart_rate: lap.max_heart_rate,
    total_ascent: lap.total_ascent,
  }));

  return (
    <div>
      <ActivityHeader activity={activity} sportType={sportType} />

      <StatPillsStrip sportType={sportType}>
        <StatPill value={formatDistance(distance, imperial)} label="Distance" />
        <StatPill value={<DurationDisplay seconds={activity.duration_seconds} format="detailed" />} label="Duration" />
        {elevation > 0 && <StatPill value={formatElevation(elevation, imperial)} label="Elevation Gain" />}
        {descent > 0 && <StatPill value={formatElevation(descent, imperial)} label="Descent" />}
        {avgHR && <StatPill value={`${avgHR} bpm`} label="Avg HR" />}
        {maxHR && <StatPill value={`${maxHR} bpm`} label="Max HR" />}
        {calories > 0 && <StatPill value={`${calories} kcal`} label="Calories" />}
        {cadence > 0 && <StatPill value={`${cadence} spm`} label="Cadence" />}
        {tss > 0 && <StatPill value={Math.round(tss)} label="TSS" />}
      </StatPillsStrip>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 mt-4 lg:items-stretch">
        {/* LEFT: HR, Pace+Speed, Elevation, Cadence */}
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

          {paceSpeedData.length > 0 && (
            <PaceSpeedGraph
              data={paceSpeedData}
              imperial={imperial}
              onHover={onHover}
              onLeave={onLeave}
              lapTimes={lapTimes}
            />
          )}

          {elevData.length > 0 && (
            <ElevationGraph data={elevData} imperial={imperial} onHover={onHover} onLeave={onLeave} lapTimes={lapTimes} />
          )}

          {cadenceData.length > 0 && (
            <CadenceGraph data={cadenceData} onHover={onHover} onLeave={onLeave} lapTimes={lapTimes} />
          )}
        </div>

        {/* RIGHT: map grows to fill, then HR histogram */}
        <div className="flex flex-col gap-4 h-full">
          {gpsTrack.length > 0 && (
            <div className="flex-1 min-h-[320px] rounded-xl overflow-hidden bg-slate-900">
              <ActivityRoute ref={routeRef} track={gpsTrack} height="100%" />
            </div>
          )}

          {hrHistogram.length > 0 && (
            <HRHistogram data={hrHistogram} maxHR={maxHR ?? 200} />
          )}
        </div>
      </div>

      {/* Laps table — full width at bottom */}
      {lapTableData.length > 0 && (
        <div className="card mt-4">
          <h3 className="section-title mb-3">
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

export default HikingLayout;
