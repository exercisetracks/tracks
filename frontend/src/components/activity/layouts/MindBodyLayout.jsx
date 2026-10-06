// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import React, { useMemo, useCallback } from "react";
import { ActivityHeader } from "./ActivityHeader";
import { StatPill, StatPillsStrip } from "../pills/StatPills";
import { DurationDisplay } from "../pills/DurationDisplay";
import { HeartRateGraph } from "../graphs/HeartRateGraph";
import { HRHistogram } from "../charts/HRHistogram";

export function MindBodyLayout({
  activity,
  track,
  laps,
  imperial = false,
  sportType = "mind_body",
}) {
  const onHover = useCallback(() => {}, []);
  const onLeave = useCallback(() => {}, []);

  if (!activity) return null;

  const avgHR = activity.avg_heart_rate;
  const maxHR = activity.max_heart_rate;
  const calories = activity.total_calories;
  const tss = activity.training_stress_score;

  const hrData = useMemo(() =>
    track?.filter((p) => p.heart_rate != null).map((p) => ({ elapsed: p.elapsed, hr: p.heart_rate })) || [],
    [track]);

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

  const hasHRData = hrData.length > 0;

  return (
    <div>
      <ActivityHeader activity={activity} sportType={sportType} />

      <StatPillsStrip sportType={sportType}>
        <StatPill value={<DurationDisplay seconds={activity.duration_seconds} format="detailed" />} label="Duration" />
        {avgHR && <StatPill value={`${avgHR} bpm`} label="Avg HR" />}
        {maxHR && <StatPill value={`${maxHR} bpm`} label="Max HR" />}
        {calories > 0 && <StatPill value={`${calories} kcal`} label="Calories" />}
        {tss > 0 && <StatPill value={Math.round(tss)} label="TSS" />}
      </StatPillsStrip>

      {hasHRData ? (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 mt-4">
          <div className="space-y-4">
            <HeartRateGraph
              data={hrData}
              maxHR={maxHR ?? 200}
              hrYMin={hrYMin}
              onHover={onHover}
              onLeave={onLeave}
              lapTimes={[]}
            />
          </div>

          <div className="space-y-4">
            {hrHistogram.length > 0 && (
              <HRHistogram data={hrHistogram} maxHR={maxHR ?? 200} />
            )}

          </div>
        </div>
      ) : (
        <div className="mt-4">
          <div className="card">
            <p className="text-sm text-slate-500 dark:text-slate-400 text-center py-5">
              No sensor data recorded for this session.
            </p>
          </div>
        </div>
      )}
    </div>
  );
}

export default MindBodyLayout;
