// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Swimming activity detail layout. One of the sport-specific layouts — arranges
// the shared activity building blocks (header, stat pills, pace/HR graphs, HR
// histogram) into the view tuned for a swim, emphasising laps/pace. Reads the
// parsed activity/track from props; map cursor sync (for open-water swims) is
// wired via useMapCursor.
import React, { useMemo, useRef } from "react";
import { useMapCursor } from "../../../hooks/useMapCursor";
import { ActivityHeader } from "./ActivityHeader";
import { StatPill, StatPillsStrip } from "../pills/StatPills";
import { DurationDisplay } from "../pills/DurationDisplay";
import { HeartRateGraph } from "../graphs/HeartRateGraph";
import { SpeedGraph } from "../graphs/SpeedGraph";
import { HRHistogram } from "../charts/HRHistogram";
import { ActivityTable } from "../tables/ActivityTable";
import ActivityRoute from "../../ActivityRoute";
import {
  formatDistance,
  fmtElapsed,
} from "../../../utils/formatUtils";

function formatPace100(speed_ms, imperial) {
  if (!speed_ms || speed_ms <= 0) return "—";
  const unitDist = imperial ? 91.44 : 100; // 100yd in metres ≈ 91.44
  const minPer100 = unitDist / (speed_ms * 60);
  const mins = Math.floor(minPer100);
  const secs = Math.round((minPer100 - mins) * 60);
  const label = imperial ? "min/100yd" : "min/100m";
  return `${mins}:${String(secs).padStart(2, "0")} ${label}`;
}

export function SwimmingLayout({
  activity,
  track,
  laps,
  imperial = false,
  sportType = "swimming",
}) {
  const routeRef = useRef(null);
  const { trackRef, onHover, onLeave } = useMapCursor(routeRef);

  if (!activity) return null;

  const gpsTrack = track?.filter((p) => p.lat && p.lng) || [];
  trackRef.current = gpsTrack;
  const hasGPS = gpsTrack.length > 0;

  const avgHR = activity.avg_heart_rate;
  const maxHR = activity.max_heart_rate;
  const distance = activity.distance_meters;
  const calories = activity.total_calories;
  const strokeRate = activity.avg_cadence;
  const lapCount = activity.lap_count ?? laps?.length ?? 0;

  const hrData = useMemo(() =>
    track?.filter((p) => p.heart_rate != null).map((p) => ({ elapsed: p.elapsed, hr: p.heart_rate })) || [],
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
      key: "avg_heart_rate", label: "Avg HR", align: "right",
      formatter: (v) => (v ? `${v} bpm` : "—"),
    },
    {
      key: "pace", label: imperial ? "Pace/100yd" : "Pace/100m", align: "right",
      formatter: (_, row) => formatPace100(row.avg_speed, imperial),
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
  }));

  // Graphs shared between both layout modes
  const graphsLeft = (
    <>
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

      {speedData.length > 0 && (
        <SpeedGraph data={speedData} imperial={imperial} onHover={onHover} onLeave={onLeave} lapTimes={lapTimes} />
      )}

    </>
  );

  const chartsRight = (
    <>
      {hrHistogram.length > 0 && (
        <HRHistogram data={hrHistogram} maxHR={maxHR ?? 200} />
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
    </>
  );

  return (
    <div>
      <ActivityHeader activity={activity} sportType={sportType} />

      <StatPillsStrip sportType={sportType}>
        <StatPill value={formatDistance(distance, imperial)} label="Distance" />
        <StatPill value={<DurationDisplay seconds={activity.duration_seconds} format="detailed" />} label="Duration" />
        {avgHR && <StatPill value={`${avgHR} bpm`} label="Avg HR" />}
        {maxHR && <StatPill value={`${maxHR} bpm`} label="Max HR" />}
        {calories > 0 && <StatPill value={`${calories} kcal`} label="Calories" />}
        {strokeRate > 0 && <StatPill value={`${strokeRate} spm`} label="Stroke Rate" />}
        {lapCount > 0 && <StatPill value={lapCount} label="Laps" />}
      </StatPillsStrip>

      {hasGPS ? (
        // Open-water: two-column with map on the right
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 mt-4">
          <div className="space-y-4">
            {graphsLeft}
          </div>

          <div className="space-y-4">
            <div className="rounded-xl overflow-hidden bg-slate-900" style={{ height: 400 }}>
              <ActivityRoute ref={routeRef} track={gpsTrack} height={400} />
            </div>

            {chartsRight}
          </div>
        </div>
      ) : (
        // Pool: two-column without map — HR/speed/zones left, histogram/table right
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 mt-4">
          <div className="space-y-4">
            {graphsLeft}
          </div>

          <div className="space-y-4">
            {chartsRight}
          </div>
        </div>
      )}
    </div>
  );
}

export default SwimmingLayout;
