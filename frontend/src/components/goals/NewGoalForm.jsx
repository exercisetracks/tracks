// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The goal form, new or edit. Self-contained: owns the entire draft state,
// validation, and the save call. Renders different fields per goal_type
// (event / fitness / volume_target) and, for event goals, builds the
// training-plan payload (discipline, field-tests, strength integration).
//
// A new event starts on a recommended date (GET /coaching/goals/recommended-date,
// the same rule the phone runs offline), with its reasons behind the date's
// "?"; picking a date by hand ends that. An edit PATCHes only what changed, and
// the server rebuilds the plan when that leaves it stale — there is no
// Regenerate button.
//
// Props:
//   goal      – the goal being edited; absent for a new one
//   imperial  – units flag (affects distance entry + conversions)
//   onCancel  – called when the user dismisses the form
//   onSaved   – called after a successful save (+ best-effort plan generation)

import { useEffect, useState } from "react";
import { api } from "../../api/client";
import { experienceDefaultTier } from "../../lib/experienceLevels";
import { MTB_DISCIPLINES, CYCLING_DISCIPLINES } from "./constants";
import { changedFields, draftFromGoal, hasPlan, newDraft, pickPreset, pickSport, todayISO } from "./helpers";
import { InfoTooltip } from "../Charts/fitness/FitnessChartParts";
import UserIcsCopy from "../plancalendar/UserIcsCopy";
import { INPUT, LABEL } from "./ui";
import DatePicker from "../ui/DatePicker";
import {
  GoalTypeChooser,
  SportChooser,
  PresetChooser,
  IntensitySlider,
  StrengthSlider,
  RampSlider,
  DaysPerWeekPicker,
  DisciplineChooser,
  ExperienceChooser,
  SportVariantChooser,
  MultiSportChooser,
} from "./formControls";

