// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * ClimbingLayout — detail view for a single route-climbing activity.
 *
 * Renders the header, a strip of summary stat pills, an active/rest timeline,
 * a two-column analytics grid (effort/grade/ascent charts + HR graph on the
 * left; GPS map + HR/rest distributions on the right), and a full-width
 * "Climb Log" table.
 *
 * This file deliberately keeps most of its data-derivation logic inline: the
 * stat pills and memoized histograms are tightly coupled to ~15 locally
 * derived values, so pulling them out would create prop-drilling with no
 * readability win. Only genuinely low-coupling pieces are extracted:
 *   - climbing/AscentVertSpeedChart.jsx — the standalone ComposedChart block.
 *   - climbing/climbLogColumns.jsx      — the pure table-column builder.
 * Both are Climbing-specific, hence under climbing/ (not shared/).
 *
 * Sibling layouts (BoulderingLayout, StrengthLayout, …) mirror this structure
 * for their own sports; shared presentational atoms already live under
 * ../pills, ../cards, ../charts, ../graphs and ../tables.
 */
import React, { useMemo, useRef } from "react";
import { useMapCursor } from "../../../hooks/useMapCursor";
import { ActivityHeader } from "./ActivityHeader";
import { ActivityTimeline } from "../timelines/ActivityTimeline";
import { StatPill, StatPillsStrip } from "../pills/StatPills";
import { DurationDisplay } from "../pills/DurationDisplay";
import { GradeDisplay } from "../pills/GradeDisplay";
import { ActivityTable } from "../tables/ActivityTable";
import { ClimbEffortChart } from "../charts/ClimbEffortChart";
import { ClimbGradeHistogram } from "../charts/ClimbGradeHistogram";
import { RestHistogram } from "../charts/RestHistogram";
import { HRHistogram } from "../charts/HRHistogram";
import { HeartRateGraph } from "../graphs/HeartRateGraph";
import ActivityRoute from "../../ActivityRoute";
import { formatElevation } from "../../../utils/formatUtils";
// Climbing-specific extractions (see header comment)
import { AscentVertSpeedChart } from "./climbing/AscentVertSpeedChart";
import { buildClimbLogColumns } from "./climbing/climbLogColumns";

