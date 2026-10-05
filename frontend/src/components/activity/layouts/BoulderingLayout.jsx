// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * BoulderingLayout
 * =========================================================================
 * Detail view for a single bouldering activity. Sibling of ClimbingLayout,
 * StrengthLayout, CyclingLayout, etc. (same directory) — they share the visual
 * idioms (ActivityHeader, StatPillsStrip, ChartCard, ActivityTable) but each
 * owns its sport-specific metrics and chart selection.
 *
 * Data model: `climbs` is a list of split rows, each either a "climb_active"
 * problem attempt or a "climb_rest" gap. All the numbers on this page are
 * derived from those two subsets (activeSplits / restSplits) plus the raw
 * `track` (per-sample HR timeseries used for the HR graph + histogram).
 *
 * Structure of this file (kept mostly whole on purpose — the derivations are
 * tightly coupled to the same local data and splitting them would create
 * prop-drilling with no reuse payoff):
 *   1. Feature flags + aggregate metrics (sends, rates, HR, calories, grades).
 *   2. Memoized chart datasets (grade breakdown, session flow, effort-vs-rest,
 *      HR histogram, rest histogram, lap times) and the problem-log table spec.
 *   3. Render: header -> stat pills -> timeline -> two-column charts -> HR
 *      graph -> full-width problem log.
 *
 * Two genuinely separable, low-coupling chart blocks were extracted to keep the
 * render readable; each just consumes one pre-computed data array from here:
 *   - ./bouldering/SendRateByGradeChart  (from `gradeBreakdown`)
 *   - ./bouldering/EffortVsRecoveryChart (from `effortVsRest`)
 * They are bouldering-specific, so they live under layouts/bouldering/.
 */

import React, { useMemo } from "react";
import { ActivityHeader } from "./ActivityHeader";
import { ActivityTimeline } from "../timelines/ActivityTimeline";
import { StatPill, StatPillsStrip } from "../pills/StatPills";
import { DurationDisplay } from "../pills/DurationDisplay";
import { GradeDisplay } from "../pills/GradeDisplay";
import { ResultBadge } from "../cards/ResultBadge";
import { ActivityTable } from "../tables/ActivityTable";
import { ClimbEffortChart } from "../charts/ClimbEffortChart";
import { ClimbGradeHistogram } from "../charts/ClimbGradeHistogram";
import { RestHistogram } from "../charts/RestHistogram";
import { HRHistogram } from "../charts/HRHistogram";
import { HeartRateGraph } from "../graphs/HeartRateGraph";
import { SendRateByGradeChart } from "./bouldering/SendRateByGradeChart";
import { EffortVsRecoveryChart } from "./bouldering/EffortVsRecoveryChart";

