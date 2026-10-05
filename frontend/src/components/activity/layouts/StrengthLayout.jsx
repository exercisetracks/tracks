// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * StrengthLayout.jsx
 * ------------------------------------------------------------------------
 * Detail view for a single strength-training activity.
 *
 * A strength activity is a flat, time-ordered list of `sets` (each either an
 * `active` working set or a `rest` gap). This component derives the summary
 * stats, per-set progression series, muscle activation, and exercise log from
 * that list and lays them out: header + stat strip + set timeline, then a
 * two-column grid (progression graphs on the left, analytics on the right) with
 * the full exercise/movement log below.
 *
 * All state/wiring lives here (refs, useMapCursor, the HR-track useMemos). The
 * pure, React-free data-shaping helpers that turn raw sets into chart/log data
 * are Strength-specific and extracted to ./strength/setDataHelpers.js to keep
 * this file focused on render logic.
 *
 * Pure structural refactor of the original single-file layout — no behaviour,
 * styling, prop, or data changes.
 * ------------------------------------------------------------------------
 */
import React, { useMemo, useRef } from "react";
import { useMapCursor } from "../../../hooks/useMapCursor";
import { ActivityHeader } from "./ActivityHeader";
import { ActivityTimeline } from "../timelines/ActivityTimeline";
import { StatPill, StatPillsStrip, StatPillsGrid } from "../pills/StatPills";
import { DurationDisplay } from "../pills/DurationDisplay";
import { WeightDisplay, WeightPill } from "../pills/WeightDisplay";
import { ActivityTable } from "../tables/ActivityTable";
import { ExerciseCardGroup } from "../cards/ExerciseCardGroup";
import { WeightGraph } from "../graphs/WeightGraph";
import { VolumeGraph } from "../graphs/VolumeGraph";
import { RepGraph } from "../graphs/RepGraph";
import { StrengthHRGraph } from "../graphs/StrengthHRGraph";
import { HRHistogram } from "../charts/HRHistogram";
import { RestHistogram } from "../charts/RestHistogram";
import { MuscleMap } from "../charts/MuscleMap";
import { computeMuscleActivation } from "../../../utils/muscleGroups";
import ActivityRoute from "../../ActivityRoute";
// Strength-specific pure helpers: reshape the raw `sets` list into the data each
// chart / card / timeline expects. See ./strength/setDataHelpers.js.
import {
  groupSetsByExercise,
  formatSetsForTimeline,
  buildWeightData,
  buildVolumeData,
  buildRepData,
  buildHRData,
  buildRestHistogramData,
  calculateAvgRestTime,
} from "./strength/setDataHelpers";

