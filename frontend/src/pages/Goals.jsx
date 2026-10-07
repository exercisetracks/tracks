// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Training/Goals page. Orchestrates the user's goals: loads them (plus the
// supporting CTL + weekly-volume context), shows the new-goal form, and renders
// the active and past goal cards. One goal is "active" at a time; activating an
// event goal drives the full training plan rendered by PlanCalendarSection.
//
// The bulk of the UI lives in components/goals/* — this file is the page shell
// + data/state wiring.

import { useEffect, useMemo, useState, Fragment } from "react";
import { api } from "../api/client";
import PlanCalendarSection from "../components/PlanCalendarSection";
import { GOAL_TYPES } from "../components/goals/constants";
import { hasPlan } from "../components/goals/helpers";
import { Section, Card } from "../components/ui/Section";
import PageHeader from "../components/ui/PageHeader";
import { PlusIcon } from "../components/ui/Button";
import NewGoalForm from "../components/goals/NewGoalForm";
import ExperienceSuggestionBanner from "../components/ExperienceSuggestionBanner";
import GoalCard from "../components/goals/GoalCard";
import ConfirmDialog from "../components/ConfirmDialog";
import { isoOfDay, todayDate } from "../lib/today";

export default function Goals() {
  const [goals,           setGoals]           = useState([]);
  const [settings,        setSettings]        = useState(null);
  const [ctl,             setCtl]             = useState(null);
  const [weeklyKm,        setWeeklyKm]        = useState(null);
  const [loading,         setLoading]         = useState(true);
  const [showForm,        setShowForm]        = useState(false);
  const [confirmDelete,   setConfirmDelete]   = useState(null);
  const [planRefreshKey,  setPlanRefreshKey]  = useState(0);

  const imperial = settings?.units === "imperial";

  async function loadGoals(bumpPlan = false) {
    try { setGoals(await api.getGoals()); } catch { setGoals([]); }
    if (bumpPlan) setPlanRefreshKey(k => k + 1);
  }

  async function refreshSettings() {
    try { setSettings(await api.getSettings()); } catch { /* ignore */ }
    setPlanRefreshKey(k => k + 1);
  }

  useEffect(() => {
    Promise.all([
      api.getGoals().catch(() => []),
      api.getSettings().catch(() => null),
      api.getTrainingLoad().catch(() => []),
      // 7-day weekly summary
      (() => {
        const today = todayDate();
        const start = new Date(today);
        start.setDate(today.getDate() - 6);
        const iso = isoOfDay;
        return api.getSummary({ after: iso(start), before: iso(today) }).catch(() => null);
      })(),
    ]).then(([g, s, tload, summary]) => {
      setGoals(g);
      setSettings(s);
      // Latest CTL point
      if (tload?.length) setCtl(tload[tload.length - 1].ctl);
      // Weekly km
      if (summary?.total_distance_km != null) setWeeklyKm(summary.total_distance_km);
    }).finally(() => setLoading(false));
  }, []);

  async function toggleActive(goal) {
    try {
      await api.updateGoal(goal.id, { is_active: !goal.is_active });
      await loadGoals();
    } catch {}
  }

  async function performDelete() {
    if (!confirmDelete) return;
    try {
      await api.deleteGoal(confirmDelete.id);
      await loadGoals();
    } catch {}
    setConfirmDelete(null);
  }

  const activeGoals = useMemo(() => goals.filter(g => g.is_active), [goals]);
  const pastGoals   = useMemo(() => goals.filter(g => !g.is_active), [goals]);

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64 text-sm text-slate-400 dark:text-slate-500">
        Loading…
      </div>
    );
  }

  return (
    <div className="p-5 max-w-7xl mx-auto space-y-8">

      <PageHeader
        title="Training"
        subtitle="One active goal at a time. When a Race/Event goal is active, coaching builds a full training plan through Base, Build, Peak, and Taper phases."
      >
        {!showForm && (
          <button onClick={() => setShowForm(true)} data-tour="goals-new" className="btn btn-primary">
            <PlusIcon />New goal
          </button>
        )}
      </PageHeader>

      {/* Coach note: history-derived experience-level suggestion */}
      <ExperienceSuggestionBanner settings={settings} onChanged={refreshSettings} />

      {/* New goal form */}
      {showForm && (
        <Section title="New goal">
          <Card>
            <NewGoalForm
              imperial={imperial}
              settings={settings}
              onCancel={() => setShowForm(false)}
              onSaved={async () => { setShowForm(false); await loadGoals(); }}
            />
          </Card>
        </Section>
      )}

      {/* Active goal */}
      <div data-tour="goals-active">
      <Section title="Active Goal">
        {activeGoals.length === 0 ? (
          <Card className="flex flex-col items-center gap-3 py-7 text-sm text-slate-400 dark:text-slate-500">
            No active goals yet.
            {!showForm && (
              <button onClick={() => setShowForm(true)} className="btn btn-tonal">
                <PlusIcon />Create one
              </button>
            )}
          </Card>
        ) : (
          <div className="space-y-3">
            {activeGoals.map(g => (
              <Fragment key={g.id}>
                <GoalCard goal={g} ctl={ctl} weeklyKm={weeklyKm} imperial={imperial} settings={settings}
                  onActivate={toggleActive}
                  onDelete={() => setConfirmDelete(g)}
                  onReload={() => loadGoals(true)} />
                {hasPlan(g) && (
                  <PlanCalendarSection goalId={g.id} refreshKey={planRefreshKey} imperial={imperial} />
                )}
              </Fragment>
            ))}
          </div>
        )}
      </Section>
      </div>

      {/* Past goals */}
      {pastGoals.length > 0 && (
        <Section title={`Past · ${pastGoals.length}`}>
          <div className="space-y-3">
            {pastGoals.map(g => (
              <GoalCard key={g.id} goal={g} ctl={ctl} weeklyKm={weeklyKm} imperial={imperial} settings={settings}
                onActivate={toggleActive}
                onDelete={() => setConfirmDelete(g)}
                onReload={loadGoals} />
            ))}
          </div>
        </Section>
      )}

      {confirmDelete && (
        <ConfirmDialog
          confirmLabel="Delete"
          title="Delete goal?"
          message={`"${confirmDelete.event_name || GOAL_TYPES.find(t => t.id === confirmDelete.goal_type)?.title || "this goal"}" will be permanently removed.`}
          danger
          onConfirm={performDelete}
          onCancel={() => setConfirmDelete(null)}
        />
      )}
    </div>
  );
}
