// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Dashboard page: the app's home screen — a period-filtered grid of summary
// cards and charts (overview stats, readiness/coaching, fitness & weekly-volume
// charts, activity calendar, sport breakdown, and a training-location heatmap).
//
// This file owns all page-level state and data fetching: the period selector,
// the parallel API loads, and the selected-sport cross-filter that several
// widgets share. Self-contained presentational pieces (the section wrapper, the
// recap banner, the readiness card) and the pure date/format helpers live in
// components/dashboard/ so this file stays focused on data orchestration.

import { useEffect, useState, useMemo, useCallback } from "react";
import { api } from "../api/client";
import StatCard from "../components/StatCard";
import CoachingCard from "../components/CoachingCard";
import Vo2MaxWidget from "../components/Vo2MaxWidget";
import ReadinessWidget from "../components/ReadinessWidget";
import FitnessChart from "../components/Charts/FitnessChart";
import ActivityCalendar from "../components/Charts/ActivityCalendar";
import SportBreakdown from "../components/Charts/SportBreakdown";
import ActivityHeatmap from "../components/Charts/ActivityHeatmap";
import PlannedWorkoutCard from "../components/PlannedWorkoutCard";
import WeeklyVolumeChart from "../components/Charts/WeeklyVolumeChart";
import WorkoutRecapModal from "../components/WorkoutRecapModal";

import { PERIODS } from "../components/dashboard/constants";
import { afterDateFor, buildXAxisConfig, fmt } from "../components/dashboard/helpers";
import { Section, Card } from "../components/ui/Section";
import BarPills from "../components/ui/BarPills";
import PageHeader from "../components/ui/PageHeader";
import RecapBanner from "../components/dashboard/RecapBanner";