export function StrengthLayout({
  activity,
  sets,
  track,
  imperial = false,
  sportType = "strength",
  onHover: externalOnHover,
  onLeave: externalOnLeave,
}) {
  const routeRef = useRef(null);
  const { trackRef, onHover, onLeave } = useMapCursor(routeRef);

  if (!sets?.length) return null;

  const gpsTrack = track?.filter((p) => p.lat && p.lng) || [];
  trackRef.current = gpsTrack;

  const exercises = groupSetsByExercise(sets);

  const totalSets = sets.filter((s) => s.set_type === "active").length;
  const totalReps = sets
    .filter((s) => s.set_type === "active")
    .reduce((n, s) => n + (s.repetitions ?? 0), 0);
  const totalDuration = activity?.duration_seconds ?? 0;

  const maxWeight = sets.reduce((m, s) => {
    if (s.weight_kg && s.weight_kg > m) return s.weight_kg;
    return m;
  }, 0);

  const totalVolume = sets
    .filter((s) => s.set_type === "active")
    .reduce((n, s) => n + ((s.weight_kg || 0) * (s.repetitions || 0)), 0);

  const avgRestTime = calculateAvgRestTime(sets);

  const exerciseCards = exercises.map((ex) => ({
    name: ex.name,
    sets: ex.sets,
    summary: {
      sets: ex.activeSets.length,
      reps: ex.activeSets.reduce((n, s) => n + (s.repetitions ?? 0), 0),
      maxWeight: ex.maxWeight,
      bestOneRM: Math.max(0, ...ex.activeSets.map((s) => {
        const w = s.weight_kg || 0;
        const r = s.repetitions || 0;
        return w > 0 && r > 0 ? w * (1 + r / 30) : 0;
      })),
    },
  }));

  const { activation: muscleActivation, totals: muscleTotals } = useMemo(
    () => computeMuscleActivation(sets),
    [sets]
  );
  const hasMuscleData = Object.values(muscleActivation).some((v) => v > 0);

  const weightData = buildWeightData(sets);
  const volumeData = buildVolumeData(sets);
  const repData = buildRepData(sets);
  const hrData = buildHRData(sets, track);
  const restHistogramData = buildRestHistogramData(sets);

  const lapTimes = useMemo(() => {
    const times = [];
    let cumulative = 0;
    for (const set of sets) {
      if (set.set_type === "active" && times.length > 0) {
        times.push(cumulative);
      }
      cumulative += (set.duration_seconds || 30);
    }
    return times;
  }, [sets]);

  const chartTrack = useMemo(() => {
    if (!track?.length) return [];
    const MAX = 800;
    if (track.length <= MAX) return track;
    const step = Math.ceil(track.length / MAX);
    return track.filter((_, i) => i % step === 0 || i === track.length - 1);
  }, [track]);

  const computedHistData = useMemo(() => {
    if (!chartTrack?.length) return [];
    let lo = Infinity, hi = -Infinity;
    for (const p of chartTrack) {
      if (p.heart_rate == null) continue;
      if (p.heart_rate < lo) lo = p.heart_rate;
      if (p.heart_rate > hi) hi = p.heart_rate;
    }
    if (!isFinite(lo)) return [];
    const mn = Math.floor(lo / 2) * 2, mx = Math.ceil(hi / 2) * 2;
    const bins = [];
    for (let h = mn; h < mx; h += 2) bins.push({ range: `${h}–${h + 2}`, hr: h, count: 0 });
    for (const p of chartTrack) {
      if (p.heart_rate == null || p.heart_rate < mn || p.heart_rate >= mx) continue;
      bins[Math.floor((p.heart_rate - mn) / 2)].count++;
    }
    return bins;
  }, [chartTrack]);

  return (
    <div>
      <ActivityHeader activity={activity} sportType={sportType} />

      <StatPillsStrip sportType={sportType}>
        <StatPill value={exercises.length} label="Exercises" />
        <StatPill value={totalSets} label="Sets" />
        {totalReps > 0 && <StatPill value={totalReps.toLocaleString()} label="Total Reps" />}
        {totalVolume > 0 && (
          <StatPill
            value={<WeightDisplay kg={totalVolume} imperial={imperial} />}
            label="Total Volume"
          />
        )}
        {maxWeight > 0 && (
          <StatPill
            value={<WeightDisplay kg={maxWeight} imperial={imperial} />}
            label="Max Weight"
          />
        )}
        {avgRestTime > 0 && <StatPill value={`${Math.round(avgRestTime)}s`} label="Avg Rest" />}
        <StatPill
          value={<DurationDisplay seconds={totalDuration} format="detailed" />}
          label="Duration"
        />
        {activity?.total_calories > 0 && (
          <StatPill value={`${activity.total_calories} kcal`} label="Calories" />
        )}
      </StatPillsStrip>

      <ActivityTimeline
        splits={formatSetsForTimeline(sets)}
        totalDuration={totalDuration}
        activeType="active"
      />

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-4 mt-4">
        {/* LEFT: per-set progression graphs */}
        <div className="space-y-4 lg:col-span-5">
          {weightData.length > 0 && (
            <WeightGraph data={weightData} onHover={onHover} onLeave={onLeave} lapTimes={lapTimes} />
          )}

          {volumeData.length > 0 && (
            <VolumeGraph data={volumeData} onHover={onHover} onLeave={onLeave} lapTimes={lapTimes} />
          )}

          {repData.length > 0 && (
            <RepGraph data={repData} onHover={onHover} onLeave={onLeave} lapTimes={lapTimes} />
          )}

          {hrData.length > 0 && (
            <StrengthHRGraph
              data={hrData}
              maxHR={activity?.max_heart_rate ?? 200}
              onHover={onHover}
              onLeave={onLeave}
              lapTimes={lapTimes}
            />
          )}

          {computedHistData.length > 0 && (
            <HRHistogram
              data={computedHistData}
              maxHR={activity?.max_heart_rate ?? 200}
            />
          )}
        </div>

        {/* RIGHT: analytics */}
        <div className="flex flex-col gap-4 min-h-0 lg:col-span-7">
          <MuscleMap activation={muscleActivation} totals={muscleTotals} hasData={hasMuscleData} />

          {gpsTrack.length > 0 && (
            <div className="rounded-xl overflow-hidden bg-slate-900" style={{ height: 300 }}>
              <ActivityRoute ref={routeRef} track={gpsTrack} height={300} />
            </div>
          )}

          {restHistogramData.length > 0 && (
            <RestHistogram data={restHistogramData} />
          )}
        </div>
      </div>

      {/* Movement list — full width below the grid */}
      <div className="mt-4">
        <ExerciseCardGroup
          exercises={exerciseCards}
          imperial={imperial}
        />
      </div>
    </div>
  );
}

export default StrengthLayout;
