// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Health page: every reading as a dial against its scale, grouped Activity /
// Body / Vitals, each opening its own history; the run of nights on a clock in
// the Sleep card; then injuries, medications and meals. The same design as the
// phone's Health screen — see components/health/MetricGaugeGroup.jsx for why
// dials replaced the old "Today" strip and metric-picker Trends chart.
//
// This file owns page-level data fetching, the window every chart is drawn
// over, and injury CRUD state; the dials and charts live in components/health/.

import { useEffect, useMemo, useState, useCallback, useRef } from "react";
import { api } from "../api/client";
import MedicationSection from "../components/MedicationSection";

import { isoToday } from "../components/health/helpers";
import { Section, Card } from "../components/ui/Section";
import MetricGaugeGroup from "../components/health/MetricGaugeGroup";
import SleepPanel from "../components/health/SleepPanel";
import LogTodayModal from "../components/health/LogTodayModal";
import { healthGroups, hasMeasurements } from "../components/health/metrics";
import { sleepNights } from "../components/health/sleepClock";
import { localIso } from "../components/health/scales";
import InjuryTimeline from "../components/health/InjuryTimeline";
import InjuryForm from "../components/health/InjuryForm";
import InjuryCard from "../components/health/InjuryCard";
import InjuryActivitiesDrawer from "../components/health/InjuryActivitiesDrawer";
import BarPills from "../components/ui/BarPills";
import PageHeader from "../components/ui/PageHeader";
import { PlusIcon } from "../components/ui/Button";
import { todayDate, todayIso } from "../lib/today";
import { autoGoals, goalWindow, resolveGoals } from "../components/health/goals";

/**
 * How far back every chart on the page looks. One window for the whole page,
 * in the header, because it governs every dial's history and the sleep card
 * alike — the same reason the phone puts it in its app bar.
 */
// Longest first, like the dashboard's period and the phone's pills: the same
// control counting in opposite directions on adjacent pages feels wrong
// without anyone being able to say why.
const RANGES = [
  { key: "life", label: "Lifetime", days: null },
  { key: "1y",   label: "1 year",   days: 365 },
  { key: "30d",  label: "30 days",  days: 30 },
  { key: "7d",   label: "7 days",   days: 7 },
];

/**
 * Past a month, the stress chart draws daily averages rather than every
 * reading — a legibility limit, not a fetch one: a year of three-minute samples
 * is a fifth of a pixel each. The server caps its own window on top of this.
 */
const INTRADAY_MAX_DAYS = 31;

/**
 * What is fetched however short the window. The goals read the last four
 * finished weeks whatever range is chosen, and the page decides whether a watch
 * is in play from readings "anywhere in the history", so a window of seven days
 * would hide the whole measured half of the page after a quiet week. Three
 * months covers both; a watch silent for longer than that is only noticed once
 * a longer range is picked.
 */
const BASE_DAYS = 90;
const LIFETIME_DAYS = 36500;

function windowStart(range) {
  if (range.days == null) return null;
  const d = todayDate();
  d.setDate(d.getDate() - range.days);
  return localIso(d);
}