export default function Dashboard() {
  const [period,    setPeriod]    = useState("yearly");
  const [me,        setMe]        = useState(null);
  const [summary,   setSummary]   = useState(null);
  const [settings,  setSettings]  = useState(null);
  const [allLoad,      setAllLoad]      = useState(null);
  const [coaching,     setCoaching]     = useState(null);
  const [upcomingWorkouts, setUpcomingWorkouts] = useState([]);
  const [pageReady,    setPageReady]    = useState(false);
  const [calendarData,   setCalendarData]   = useState([]);
  const [sportData,      setSportData]      = useState([]);
  const [selectedSport,  setSelectedSport]  = useState("");
  const [vo2History,     setVo2History]     = useState([]);
  const [readinessHistory, setReadinessHistory] = useState([]);
  const [weeklyVolume,   setWeeklyVolume]   = useState([]);
  // Animation-feedback recaps: list of completed workouts that still need
  // the user to mark which exercises animated. Refetched after the modal
  // closes so the banner disappears when everything's been reviewed.
  const [pendingRecaps,  setPendingRecaps]  = useState([]);
  const [recapOpen,      setRecapOpen]      = useState(false);

  const imperial  = settings?.units === "imperial";
  const distUnit  = imperial ? "mi" : "km";
  const distFmt = useCallback((km, decimals) => {
    if (km == null) return null;
    return fmt(imperial ? km * 0.621371 : km, decimals);
  }, [imperial]);

  // Training load + coaching: fetch once (need full history for CTL warm-up)
  useEffect(() => {
    let cancelled = false;
    api.getMe().then(d => { if (!cancelled) setMe(d); }).catch(() => {});
    api.getSettings().then(d => { if (!cancelled) setSettings(d); }).catch(() => {});
    api.getTrainingLoad().then(d => { if (!cancelled) setAllLoad(d); }).catch(() => {});
    api.getCoachingToday().then(d => { if (!cancelled) setCoaching(d); }).catch(() => {});
    api.getUpcomingWorkouts(14).then(d => { if (!cancelled) setUpcomingWorkouts(d); }).catch(() => {});
    api.getPendingRecaps().then(d => { if (!cancelled) setPendingRecaps(d || []); }).catch(() => {});
    return () => { cancelled = true; };
  }, []);

  // Refetch the pending list after the modal closes — it'll be empty once
  // the user has stepped through all queued workouts.
  const refreshRecaps = useCallback(() => {
    api.getPendingRecaps().then(d => setPendingRecaps(d || [])).catch(() => {});
  }, []);

  // Summary, sport breakdown, and VO2Max history: refetch when period changes
  useEffect(() => {
    let cancelled = false;
    const after = afterDateFor(period);
    const params = after ? { after } : {};
    api.getSummary(params)
      .then(s => { if (!cancelled) { setSummary(s); setPageReady(true); } })
      .catch(() => { if (!cancelled) setPageReady(true); });
    api.getBySort(params).then(d => { if (!cancelled) setSportData(d); }).catch(() => {});
    api.getVo2MaxHistory(params).then(d => { if (!cancelled) setVo2History(d); }).catch(() => {});
    // Readiness history is windowed by day count (endpoint caps at 365);
    // lifetime falls back to the 365-day max.
    const days = Math.min(PERIODS.find(p => p.value === period)?.days ?? 365, 365);
    api.getReadinessHistory(days).then(d => { if (!cancelled) setReadinessHistory(d); }).catch(() => {});
    return () => { cancelled = true; };
  }, [period]);

  // Weekly volume: refetch when period OR sport changes
  useEffect(() => {
    let cancelled = false;
    const after = afterDateFor(period);
    const params = after ? { after } : {};
    if (selectedSport) params.sport = selectedSport;
    api.getWeeklyVolume(params).then(d => { if (!cancelled) setWeeklyVolume(d); }).catch(() => {});
    return () => { cancelled = true; };
  }, [period, selectedSport]);

  // Calendar: refetch when period OR selected sport changes
  useEffect(() => {
    let cancelled = false;
    const after = afterDateFor(period);
    const params = after ? { after } : {};
    if (selectedSport) params.sport = selectedSport;
    api.getActivityCalendar(params).then(d => { if (!cancelled) setCalendarData(d); }).catch(() => {});
    return () => { cancelled = true; };
  }, [period, selectedSport]);

  // Filter training load to the selected display window.
  // Memoized so FitnessChart only re-animates on genuine data changes.
  const filteredLoad = useMemo(() => {
    if (!allLoad?.length) return [];
    const after = afterDateFor(period);
    return after ? allLoad.filter(p => p.date >= after) : allLoad;
  }, [allLoad, period]);

  // Shared numeric X axis for the Fitness + Weekly Volume charts so they align
  // vertically and their hover cursor stays in sync via syncMethod="value".
  const xAxisConfig = useMemo(
    () => buildXAxisConfig(period, filteredLoad[0]?.date),
    [period, filteredLoad],
  );

  // Derived coaching display values — must appear BEFORE any early return
  // to satisfy React's rules of hooks (hooks cannot be conditional).
  const recommendations  = useMemo(() =>
    (coaching?.recommendations ?? []).map(r => ({
      modality:         r.modality ?? "cardio",
      title:            r.title,
      focus:            r.focus,
      sport:            r.sport,
      description:      r.description,
      duration_minutes: r.duration_minutes,
      intensity:        r.intensity,
    })),
    [coaching?.recommendations],
  );

  if (!pageReady) {
    return (
      <div className="flex items-center justify-center h-64 text-sm text-slate-400 dark:text-slate-500">
        Loading…
      </div>
    );
  }

  return (
    <div className="p-5 max-w-7xl mx-auto space-y-8">
      {/* Header — the period as bar pills beside the title, as on the phone:
          it governs every card below, so it stays visible rather than
          behind a dropdown. */}
      <PageHeader title={me?.name ? `Hi, ${me.name}` : "Dashboard"}>
        <BarPills
          dataTour="dashboard-period"
          label="Period"
          options={PERIODS}
          value={period}
          onChange={setPeriod}
        />
      </PageHeader>

      {/* Animation feedback banner — only when there are recaps to fill in.
          Clicking opens the modal; modal walks all queued workouts and
          refetches the list when done. */}
      <RecapBanner pendingRecaps={pendingRecaps} onReview={() => setRecapOpen(true)} />

      {/* Headline stats */}
      <Section title="Overview">
        <div data-tour="dashboard-overview" className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-3">
          <StatCard
            label="Filtered Activities"
            value={summary?.activity_count ?? 0}
          />
          <StatCard
            label="Total Distance"
            value={distFmt(summary?.total_distance_km, 0)}
            unit={distUnit}
          />
          <StatCard
            label="Total Time"
            value={fmt(summary?.total_duration_hours, 1)}
            unit="h"
          />
          <StatCard
            label="Unique Sports"
            value={summary?.sport_count ?? 0}
          />
          <StatCard
            label="Avg Distance"
            value={distFmt(summary?.avg_distance_km, 1)}
            unit={distUnit}
            sub="per activity"
          />
          <StatCard
            label="Avg Duration"
            value={fmt(summary?.avg_duration_minutes, 0)}
            unit="min"
            sub="per activity"
          />
        </div>
      </Section>

      {/* Readiness + coaching/plan + VO2Max */}
      <Section title="Upcoming">
        <div data-tour="dashboard-upcoming" className="grid grid-cols-1 lg:grid-cols-[1fr_auto] gap-4 items-stretch">
          {upcomingWorkouts.length > 0
            ? <PlannedWorkoutCard workouts={upcomingWorkouts} imperial={imperial} />
            : <CoachingCard recommendations={recommendations} />
          }
          {/* Right rail: VO2Max + Readiness gauges, split evenly to fill the
              column height alongside the taller coaching/plan card. */}
          <div className="grid grid-rows-2 gap-4 h-full">
            <Vo2MaxWidget history={vo2History} />
            <ReadinessWidget history={readinessHistory} />
          </div>
        </div>
      </Section>

      {/* Fitness chart — display window follows the period selector */}
      <Section title={`Fitness${period !== "lifetime" ? ` — ${PERIODS.find(p => p.value === period)?.label}` : " — All time"}`}>
        <Card data-tour="dashboard-fitness">
          <FitnessChart data={filteredLoad} xAxis={xAxisConfig} />
        </Card>
      </Section>

      {/* Weekly volume chart — shares xAxisConfig with FitnessChart for alignment */}
      <Section title={`Weekly Volume${selectedSport ? ` — ${selectedSport}` : ""}`}>
        <Card>
          <WeeklyVolumeChart data={weeklyVolume} imperial={imperial} xAxis={xAxisConfig} />
        </Card>
      </Section>

      {/* Activity calendar + sport breakdown */}
      <Section title="Activity History">
        <div className="grid grid-cols-1 lg:grid-cols-[2fr_1fr] gap-4 items-stretch">
          <Card className="flex flex-col">
            <ActivityCalendar
              data={calendarData}
              days={period === "lifetime" ? null : 365}
            />
          </Card>
          <Card>
            <SportBreakdown
              data={sportData}
              selectedSport={selectedSport}
              onSportSelect={setSelectedSport}
            />
          </Card>
        </div>
      </Section>

      {/* Geographic heatmap */}
      <Section title="Training Locations">
        <div className="card p-0 overflow-hidden">
          <ActivityHeatmap sport={selectedSport} onSportChange={setSelectedSport} />
        </div>
      </Section>

      {recapOpen && pendingRecaps.length > 0 && (
        <WorkoutRecapModal
          workoutIds={pendingRecaps.map(r => r.workout_id)}
          onClose={() => { setRecapOpen(false); refreshRecaps(); }}
          onAllDone={refreshRecaps}
        />
      )}
    </div>
  );
}
