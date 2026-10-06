// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Running activity detail layout. One of the sport-specific layouts — arranges
// the shared activity building blocks (header, stat pills, pace/HR/elevation/
// cadence graphs, splits, HR histogram) into the view tuned for a run. Reads the
// parsed activity/track from props; map cursor sync is wired via useMapCursor.
// The local Component subclass is an error boundary around the heavier graphs.
import React, { useMemo, useRef, Component } from "react";
import { useMapCursor } from "../../../hooks/useMapCursor";

class ChartBoundary extends Component {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  componentDidCatch(err) { console.error("[RunningLayout] chart crashed:", err.message); }
  render() {
    if (this.state.failed)
      return <div className="card flex items-center justify-center h-24 text-xs text-slate-400">Chart unavailable</div>;
    return this.props.children;
  }
}
import { ActivityHeader } from "./ActivityHeader";
import { StatPill, StatPillsStrip } from "../pills/StatPills";
import { DurationDisplay } from "../pills/DurationDisplay";
import { HeartRateGraph } from "../graphs/HeartRateGraph";
import { PaceSpeedGraph } from "../graphs/PaceSpeedGraph";
import { CadenceGraph } from "../graphs/CadenceGraph";
import { ElevationGraph } from "../graphs/ElevationGraph";
import { HRHistogram } from "../charts/HRHistogram";
import { ActivityTable } from "../tables/ActivityTable";
import ActivityRoute from "../../ActivityRoute";
import {
  formatDistance,
  formatElevation,
  formatPace,
  fmtElapsed,
} from "../../../utils/formatUtils";

export function RunningLayout({
  activity,
  track,
  laps,
  imperial = false,
  sportType = "running",
}) {
  const routeRef = useRef(null);
  const { trackRef, onHover, onLeave } = useMapCursor(routeRef);

  if (!activity) return null;

  const gpsTrack = track?.filter((p) => p.lat && p.lng) || [];
  trackRef.current = gpsTrack;

  const avgHR    = activity.avg_heart_rate;
  const maxHR    = activity.max_heart_rate;
  const distance = activity.distance_meters;
  const elevation = activity.total_ascent;
  const calories  = activity.total_calories;
  const vo2max    = activity.vo2max_estimate;
  const tss       = activity.training_stress_score;

  // Time-series data
  const hrData = useMemo(() =>
    track?.filter(p => p.heart_rate != null).map(p => ({ elapsed: p.elapsed, hr: p.heart_rate })) || [],
    [track]);

  // PaceSpeedGraph uses raw track (needs .speed field)
  const paceSpeedData = useMemo(() => track || [], [track]);

  const cadenceData = useMemo(() =>
    track?.filter(p => p.cadence != null).map(p => ({ elapsed: p.elapsed, cadence: p.cadence })) || [],
    [track]);

  const elevData = useMemo(() =>
    track?.filter(p => p.altitude != null).map(p => ({
      elapsed: p.elapsed,
      elevation: imperial ? p.altitude * 3.28084 : p.altitude,
    })) || [],
    [track, imperial]);

  const lapTimes = useMemo(() => {
    if (!laps?.length || !activity?.started_at) return [];
    const t0 = new Date(activity.started_at).getTime();
    return laps.slice(1)
      .map(l => l.start_time ? Math.round((new Date(l.start_time).getTime() - t0) / 1000) : null)
      .filter(Boolean);
  }, [laps, activity]);

  const hrYMin = useMemo(() => {
    if (!hrData.length) return 40;
    let lo = hrData[0].hr;
    for (const p of hrData) if (p.hr < lo) lo = p.hr;
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
      formatter: v => <DurationDisplay seconds={v} format="mmss" />,
    },
    {
      key: "distance_meters", label: "Distance", align: "right",
      formatter: (_, row) => formatDistance(row.distance_meters, imperial),
    },
    {
      key: "avg_speed", label: "Pace", align: "right",
      formatter: (_, row) => formatPace(row.avg_speed, true, imperial),
    },
    {
      key: "avg_heart_rate", label: "Avg HR", align: "right",
      formatter: v => v ? `${v} bpm` : "—",
    },
    {
      key: "max_heart_rate", label: "Max HR", align: "right",
      formatter: v => v ? `${v} bpm` : "—",
    },
    {
      key: "total_ascent", label: "Ascent", align: "right",
      formatter: (_, row) => formatElevation(row.total_ascent, imperial),
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
    <div className="space-y-4">
      <ActivityHeader activity={activity} sportType={sportType} />

      <StatPillsStrip sportType={sportType}>
        <StatPill value={formatDistance(distance, imperial)} label="Distance" />
        <StatPill value={<DurationDisplay seconds={activity.duration_seconds} format="detailed" />} label="Duration" />
        <StatPill value={formatPace(activity.avg_speed, true, imperial)} label="Avg Pace" />
        {activity.max_speed > 0 && <StatPill value={formatPace(activity.max_speed, true, imperial)} label="Best Pace" />}
        {avgHR  && <StatPill value={`${avgHR} bpm`}  label="Avg HR" />}
        {maxHR  && <StatPill value={`${maxHR} bpm`}  label="Max HR" />}
        {elevation > 0 && <StatPill value={formatElevation(elevation, imperial)} label="Elevation" />}
        {calories > 0  && <StatPill value={`${calories} kcal`} label="Calories" />}
        {tss > 0       && <StatPill value={Math.round(tss)} label="TSS" />}
        {vo2max > 0    && <StatPill value={vo2max.toFixed(1)} label="VO₂ Max" />}
      </StatPillsStrip>

      {/* Two-column chart area */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 lg:items-stretch">
        {/* LEFT: HR, Pace, Cadence, Elevation */}
        <div className="flex flex-col gap-4 h-full">
          {hrData.length > 0 && (
            <ChartBoundary key="hr">
              <HeartRateGraph
                data={hrData}
                maxHR={maxHR ?? 200}
                hrYMin={hrYMin}
                onHover={onHover}
                onLeave={onLeave}
                lapTimes={lapTimes}
              />
            </ChartBoundary>
          )}

          {paceSpeedData.length > 0 && (
            <ChartBoundary key="pace">
              <PaceSpeedGraph
                data={paceSpeedData}
                imperial={imperial}
                onHover={onHover}
                onLeave={onLeave}
                lapTimes={lapTimes}
              />
            </ChartBoundary>
          )}

          {cadenceData.length > 0 && (
            <ChartBoundary key="cadence">
              <CadenceGraph data={cadenceData} onHover={onHover} onLeave={onLeave} lapTimes={lapTimes} />
            </ChartBoundary>
          )}

          {elevData.length > 0 && (
            <ChartBoundary key="elevation">
              <ElevationGraph data={elevData} imperial={imperial} onHover={onHover} onLeave={onLeave} lapTimes={lapTimes} />
            </ChartBoundary>
          )}
        </div>

        {/* RIGHT: map fills all space, HR histogram at bottom */}
        <div className="flex flex-col gap-4 h-full">
          {gpsTrack.length > 0 && (
            <div className="flex-1 min-h-[300px] rounded-xl overflow-hidden bg-slate-900">
              <ActivityRoute ref={routeRef} track={gpsTrack} height="100%" />
            </div>
          )}

          {hrHistogram.length > 0 && (
            <ChartBoundary key="histogram">
              <HRHistogram data={hrHistogram} maxHR={maxHR ?? 200} />
            </ChartBoundary>
          )}
        </div>
      </div>

      {/* Laps table — full width at bottom */}
      {lapTableData.length > 0 && (
        <div className="card">
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

export default RunningLayout;