export default function Health() {
  const [metrics,   setMetrics]   = useState([]);
  const [injuries,  setInjuries]  = useState([]);
  const [settings,  setSettings]  = useState(null);
  const [stress,    setStress]    = useState([]);
  const [heartRate, setHeartRate] = useState([]);
  const [spo2, setSpo2] = useState([]);
  const [rangeKey,  setRangeKey]  = useState("30d");
  const [logOpen,   setLogOpen]   = useState(false);
  const [meals,     setMeals]     = useState([]);
  const [food,      setFood]      = useState([]);

  const [highlightedInjuryId, setHighlightedInjuryId] = useState(null);
  const [showInjuryForm,     setShowInjuryForm]     = useState(false);
  const [editingInjury,      setEditingInjury]      = useState(null);
  const [viewingInjury,      setViewingInjury]      = useState(null);
  const [injuryFormLoading,  setInjuryFormLoading]  = useState(false);
  const injuryRefs = useRef({});

  const imperial = settings?.units === "imperial";
  const range = RANGES.find(r => r.key === rangeKey) ?? RANGES[2];
  const start = windowStart(range);

  // Only as much history as the window on screen needs, and more when a longer
  // one is picked. Fetching everything up front made the page wait on years of
  // readings to draw a month of them. A longer fetch is a superset, so going
  // back to a shorter range reuses what is already here instead of asking again.
  const need = range.days == null ? LIFETIME_DAYS : Math.max(range.days, BASE_DAYS);
  const loaded = useRef(0);
  const asked = useRef(0);

  const fetchHistory = useCallback((daysBack) => {
    const ticket = ++asked.current;
    // A stale answer — for a window the user has already moved past — must not
    // replace a newer one that happened to arrive first.
    const current = fn => v => { if (ticket === asked.current) fn(v); };
    api.getHealthSummary(daysBack)
      .then(current(rows => { loaded.current = daysBack; setMetrics(rows); }))
      .catch(() => {});
    // Saved meals, and every food entry — the Eaten dial counts them, so they
    // cover the same span as the metrics.
    api.getMeals().then(setMeals).catch(() => {});
    api.getMealLog(daysBack).then(current(setFood)).catch(() => {});
  }, []);

  // After a write: ask again for what is on screen.
  const refresh = useCallback(() => fetchHistory(Math.max(loaded.current, need)), [fetchHistory, need]);

  const fetchInjuries = useCallback(() => {
    api.getInjuries().then(setInjuries).catch(() => {});
  }, []);

  useEffect(() => {
    fetchInjuries();
    api.getSettings().then(setSettings).catch(() => {});
  }, [fetchInjuries]);

  useEffect(() => {
    if (need > loaded.current) fetchHistory(need);
  }, [need, fetchHistory]);

  // The stress curve, on windows short enough to draw one. A longer window
  // clears it: a month of readings drawn across a year of axis would be a
  // smear at the right edge pretending to be a year of data. The guard drops
  // an answer for a window the user has already left.
  useEffect(() => {
    if (range.days == null || range.days > INTRADAY_MAX_DAYS) { setStress([]); setHeartRate([]); setSpo2([]); return undefined; }
    let live = true;
    api.getStressDetail({ after: start })
      .then(days => { if (live) setStress(days ?? []); })
      .catch(() => { if (live) setStress([]); });
    api.getHeartRateDetail({ after: start })
      .then(days => { if (live) setHeartRate(days ?? []); })
      .catch(() => { if (live) setHeartRate([]); });
    api.getSpo2Detail({ after: start })
      .then(days => { if (live) setSpo2(days ?? []); })
      .catch(() => { if (live) setSpo2([]); });
    return () => { live = false; };
  }, [range.days, start]);

  const days = useMemo(
    () => (start ? metrics.filter(m => m.date >= start) : metrics),
    [metrics, start],
  );
  // From every day, not the window: Auto reads the last four finished weeks
  // whatever range is on screen.
  const goals = useMemo(
    () => resolveGoals(autoGoals(goalWindow(metrics, todayIso())), settings),
    [metrics, settings],
  );
  const groups = useMemo(
    () => healthGroups({ days, imperial, start, stress, heartRate, spo2, food, goals }),
    [days, imperial, start, stress, heartRate, spo2, food, goals],
  );
  const nights = useMemo(() => sleepNights(days), [days]);
  // The watch's half of the page only exists with readings one left behind —
  // anywhere in the history, not just this window, so a quiet week shows
  // blank dials rather than hiding them. Without any, the page is the half
  // anyone can fill in.
  const showMeasured = useMemo(() => metrics.some(hasMeasurements), [metrics]);

  // ── Injury CRUD ──────────────────────────────────────────────────────────────

  async function handleCreateInjury(data) {
    setInjuryFormLoading(true);
    try {
      await api.createInjury(data);
      setShowInjuryForm(false);
      fetchInjuries();
    } catch { /* ignore */ }
    finally { setInjuryFormLoading(false); }
  }

  async function handleUpdateInjury(data) {
    setInjuryFormLoading(true);
    try {
      await api.updateInjury(editingInjury.id, data);
      setEditingInjury(null);
      fetchInjuries();
    } catch { /* ignore */ }
    finally { setInjuryFormLoading(false); }
  }

  async function handleDeleteInjury(id) {
    await api.deleteInjury(id).catch(() => {});
    setEditingInjury(null);
    fetchInjuries();
  }

  async function handleHealInjury(inj) {
    await api.updateInjury(inj.id, { end_date: isoToday() }).catch(() => {});
    fetchInjuries();
  }

  // Scroll highlighted injury card into view
  useEffect(() => {
    if (!highlightedInjuryId) return;
    const el = injuryRefs.current[highlightedInjuryId];
    if (el) el.scrollIntoView({ behavior: "smooth", block: "nearest" });
  }, [highlightedInjuryId]);

  // ── Render ───────────────────────────────────────────────────────────────────

  // The one button for everything a watch cannot know, inside the card whose
  // dials it fills in rather than floating loose on the page — upright down
  // its right edge, so the Body card is no taller than Activity's beside it
  // (see MetricGaugeGroup's aside).
  const logAside = (
    <button
      type="button"
      onClick={() => setLogOpen(true)}
      data-tour="health-log"
      // Stretched to the card's height by the flex row it sits in, never
      // `h-full`: text set vertically is an orthogonal flow, and a percentage
      // height on one resolves against the viewport — the button came out
      // 900px tall and took both cards with it. Only the label turns, reading
      // bottom to top as a book spine and a chart's y-axis title do.
      className="btn btn-tonal !h-auto self-stretch !px-2.5 !py-4 flex-col"
    >
      <PlusIcon />
      <span className="[writing-mode:vertical-rl] rotate-180">Log today</span>
    </button>
  );

  return (
    <div className="p-5 max-w-7xl mx-auto space-y-8">
      {/* The range as bar pills beside the title — the same control, in the
          same place, as the Dashboard's period and the phone's Health tab. */}
      <PageHeader title="Health">
        <BarPills
          dataTour="health-range"
          label="Range"
          options={RANGES.map(r => ({ value: r.key, label: r.label }))}
          value={rangeKey}
          onChange={setRangeKey}
        />
      </PageHeader>

      {showMeasured ? (
        <>
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
            <MetricGaugeGroup {...groups.activity} start={start} />
            <MetricGaugeGroup {...groups.body} start={start} aside={logAside} />
          </div>
          <MetricGaugeGroup {...groups.vitals} start={start} columns={6} />
          <SleepPanel nights={nights} start={start} />
        </>
      ) : (
        <MetricGaugeGroup {...groups.body} start={start} aside={logAside} />
      )}

      {/* Injuries */}
      <Section
        title="Injuries"
        action={
          !showInjuryForm && (
            <button
              onClick={() => { setShowInjuryForm(true); setEditingInjury(null); }}
              data-tour="health-injuries"
              className="btn btn-primary btn-sm"
            >
              <PlusIcon />Log injury
            </button>
          )
        }
      >
        {/* New injury form */}
        {showInjuryForm && (
          <Card className="mb-4">
            <p className="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-3">New injury</p>
            <InjuryForm
              onSave={handleCreateInjury}
              onCancel={() => setShowInjuryForm(false)}
              loading={injuryFormLoading}
            />
          </Card>
        )}

        {/* Timeline */}
        {injuries.length > 0 && (
          <InjuryTimeline
            injuries={injuries}
            selectedId={highlightedInjuryId}
            onSelect={setHighlightedInjuryId}
          />
        )}

        {/* Injury list */}
        {injuries.length === 0 && !showInjuryForm ? (
          <Card>
            <p className="text-sm text-slate-400 dark:text-slate-500 text-center py-3.5">No injuries logged</p>
          </Card>
        ) : (
          <div className="space-y-3">
            {injuries.map(inj => (
              <InjuryCard
                key={inj.id}
                injury={inj}
                editing={editingInjury?.id === inj.id}
                highlighted={highlightedInjuryId === inj.id}
                formLoading={injuryFormLoading}
                registerRef={el => { injuryRefs.current[inj.id] = el; }}
                onSelectActivities={() => setViewingInjury(inj)}
                onHeal={() => handleHealInjury(inj)}
                onEdit={() => { setEditingInjury(inj); setShowInjuryForm(false); }}
                onSave={handleUpdateInjury}
                onCancelEdit={() => setEditingInjury(null)}
                onDelete={() => handleDeleteInjury(inj.id)}
              />
            ))}
          </div>
        )}
      </Section>

      {/* Medications */}
      <Section title="Medications" dataTour="health-meds">
        <MedicationSection />
      </Section>

      {logOpen && (
        <LogTodayModal
          days={metrics}
          meals={meals}
          log={food}
          imperial={imperial}
          onChanged={refresh}
          onClose={() => setLogOpen(false)}
        />
      )}

      {/* Activities drawer */}
      {viewingInjury && (
        <InjuryActivitiesDrawer
          injury={viewingInjury}
          onClose={() => setViewingInjury(null)}
          imperial={imperial}
        />
      )}
    </div>
  );
}