export function ClimbingLayout({
  activity,
  climbs,
  track,
  imperial = false,
  sportType = "climbing",
  onClimbClick,
}) {
  const routeRef = useRef(null);
  const { trackRef, onHover, onLeave } = useMapCursor(routeRef);

  if (!climbs?.length) return null;

  const gpsTrack = track?.filter((p) => p.lat && p.lng) || [];
  trackRef.current = gpsTrack;

  // Splits are the raw watch laps; climbing sessions alternate active climbs
  // with rest periods. Everything below is derived from these two subsets.
  const activeSplits = climbs.filter((c) => c.split_type === "climb_active");
  const restSplits = climbs.filter((c) => c.split_type === "climb_rest");

  // Availability flags — drive which stat pills, chart, and table columns show.
  const hasAscent = activeSplits.some((c) => (c.total_ascent ?? 0) > 0);
  const hasGrades = activeSplits.some((c) => c.grade_level != null);
  const hasResults = activeSplits.some((c) => c.climb_result != null);
  const hasVertSpeed = activeSplits.some((c) => (c.avg_vert_speed ?? 0) > 0);
  const hasCalories = activeSplits.some((c) => (c.total_calories ?? 0) > 0);

  const totalAscent = activeSplits.reduce((s, c) => s + (c.total_ascent ?? 0), 0);
  const totalActiveTime = activeSplits.reduce((s, c) => s + (c.duration_seconds ?? 0), 0);
  const totalRestTime = restSplits.reduce((s, c) => s + (c.duration_seconds ?? 0), 0);
  const avgRestTime = restSplits.length ? totalRestTime / restSplits.length : null;
  const totalDuration = activity?.duration_seconds ?? 0;
  const sends = activeSplits.filter((c) => c.climb_result === 3).length;
  const attempts = activeSplits.length - sends;
  const sendRate = hasResults && activeSplits.length > 0
    ? (sends / activeSplits.length) * 100
    : null;
  const activeRatio = totalDuration > 0 ? (totalActiveTime / totalDuration) * 100 : null;

  const maxClimbHR = Math.max(0, ...activeSplits.map((c) => c.max_heart_rate ?? 0));
  const avgClimbHR = (() => {
    const hrs = activeSplits.map((c) => c.max_heart_rate).filter((v) => v != null && v > 0);
    return hrs.length ? Math.round(hrs.reduce((a, b) => a + b, 0) / hrs.length) : null;
  })();
  const bestVertSpeed = Math.max(0, ...activeSplits.map((c) => c.avg_vert_speed ?? 0));
  const totalClimbCalories = activeSplits.reduce((s, c) => s + (c.total_calories ?? 0), 0);

  // Hardest send = max grade among sent routes; hardest tried = max grade
  // attempted (shown separately only when it differs from the hardest send).
  const hardestSend = hasGrades && hasResults
    ? Math.max(-1, ...activeSplits.filter((c) => c.climb_result === 3).map((c) => c.grade_level ?? -1))
    : -1;
  const hardestSendDisplay = hardestSend >= 0 ? hardestSend : null;
  const hardestTried = hasGrades
    ? Math.max(0, ...activeSplits.map((c) => c.grade_level ?? 0))
    : null;

  const hrData = useMemo(
    () =>
      track?.filter((p) => p.heart_rate != null).map((p) => ({
        elapsed: p.elapsed,
        hr: p.heart_rate,
      })) || [],
    [track]
  );

  const hrYMin = useMemo(() => {
    if (!hrData.length) return 40;
    let lo = hrData[0].hr;
    for (const p of hrData) if (p.hr < lo) lo = p.hr;
    return Math.max(0, Math.floor((lo - 10) / 5) * 5);
  }, [hrData]);

  const maxHR = activity?.max_heart_rate ?? 200;

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

  const restHistogramData = useMemo(() => {
    const restTimes = restSplits
      .map((r) => r.duration_seconds)
      .filter((v) => v != null && v > 0);
    if (restTimes.length === 0) return [];
    const bins = [
      { range: "0–1m", min: 0, max: 60, count: 0 },
      { range: "1–2m", min: 60, max: 120, count: 0 },
      { range: "2–5m", min: 120, max: 300, count: 0 },
      { range: "5–10m", min: 300, max: 600, count: 0 },
      { range: "10m+", min: 600, max: Infinity, count: 0 },
    ];
    for (const r of restTimes) {
      for (const b of bins) {
        if (r >= b.min && r < b.max) {
          b.count++;
          break;
        }
      }
    }
    return bins.filter((b) => b.count > 0);
  }, [restSplits]);

  // Per-route rows for AscentVertSpeedChart. Unit conversion happens here so
  // the chart itself stays purely presentational; `send` flags green bars.
  const ascentPerClimb = useMemo(() => {
    if (!hasAscent) return [];
    return activeSplits.map((c, i) => ({
      idx: i + 1,
      ascent: imperial
        ? Math.round((c.total_ascent ?? 0) * 3.28084)
        : Math.round(c.total_ascent ?? 0),
      vert: c.avg_vert_speed
        ? +(imperial ? c.avg_vert_speed * 3.28084 : c.avg_vert_speed).toFixed(2)
        : 0,
      send: c.climb_result === 3,
    }));
  }, [activeSplits, imperial, hasAscent]);

  // Cumulative elapsed time at the start of each active climb (skipping the
  // first) — drawn as lap markers on the HeartRateGraph.
  const lapTimes = useMemo(() => {
    const times = [];
    let cumulative = 0;
    for (const split of climbs) {
      if (split.split_type === "climb_active" && times.length > 0) times.push(cumulative);
      cumulative += (split.duration_seconds || 0);
    }
    return times;
  }, [climbs]);

  // Column set is data-driven: optional columns appear only when the activity
  // actually has that field populated (see buildClimbLogColumns).
  const columns = buildClimbLogColumns({ hasGrades, hasResults, hasAscent, hasCalories, imperial });

  // Each active climb row is annotated with the duration of the rest split
  // that immediately follows it in the raw split sequence.
  const tableData = activeSplits.map((c, i) => {
    const climbIdx = climbs.indexOf(c);
    const nextRest = climbs.slice(climbIdx + 1).find((s) => s.split_type === "climb_rest");
    return {
      id: c.id,
      number: i + 1,
      ...c,
      rest_after: nextRest ? nextRest.duration_seconds : null,
    };
  });

  const chartData = activeSplits.map((c, i) => ({
    idx: i + 1,
    score: c.difficulty_score ?? 0,
    grade_level: c.grade_level,
    difficulty_score: c.difficulty_score,
    total_ascent: c.total_ascent,
    max_heart_rate: c.max_heart_rate,
    climb_result: c.climb_result,
  }));

  return (
    <div>
      <ActivityHeader activity={activity} sportType={sportType} />

      <StatPillsStrip sportType={sportType}>
        <StatPill value={activeSplits.length} label="Routes" />
        {hasResults && sends > 0 && (
          <StatPill value={sends} label="Sends" sub={sendRate != null ? `${sendRate.toFixed(0)}%` : undefined} />
        )}
        {hasResults && attempts > 0 && (
          <StatPill value={attempts} label="Attempts" />
        )}
        {hardestSendDisplay != null && (
          <StatPill value={<GradeDisplay grade={hardestSendDisplay} />} label="Hardest Send" />
        )}
        {hardestTried != null && hardestTried !== hardestSendDisplay && (
          <StatPill value={<GradeDisplay grade={hardestTried} />} label="Hardest Tried" />
        )}
        {hasAscent && (
          <StatPill value={formatElevation(totalAscent, imperial)} label="Total Vertical" />
        )}
        {hasVertSpeed && bestVertSpeed > 0 && (
          <StatPill
            value={`${(imperial ? bestVertSpeed * 3.28084 : bestVertSpeed).toFixed(2)} ${imperial ? "ft/s" : "m/s"}`}
            label="Best Vert Speed"
          />
        )}
        <StatPill
          value={<DurationDisplay seconds={totalActiveTime} format="detailed" />}
          label="Climbing Time"
        />
        {avgRestTime != null && (
          <StatPill
            value={<DurationDisplay seconds={Math.round(avgRestTime)} format="compact" />}
            label="Avg Rest"
          />
        )}
        {activeRatio != null && (
          <StatPill value={`${activeRatio.toFixed(0)}%`} label="On Wall" />
        )}
        {avgClimbHR != null && <StatPill value={`${avgClimbHR} bpm`} label="Avg Climb HR" />}
        {maxClimbHR > 0 && <StatPill value={`${maxClimbHR} bpm`} label="Peak HR" />}
        {totalClimbCalories > 0 && (
          <StatPill value={`${totalClimbCalories} kcal`} label="Calories" />
        )}
      </StatPillsStrip>

      <ActivityTimeline
        splits={climbs}
        totalDuration={totalDuration}
        activeType="climb_active"
      />

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 mt-4">
        {/* LEFT — analytics */}
        <div className="space-y-4">
          {chartData.length > 0 && (
            <ClimbEffortChart activeSplits={chartData} imperial={imperial} />
          )}

          {hasGrades && (
            <ClimbGradeHistogram activeSplits={activeSplits} />
          )}

          {ascentPerClimb.length > 0 && (
            <AscentVertSpeedChart
              ascentPerClimb={ascentPerClimb}
              imperial={imperial}
              hasResults={hasResults}
            />
          )}

          {hrData.length > 0 && (
            <HeartRateGraph
              data={hrData}
              maxHR={maxHR}
              hrYMin={hrYMin}
              onHover={onHover}
              onLeave={onLeave}
              lapTimes={lapTimes}
            />
          )}
        </div>

        {/* RIGHT — map + distributions */}
        <div className="space-y-4">
          {gpsTrack.length > 0 && (
            <div className="rounded-xl overflow-hidden bg-slate-900" style={{ height: 480 }}>
              <ActivityRoute ref={routeRef} track={gpsTrack} height={480} />
            </div>
          )}

          {hrHistogram.length > 0 && (
            <HRHistogram data={hrHistogram} maxHR={maxHR} />
          )}

          {restHistogramData.length > 0 && (
            <RestHistogram data={restHistogramData} />
          )}
        </div>
      </div>

      {/* Full-width climb log */}
      {tableData.length > 0 && (
        <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 p-3.5 mt-4">
          <h3 className="text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider mb-3">
            Climb Log
          </h3>
          <ActivityTable
            columns={columns}
            data={tableData}
            emptyMessage="No climb data available"
            onRowClick={onClimbClick}
          />
        </div>
      )}
    </div>
  );
}

export default ClimbingLayout;