export function BoulderingLayout({
  activity,
  climbs,
  track,
  imperial = false,
  sportType = "bouldering",
  onClimbClick,
}) {
  if (!climbs?.length) return null;

  // Split the session into problem attempts vs rest gaps. Every aggregate on
  // this page derives from one of these two subsets (plus the raw HR track).
  const activeSplits = climbs.filter((c) => c.split_type === "climb_active");
  const restSplits = climbs.filter((c) => c.split_type === "climb_rest");

  // Feature flags: only show a column / pill when the data actually exists.
  const hasGrades = activeSplits.some((c) => c.grade_level != null);
  const hasResults = activeSplits.some((c) => c.climb_result != null);
  const hasCalories = activeSplits.some((c) => (c.total_calories ?? 0) > 0);

  const totalActiveTime = activeSplits.reduce((s, c) => s + (c.duration_seconds ?? 0), 0);
  const totalRestTime = restSplits.reduce((s, c) => s + (c.duration_seconds ?? 0), 0);
  const totalDuration = activity?.duration_seconds ?? 0;
  const sends = activeSplits.filter((c) => c.climb_result === 3).length;
  const attempts = activeSplits.length - sends;
  const sendRate = hasResults && activeSplits.length > 0
    ? (sends / activeSplits.length) * 100
    : null;
  const activeRatio = totalDuration > 0 ? (totalActiveTime / totalDuration) * 100 : null;

  const avgAttemptTime = activeSplits.length
    ? totalActiveTime / activeSplits.length
    : null;
  const avgRestTime = restSplits.length ? totalRestTime / restSplits.length : null;

  const maxClimbHR = Math.max(0, ...activeSplits.map((c) => c.max_heart_rate ?? 0));
  const avgClimbHR = (() => {
    const hrs = activeSplits.map((c) => c.max_heart_rate).filter((v) => v != null && v > 0);
    return hrs.length ? Math.round(hrs.reduce((a, b) => a + b, 0) / hrs.length) : null;
  })();
  const totalClimbCalories = activeSplits.reduce((s, c) => s + (c.total_calories ?? 0), 0);

  const maxGrade = hasGrades
    ? Math.max(...activeSplits.map((c) => c.grade_level ?? 0))
    : null;

  const hardestSend = hasGrades && hasResults
    ? Math.max(-1, ...activeSplits.filter((c) => c.climb_result === 3).map((c) => c.grade_level ?? -1))
    : -1;
  const hardestSendDisplay = hardestSend >= 0 ? hardestSend : null;

  // Per-grade send rate breakdown
  const gradeBreakdown = useMemo(() => {
    if (!hasGrades) return [];
    const buckets = {};
    for (const c of activeSplits) {
      const g = c.grade_level ?? 0;
      if (!buckets[g]) buckets[g] = { grade: g, sends: 0, attempts: 0, tries: 0 };
      buckets[g].tries++;
      if (c.climb_result === 3) buckets[g].sends++;
      else if (c.climb_result === 2) buckets[g].attempts++;
    }
    return Object.values(buckets)
      .sort((a, b) => a.grade - b.grade)
      .map((b) => ({
        label: `V${b.grade}`,
        sends: b.sends,
        attempts: b.attempts,
        tries: b.tries,
        sendRate: b.tries > 0 ? Math.round((b.sends / b.tries) * 100) : 0,
      }));
  }, [activeSplits, hasGrades]);

  // Sessions arc: send/attempt distributed across the session
  const sessionFlow = useMemo(() => {
    let cumActiveSec = 0;
    let cumSends = 0, cumAttempts = 0;
    return activeSplits.map((c, i) => {
      cumActiveSec += c.duration_seconds ?? 0;
      if (c.climb_result === 3) cumSends++;
      else cumAttempts++;
      return {
        idx: i + 1,
        result: c.climb_result,
        durationMin: +(((c.duration_seconds ?? 0) / 60).toFixed(2)),
        peakHr: c.max_heart_rate ?? 0,
        grade: c.grade_level ?? 0,
        cumSends,
        cumAttempts,
      };
    });
  }, [activeSplits]);

  // Effort vs rest scatter (size = peak HR)
  const effortVsRest = useMemo(() => {
    const out = [];
    for (let i = 0; i < activeSplits.length; i++) {
      const c = activeSplits[i];
      const climbIdx = climbs.indexOf(c);
      const nextRest = climbs.slice(climbIdx + 1).find((s) => s.split_type === "climb_rest");
      const rest = nextRest?.duration_seconds ?? null;
      out.push({
        idx: i + 1,
        duration: +(((c.duration_seconds ?? 0) / 60).toFixed(2)),
        rest: rest != null ? +((rest / 60).toFixed(2)) : null,
        peakHr: c.max_heart_rate ?? 0,
        send: c.climb_result === 3,
      });
    }
    return out.filter((p) => p.rest != null);
  }, [activeSplits, climbs]);

  // HR distribution from the track
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
      { range: "0–30s",  min: 0,   max: 30,  count: 0 },
      { range: "30–60s", min: 30,  max: 60,  count: 0 },
      { range: "1–2m",   min: 60,  max: 120, count: 0 },
      { range: "2–5m",   min: 120, max: 300, count: 0 },
      { range: "5m+",    min: 300, max: Infinity, count: 0 },
    ];
    for (const r of restTimes) {
      for (const b of bins) {
        if (r >= b.min && r < b.max) { b.count++; break; }
      }
    }
    return bins.filter((b) => b.count > 0);
  }, [restSplits]);

  const lapTimes = useMemo(() => {
    const times = [];
    let cumulative = 0;
    for (const split of climbs) {
      if (split.split_type === "climb_active" && times.length > 0) times.push(cumulative);
      cumulative += (split.duration_seconds || 0);
    }
    return times;
  }, [climbs]);

  const columns = [
    { key: "number", label: "#", align: "left" },
    ...(hasGrades
      ? [{
          key: "grade_level",
          label: "Grade",
          align: "left",
          className: "font-semibold text-accent-600 dark:text-accent-400",
          formatter: (v) => <GradeDisplay grade={v} />,
        }]
      : []),
    ...(hasResults
      ? [{
          key: "climb_result",
          label: "Result",
          align: "center",
          formatter: (v) => <ResultBadge result={v} />,
        }]
      : []),
    {
      key: "duration_seconds",
      label: "On Wall",
      align: "right",
      formatter: (v) => <DurationDisplay seconds={v} format="compact" />,
    },
    {
      key: "max_heart_rate",
      label: "Peak HR",
      align: "right",
      formatter: (v) => (v ? `${v} bpm` : "—"),
    },
    ...(hasCalories
      ? [{
          key: "total_calories",
          label: "kcal",
          align: "right",
          formatter: (v) => (v ? v : "—"),
        }]
      : []),
    {
      key: "rest_after",
      label: "Rest After",
      align: "right",
      formatter: (v) => <DurationDisplay seconds={v} format="compact" />,
    },
  ];

  const tableData = activeSplits.map((c, i) => {
    const climbIdx = climbs.indexOf(c);
    const nextRest = climbs.slice(climbIdx + 1).find((s) => s.split_type === "climb_rest");
    return {
      id: c.id,
      number: i + 1,
      ...c,
      rest_after: nextRest?.duration_seconds ?? null,
    };
  });

  const chartData = activeSplits.map((c, i) => ({
    idx: i + 1,
    grade: c.grade_level ?? 0,
    hr: c.max_heart_rate ?? 0,
    result: c.climb_result,
    difficulty_score: c.difficulty_score,
    grade_level: c.grade_level,
    max_heart_rate: c.max_heart_rate,
    climb_result: c.climb_result,
  }));

  return (
    <div>
      <ActivityHeader activity={activity} sportType={sportType} />

      <StatPillsStrip sportType={sportType}>
        <StatPill value={activeSplits.length} label="Problems" />
        {sends > 0 && (
          <StatPill
            value={sends}
            label="Sends"
            sub={sendRate != null ? `${Math.round(sendRate)}%` : undefined}
          />
        )}
        {attempts > 0 && <StatPill value={attempts} label="Attempts" />}
        {hardestSendDisplay != null && (
          <StatPill value={<GradeDisplay grade={hardestSendDisplay} />} label="Hardest Send" />
        )}
        {maxGrade != null && maxGrade !== hardestSendDisplay && (
          <StatPill value={<GradeDisplay grade={maxGrade} />} label="Hardest Tried" />
        )}
        <StatPill value={<DurationDisplay seconds={totalActiveTime} format="detailed" />} label="On Wall" />
        {avgAttemptTime != null && (
          <StatPill
            value={<DurationDisplay seconds={Math.round(avgAttemptTime)} format="compact" />}
            label="Avg Attempt"
          />
        )}
        {avgRestTime != null && (
          <StatPill
            value={<DurationDisplay seconds={Math.round(avgRestTime)} format="compact" />}
            label="Avg Rest"
          />
        )}
        {activeRatio != null && (
          <StatPill value={`${Math.round(activeRatio)}%`} label="Wall Time" />
        )}
        {avgClimbHR != null && <StatPill value={`${avgClimbHR} bpm`} label="Avg HR" />}
        {maxClimbHR > 0 && <StatPill value={`${maxClimbHR} bpm`} label="Peak HR" />}
        {(totalClimbCalories || activity?.total_calories) > 0 && (
          <StatPill
            value={`${totalClimbCalories || activity.total_calories} kcal`}
            label="Calories"
          />
        )}
      </StatPillsStrip>

      <ActivityTimeline
        splits={climbs}
        totalDuration={totalDuration}
        activeType="climb_active"
      />

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 mt-4">
        {/* LEFT — per-problem analytics */}
        <div className="space-y-4">
          <ClimbEffortChart activeSplits={chartData} imperial={imperial} />

          {hasGrades && <ClimbGradeHistogram activeSplits={activeSplits} />}

          {hasGrades && <SendRateByGradeChart data={gradeBreakdown} />}
        </div>

        {/* RIGHT — pacing + recovery + HR */}
        <div className="space-y-4">
          <EffortVsRecoveryChart data={effortVsRest} />

          {restHistogramData.length > 0 && <RestHistogram data={restHistogramData} />}

          {hrHistogram.length > 0 && <HRHistogram data={hrHistogram} maxHR={maxHR} />}
        </div>
      </div>

      {hrData.length > 0 && (
        <div className="mt-4">
          <HeartRateGraph
            data={hrData}
            maxHR={maxHR}
            hrYMin={hrYMin}
            lapTimes={lapTimes}
          />
        </div>
      )}

      {tableData.length > 0 && (
        <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 p-3.5 mt-4">
          <h3 className="text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider mb-3">
            Problem Log
          </h3>
          <ActivityTable
            columns={columns}
            data={tableData}
            emptyMessage="No problem data available"
            onRowClick={onClimbClick}
          />
        </div>
      )}
    </div>
  );
}

export default BoulderingLayout;
