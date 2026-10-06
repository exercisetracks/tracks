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
import MealSection from "../components/MealSection";
import MedicationSection from "../components/MedicationSection";

import { isoToday } from "../components/health/helpers";
import { Section, Card } from "../components/health/ui";
import MetricGaugeGroup from "../components/health/MetricGaugeGroup";
import SleepPanel from "../components/health/SleepPanel";
import ManualEntryPanel from "../components/health/ManualEntryPanel";
import { healthGroups, hasMeasurements } from "../components/health/metrics";
import { sleepNights } from "../components/health/sleepClock";
import { localIso } from "../components/health/scales";
import InjuryTimeline from "../components/health/InjuryTimeline";
import InjuryForm from "../components/health/InjuryForm";
import InjuryCard from "../components/health/InjuryCard";
import InjuryActivitiesDrawer from "../components/health/InjuryActivitiesDrawer";
import Tabs from "../components/ui/Tabs";
import { PlusIcon } from "../components/ui/Button";

/**
 * How far back every chart on the page looks. One window for the whole page,
 * in the header, because it governs every dial's history and the sleep card
 * alike — the same reason the phone puts it in its app bar.
 */
const RANGES = [
  { key: "7d",   label: "7 days",   days: 7 },
  { key: "30d",  label: "30 days",  days: 30 },
  { key: "1y",   label: "1 year",   days: 365 },
  { key: "life", label: "Lifetime", days: null },
];

/**
 * Past a month, the stress chart draws daily averages rather than every
 * reading — a legibility limit, not a fetch one: a year of three-minute samples
 * is a fifth of a pixel each. The server caps its own window on top of this.
 */
const INTRADAY_MAX_DAYS = 31;

function windowStart(range) {
  if (range.days == null) return null;
  const d = new Date();
  d.setDate(d.getDate() - range.days);
  return localIso(d);
}

export default function Health() {
  const [metrics,   setMetrics]   = useState([]);
  const [injuries,  setInjuries]  = useState([]);
  const [settings,  setSettings]  = useState(null);
  const [stress,    setStress]    = useState([]);
  const [rangeKey,  setRangeKey]  = useState("30d");
  const [logOpen,   setLogOpen]   = useState(false);

  const [highlightedInjuryId, setHighlightedInjuryId] = useState(null);
  const [showInjuryForm,     setShowInjuryForm]     = useState(false);
  const [editingInjury,      setEditingInjury]      = useState(null);
  const [viewingInjury,      setViewingInjury]      = useState(null);
  const [injuryFormLoading,  setInjuryFormLoading]  = useState(false);
  const injuryRefs = useRef({});

  const imperial = settings?.units === "imperial";
  const range = RANGES.find(r => r.key === rangeKey) ?? RANGES[1];
  const start = windowStart(range);

  // Full history — the window offers Lifetime, so fetch everything once and
  // filter on the way out rather than refetching on every range change.
  const fetchMetrics = useCallback(() => {
    api.getHealthSummary(36500).then(setMetrics).catch(() => {});
  }, []);

  const fetchInjuries = useCallback(() => {
    api.getInjuries().then(setInjuries).catch(() => {});
  }, []);

  useEffect(() => {
    fetchMetrics();
    fetchInjuries();
    api.getSettings().then(setSettings).catch(() => {});
  }, [fetchMetrics, fetchInjuries]);

  // The stress curve, on windows short enough to draw one. A longer window
  // clears it: a month of readings drawn across a year of axis would be a
  // smear at the right edge pretending to be a year of data. The guard drops
  // an answer for a window the user has already left.
  useEffect(() => {
    if (range.days == null || range.days > INTRADAY_MAX_DAYS) { setStress([]); return undefined; }
    let live = true;
    api.getStressDetail({ after: start })
      .then(days => { if (live) setStress(days ?? []); })
      .catch(() => { if (live) setStress([]); });
    return () => { live = false; };
  }, [range.days, start]);

  const days = useMemo(
    () => (start ? metrics.filter(m => m.date >= start) : metrics),
    [metrics, start],
  );
  const groups = useMemo(
    () => healthGroups({ days, imperial, start, stress }),
    [days, imperial, start, stress],
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
  // dials it fills in rather than floating loose on the page.
  const logFooter = (
    <>
      <button
        type="button"
        onClick={() => setLogOpen(o => !o)}
        data-tour="health-log"
        className="btn btn-tonal btn-sm w-full justify-center"
      >
        {logOpen ? "Close" : <><PlusIcon />Log weight, water or calories</>}
      </button>
      {logOpen && (
        <div className="mt-4 pt-4 border-t border-slate-100 dark:border-slate-800">
          <ManualEntryPanel today={isoToday()} imperial={imperial} onSaved={fetchMetrics} />
        </div>
      )}
    </>
  );

  return (
    <div className="p-5 max-w-7xl mx-auto space-y-8">
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <h1 className="text-xl font-bold text-slate-900 dark:text-white">Health</h1>
        <Tabs tabs={RANGES} value={rangeKey} onChange={setRangeKey} size="sm" dataTour="health-range" />
      </div>

      {showMeasured ? (
        <>
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
            <MetricGaugeGroup {...groups.activity} start={start} />
            <MetricGaugeGroup {...groups.body} start={start} footer={logFooter} />
          </div>
          <MetricGaugeGroup {...groups.vitals} start={start} columns={6} />
          <SleepPanel nights={nights} start={start} />
        </>
      ) : (
        <MetricGaugeGroup {...groups.body} start={start} footer={logFooter} />
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
      <Section title="Medications">
        <MedicationSection />
      </Section>

      {/* Nutrition & Meals */}
      <Section title="Nutrition">
        <MealSection />
      </Section>

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