export default function NewGoalForm({ goal, imperial, settings, onCancel, onSaved }) {
  const editing = !!goal;
  const [draft,   setDraft]   = useState(() => (goal ? draftFromGoal(goal, imperial) : newDraft()));
  const [saving,  setSaving]  = useState(false);
  const [error,   setError]   = useState(null);
  // Ask for strength experience only if the user has never answered it.
  const [experience, setExperience] = useState(null);
  const askExperience = !settings?.strength_experience;

  function set(key, val) { setDraft(d => ({ ...d, [key]: val })); }

  // A variant that is really a different plan (alpine) also turns on the
  // strength sessions that plan leans on; the toggle stays the user's to undo.
  function pickVariant(sport) {
    setDraft(d => ({ ...d, event_sport: sport,
                     include_strength: sport === "alpine_skiing" ? true : d.include_strength }));
  }

  // The recommended date follows the sport and distance while nobody has
  // picked one; its reasons stay behind the date's "?" either way.
  const isEvent = draft.goal_type === "event";
  useEffect(() => {
    if (!isEvent) return undefined;
    let live = true;
    const t = setTimeout(() => {
      api.recommendedEventDate(draft.event_sport, draft.event_distance_meters ?? undefined)
        .then(a => {
          if (!live || !a?.date) return;
          setDraft(d => (!editing && (!d.event_date || d._date_auto)
            ? { ...d, _date_advice: a, event_date: a.date, _date_auto: true }
            : { ...d, _date_advice: a }));
        })
        .catch(() => {});
    }, 250);
    return () => { live = false; clearTimeout(t); };
  }, [isEvent, draft.event_sport, draft.event_distance_meters, editing]);

  // The tier the strength experience suggests, for a slider nobody has moved.
  function defaultTier(isEndurance) {
    const effExperience = experience ?? settings?.strength_experience;
    const intensity = draft.plan_intensity ?? 1.0;
    const intensityTier = isEndurance
      ? (intensity >= 0.8 ? 2 : 1)
      : (intensity >= 1.4 ? 5 : intensity >= 1.2 ? 4 : intensity >= 0.8 ? 3 : intensity >= 0.5 ? 2 : 1);
    return experienceDefaultTier(effExperience, isEndurance) ?? intensityTier;
  }

  function setCustomDistance(str) {
    setDraft(d => {
      const num = parseFloat(str);
      if (isNaN(num) || num <= 0) {
        return { ...d, _custom_distance: str, event_distance_meters: null };
      }
      const meters = imperial ? num * 1609.34 : num * 1000;
      return { ...d, _custom_distance: str, event_distance_meters: meters, _preset: null };
    });
  }

  function validate() {
    if (draft.goal_type === "event") {
      if (!draft.event_name?.trim()) return "Event name is required";
      if (!draft.event_date)         return "Event date is required";
    }
    if (draft.goal_type === "volume_target") {
      if (!draft.target_weekly_km) return "Target weekly volume is required";
      if (!draft.volume_sport)     return "Sport is required";
    }
    return null;
  }

  async function save() {
    const err = validate();
    if (err) { setError(err); return; }
    setError(null);
    setSaving(true);

    const payload = {
      goal_type: draft.goal_type,
      notes: draft.notes?.trim() || null,
    };

    if (draft.goal_type === "event") {
      payload.event_name = draft.event_name.trim();
      payload.event_sport = draft.event_sport;
      payload.event_date = draft.event_date;
      payload.event_distance_meters = draft.event_distance_meters ?? null;
      payload.days_per_week = draft.days_per_week ?? 5;
      payload.plan_intensity = draft.plan_intensity ?? 1.0;
      if (draft.event_sport === "mountain biking") {
        payload.mtb_discipline = draft.mtb_discipline || "trail";
      }
      if (draft.event_sport === "cycling") {
        payload.cycling_discipline = draft.cycling_discipline || "road_race";
      }
      // Persist schedule_tests on the goal so both the background auto-refresh
      // and any subsequent /plan/generate call produce the same plan. Only
      // meaningful for cycling/MTB today — running/swim/tri ignore the flag.
      if (draft._schedule_tests
          && (draft.event_sport === "mountain biking" || draft.event_sport === "cycling")) {
        payload.schedule_tests = true;
      }
      // Strength training integration
      payload.include_strength = draft.event_sport === "strength_training" ? true : !!draft.include_strength;
      if (payload.include_strength) {
        // The slider's value; untouched, the tier the experience suggests
        // (endurance plans cap at 2, strength-only ones use the full 1-5).
        payload.strength_tier = draft.strength_tier ?? defaultTier(draft.event_sport !== "strength_training");
        if (draft.strength_days_per_week) {
          payload.strength_days_per_week = Number(draft.strength_days_per_week);
        }
      }
    } else if (draft.goal_type === "fitness") {
      // The sports go in fitness_sports; the first also in event_sport, the
      // column every other plan path (strength, stretch flows) reads.
      const sports = draft.fitness_sports?.length ? draft.fitness_sports : [draft.event_sport];
      payload.event_sport = sports[0];
      payload.fitness_sports = sports;
      payload.ctl_ramp_per_week = draft.ctl_ramp_per_week;
      payload.days_per_week = draft.days_per_week ?? 5;
      payload.include_strength = !!draft.include_strength;
      if (payload.include_strength) {
        payload.strength_tier = draft.strength_tier ?? defaultTier(true);
      }
    } else if (draft.goal_type === "volume_target") {
      const km = imperial ? Number(draft.target_weekly_km) * 1.609344 : Number(draft.target_weekly_km);
      payload.target_weekly_km = km;
      payload.volume_sport = draft.volume_sport;
    }

    try {
      // Persist the strength-experience answer before the goal so the plan
      // generation below picks it up. Best-effort — never block goal creation.
      if (payload.include_strength && askExperience && experience) {
        try { await api.updateSettings({ strength_experience: experience }); }
        catch { /* non-fatal */ }
      }
      if (editing) {
        // Only what changed: the server rebuilds the plan when that leaves
        // it stale, and an unchanged field sent anyway would count as a change.
        const changed = changedFields(payload, goal);
        if (Object.keys(changed).length) await api.updateGoal(goal.id, changed);
      } else {
        const created = await api.createGoal(payload);
        // Build the plan now for event and fitness goals
        if (hasPlan(created)) {
          try {
            await api.generatePlan(created.id);
          } catch {
            // Plan generation failure shouldn't block goal creation
          }
        }
      }
      onSaved();
    } catch (e) {
      setError(e.message || "Failed to save goal");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="space-y-6">
      {/* Goal type chooser — fixed once the goal exists, as on the phone */}
      {!editing && (
        <div>
          <p className={LABEL}>What kind of goal?</p>
          <GoalTypeChooser value={draft.goal_type} onChange={v => set("goal_type", v)} />
        </div>
      )}

      {/* Type-specific form */}
      {draft.goal_type === "event" && (
        <div className="space-y-5">
          <div>
            <p className={LABEL}>Sport</p>
            <SportChooser value={draft.event_sport} onChange={v => setDraft(d => pickSport(d, v))} />
            <div className="mt-2">
              <SportVariantChooser sport={draft.event_sport} onChange={pickVariant} />
            </div>
          </div>

          {draft.event_sport === "mountain biking" && (
            <div>
              <p className={LABEL}>MTB discipline</p>
              <DisciplineChooser
                options={MTB_DISCIPLINES}
                value={draft.mtb_discipline}
                onChange={v => set("mtb_discipline", v)}
              />
              <p className="text-xs text-slate-400 dark:text-slate-500 mt-1.5">
                Shapes periodization, workout selection, skills curriculum, and race-day HR ceiling.
              </p>
            </div>
          )}

          {draft.event_sport === "cycling" && (
            <div>
              <p className={LABEL}>Cycling discipline</p>
              <DisciplineChooser
                options={CYCLING_DISCIPLINES}
                value={draft.cycling_discipline}
                onChange={v => set("cycling_discipline", v)}
              />
              <p className="text-xs text-slate-400 dark:text-slate-500 mt-1.5">
                Drives periodization (Friel linear vs Carmichael compressed vs Rønnestad block), workout selection, and race-day strategy (drafting, fueling, W/kg).
              </p>
            </div>
          )}

          <div>
            <p className={LABEL}>Distance / event type</p>
            <PresetChooser sport={draft.event_sport} value={draft._preset} onChange={p => setDraft(d => pickPreset(d, p))} imperial={imperial} />
            <div className="mt-3 flex items-end gap-3">
              <div className="flex-1">
                <p className="text-xs text-slate-400 dark:text-slate-500 mb-1">…or a custom distance</p>
                <input className={INPUT} type="number" min="0" step="0.01"
                  value={draft._custom_distance}
                  onChange={e => setCustomDistance(e.target.value)}
                  placeholder={imperial ? "miles" : "kilometres"} />
              </div>
              <span className="text-xs text-slate-500 dark:text-slate-400 pb-2.5">
                {imperial ? "mi" : "km"}
              </span>
            </div>
          </div>

          <div className="grid sm:grid-cols-2 gap-4">
            <div>
              <p className={LABEL}>Event name</p>
              <input className={INPUT} type="text" value={draft.event_name}
                onChange={e => set("event_name", e.target.value)}
                placeholder="e.g. Boston Marathon" />
            </div>
            <div>
              <div className="flex items-center gap-2 mb-1.5">
                <p className={`${LABEL} mb-0`}>Event date</p>
                {draft._date_advice && (
                  <InfoTooltip>
                    {draft._date_advice.reasons.map(r => <p key={r}>{r}</p>)}
                    <p>Recommended: {draft._date_advice.date}. Change it to your event's real date.</p>
                  </InfoTooltip>
                )}
              </div>
              <DatePicker value={draft.event_date} min={todayISO()}
                onChange={v => setDraft(d => ({ ...d, event_date: v, _date_auto: false }))} />
            </div>
          </div>

          <div className="grid sm:grid-cols-2 gap-6">
            <div>
              <p className={LABEL}>Workout days per week</p>
              <DaysPerWeekPicker value={draft.days_per_week} onChange={v => set("days_per_week", v)} />
            </div>
            <div>
              <p className={LABEL}>Intensity</p>
              <IntensitySlider value={draft.plan_intensity} onChange={v => set("plan_intensity", v)} />
            </div>
          </div>

          {/* Field-test opt-in: currently MTB / cycling only */}
          {(draft.event_sport === "mountain biking" || draft.event_sport === "cycling") && (
            <label className="flex items-start gap-3 p-2.5 rounded-lg border border-slate-200 dark:border-slate-700 hover:border-accent-400 dark:hover:border-accent-500 cursor-pointer transition-colors">
              <input type="checkbox" className="mt-0.5"
                checked={!!draft._schedule_tests}
                onChange={e => set("_schedule_tests", e.target.checked)} />
              <span className="flex-1">
                <span className="block text-sm font-medium text-slate-700 dark:text-slate-200">
                  Schedule field tests
                </span>
                <span className="block text-xs text-slate-500 dark:text-slate-400 mt-0.5">
                  Inserts 3 test sessions (20-min FTP early, 5-min Pmax start-of-build, 20-min FTP retest mid-peak).
                  Completed tests will auto-update your FTP / LTHR.
                </span>
              </span>
            </label>
          )}

          {/* Strength training integration — endurance sports only */}
          {draft.event_sport !== "strength_training" && (
            <div className={`rounded-xl border-2 p-3.5 transition-all cursor-pointer ${
              draft.include_strength
                ? "border-accent-500 bg-accent-50/30 dark:bg-accent-900/10"
                : "border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 hover:border-slate-300 dark:hover:border-slate-600"
            }`} onClick={() => set("include_strength", !draft.include_strength)}>
              <div className="flex items-center gap-3">
                <div className="flex-1">
                  <div className="text-sm font-semibold text-slate-800 dark:text-slate-200">
                    Strength training
                  </div>
                  <div className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
                    {draft.include_strength
                      ? "Sport-specific sessions added to your plan. The intensity slider controls volume and focus."
                      : "Add strength sessions alongside your endurance plan."}
                  </div>
                </div>
                <div className={`w-5 h-5 rounded border-2 flex items-center justify-center shrink-0 transition-colors ${
                  draft.include_strength
                    ? "bg-accent-500 border-accent-500 text-white"
                    : "border-slate-300 dark:border-slate-600"
                }`}>
                  {draft.include_strength && (
                    <svg className="w-3 h-3" fill="none" stroke="currentColor" strokeWidth="3" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                    </svg>
                  )}
                </div>
              </div>
            </div>
          )}

          {draft.include_strength && draft.event_sport !== "strength_training" && (
            <div>
              <p className={LABEL}>Strength focus</p>
              <StrengthSlider value={draft.strength_tier ?? defaultTier(true)}
                onChange={v => set("strength_tier", v)} />
            </div>
          )}

          {/* One-time strength-experience question — shapes starting difficulty,
              volume and progression. Only asked if never answered before. */}
          {askExperience && (draft.include_strength || draft.event_sport === "strength_training") && (
            <div className="rounded-xl border-2 border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 p-3.5 space-y-3">
              <div>
                <div className="text-sm font-semibold text-slate-800 dark:text-slate-200">
                  How familiar are you with strength training?
                </div>
                <div className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
                  We'll tune the starting difficulty and progression. You can change this any time in Settings.
                </div>
              </div>
              <ExperienceChooser value={experience} onChange={setExperience} />
            </div>
          )}
        </div>
      )}

      {draft.goal_type === "fitness" && (
        <div className="space-y-5">
          <MultiSportChooser values={draft.fitness_sports ?? [draft.event_sport]}
            onChange={v => set("fitness_sports", v)} />
          <div className="grid sm:grid-cols-2 gap-6">
            <div>
              <p className={LABEL}>Fitness change</p>
              <RampSlider value={draft.ctl_ramp_per_week} onChange={v => set("ctl_ramp_per_week", v)} />
            </div>
            <div>
              <p className={LABEL}>Workout days per week</p>
              <DaysPerWeekPicker value={draft.days_per_week} onChange={v => set("days_per_week", v)} />
            </div>
          </div>
          <label className="flex items-center gap-3 cursor-pointer">
            <input type="checkbox" checked={!!draft.include_strength}
              onChange={e => set("include_strength", e.target.checked)} />
            <span className="text-sm text-slate-700 dark:text-slate-200">Include strength sessions</span>
          </label>
          {draft.include_strength && (
            <div>
              <p className={LABEL}>Strength focus</p>
              <StrengthSlider value={draft.strength_tier ?? defaultTier(true)}
                onChange={v => set("strength_tier", v)} />
            </div>
          )}
          {askExperience && draft.include_strength && (
            <ExperienceChooser value={experience} onChange={setExperience} />
          )}
        </div>
      )}

      {draft.goal_type === "volume_target" && (
        <div className="space-y-5">
          <div>
            <p className={LABEL}>Sport</p>
            <SportChooser value={draft.volume_sport} onChange={v => set("volume_sport", v)} />
          </div>
          <div className="grid sm:grid-cols-2 gap-4">
            <div>
              <p className={LABEL}>Target weekly distance ({imperial ? "mi" : "km"})</p>
              <input className={INPUT} type="number" min="0" step="0.1"
                value={draft.target_weekly_km ?? ""}
                onChange={e => set("target_weekly_km", e.target.value ? Number(e.target.value) : null)}
                placeholder={imperial ? "e.g. 30" : "e.g. 50"} />
            </div>
          </div>
        </div>
      )}

      <div>
        <p className={LABEL}>Notes (optional)</p>
        <textarea className={INPUT} rows={2} value={draft.notes}
          onChange={e => set("notes", e.target.value)}
          placeholder="Anything you want to remember about this goal…" />
      </div>

      {error && <p className="text-sm text-red-600 dark:text-red-400">{error}</p>}

      {/* The calendar feed is the plan's: only for a goal that exists. */}
      {editing && hasPlan(goal) && <UserIcsCopy />}

      <div className="flex items-center justify-end gap-3">
        <button onClick={onCancel} disabled={saving}
          className="btn btn-neutral">
          Cancel
        </button>
        <button onClick={save} disabled={saving}
          className="btn btn-primary">
          {saving ? "Saving…" : "Save goal"}
        </button>
      </div>
    </div>
  );
}
