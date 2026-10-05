// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Health page: a "Today" snapshot (last night's sleep + latest vitals) over a
// unified Trends explorer, then meals, medications, and injury tracking with a
// timeline. This file owns page-level data fetching and injury CRUD state; the
// presentational/chart pieces live in components/health/.

import { useEffect, useState, useCallback, useRef } from "react";
import { api } from "../api/client";
import MealSection from "../components/MealSection";
import MedicationSection from "../components/MedicationSection";

import { isoToday } from "../components/health/helpers";
import { Section, Card } from "../components/health/ui";
import TodayPanel from "../components/health/TodayPanel";
import TrendPanel from "../components/health/TrendPanel";
import InjuryTimeline from "../components/health/InjuryTimeline";
import InjuryForm from "../components/health/InjuryForm";
import InjuryCard from "../components/health/InjuryCard";
import InjuryActivitiesDrawer from "../components/health/InjuryActivitiesDrawer";
import { PlusIcon } from "../components/ui/Button";

export default function Health() {
  const [metrics,   setMetrics]   = useState([]);
  const [form,      setForm]      = useState([]);
  const [injuries,  setInjuries]  = useState([]);
  const [settings,  setSettings]  = useState(null);

  const [highlightedInjuryId, setHighlightedInjuryId] = useState(null);
  const [showInjuryForm,     setShowInjuryForm]     = useState(false);
  const [editingInjury,      setEditingInjury]      = useState(null);
  const [viewingInjury,      setViewingInjury]      = useState(null);
  const [injuryFormLoading,  setInjuryFormLoading]  = useState(false);
  const injuryRefs = useRef({});

  const imperial = settings?.units === "imperial";

  // Full history — the Trends chart offers a Lifetime range, so fetch everything
  // rather than a fixed recent window.
  const fetchMetrics = useCallback(() => {
    api.getHealthSummary(36500).then(setMetrics).catch(() => {});
  }, []);

  const fetchInjuries = useCallback(() => {
    api.getInjuries().then(setInjuries).catch(() => {});
  }, []);

  useEffect(() => {
    fetchMetrics();
    fetchInjuries();
    // Form (TSB) history, for overlaying against the health metrics.
    api.getTrainingLoad().then(setForm).catch(() => {});
    api.getSettings().then(setSettings).catch(() => {});
  }, [fetchMetrics, fetchInjuries]);

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

  return (
    <div className="p-5 max-w-7xl mx-auto space-y-8">
      <h1 className="text-xl font-bold text-slate-900 dark:text-white">Health</h1>

      {/* Today — last night's sleep + today's vitals, with inline logging */}
      <div data-tour="health-today">
        <TodayPanel metrics={metrics} imperial={imperial} onSaved={fetchMetrics} />
      </div>

      {/* Trends — multi-select metrics (incl. Form) over one chart */}
      <div data-tour="health-trends">
        <TrendPanel metrics={metrics} form={form} imperial={imperial} />
      </div>

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
