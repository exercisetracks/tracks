// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Goal card + all of its display/edit sub-pieces. A GoalCard renders one goal's
// title/subtitle, countdown, progress, and (for active goals) either the
// training-phase timeline or a coaching-impact note. Event cards also expose
// inline days/week and intensity editors that PATCH the goal directly.
//
// GoalCard props:
//   goal       – the goal record
//   ctl        – latest chronic-training-load value
//   weeklyKm   – this week's distance in km (for volume progress)
//   imperial   – units flag
//   onActivate – (goal) => toggle active
//   onDelete   – () => request delete confirmation
//   onReload   – () => refetch goals (used after inline edits)
//   settings   – user settings, for the goal form's strength defaults
//
// Edit opens the goal form in the card's place — the phone's goal sheet, with
// the calendar subscription link at its foot (it belongs to the goal's plan,
// and a goal not yet saved has none).

import { useEffect, useRef, useState, Fragment } from "react";
import { Link } from "react-router-dom";
import { api } from "../../api/client";
import { RAMP_MAX, RAMP_MIN, SPORT_LABEL, PHASES_CONFIG, PHASE_DOT_ACTIVE, PHASE_LABEL_ACTIVE, PHASE_DESC_COLOR } from "./constants";
import { fmtDistance, fmtDate, daysBetween, planPhaseInfo } from "./helpers";
import { LABEL, Pill } from "./ui";
import { IntensitySlider, RampSlider } from "./formControls";
import NewGoalForm from "./NewGoalForm";

// Colour-coded days-to-event chip.
function CountdownChip({ iso }) {
  const days = daysBetween(iso);
  if (days == null) return null;
  if (days < 0)  return <Pill tone="slate">Past · {Math.abs(days)} d ago</Pill>;
  if (days === 0) return <Pill tone="red">Today</Pill>;
  if (days <= 7)  return <Pill tone="red">{days} days</Pill>;
  if (days <= 30) return <Pill tone="amber">{days} days</Pill>;
  return <Pill tone="blue">{days} days</Pill>;
}

function ProgressBar({ value, max, color = "accent" }) {
  const pct = Math.max(0, Math.min(100, max ? (value / max) * 100 : 0));
  const colors = {
    accent: "bg-accent-500",
    blue:    "bg-blue-500",
    amber:   "bg-amber-500",
    red:     "bg-red-500",
  };
  return (
    <div className="h-2 bg-slate-100 dark:bg-slate-800 rounded-full overflow-hidden">
      <div className={`h-full ${colors[color]} transition-all`} style={{ width: `${pct}%` }} />
    </div>
  );
}

// Progress visual that varies by goal type (CTL target / weekly volume /
// maintain range). Returns null for event goals (which use the phase timeline).
function GoalProgress({ goal, ctl, weeklyKm, imperial }) {
  if (goal.goal_type === "volume_target" && goal.target_weekly_km) {
    const targetDisplay = imperial ? goal.target_weekly_km * 0.621371 : goal.target_weekly_km;
    const currentDisplay = imperial ? (weeklyKm ?? 0) * 0.621371 : (weeklyKm ?? 0);
    const unit = imperial ? "mi" : "km";
    return (
      <div className="mt-3">
        <div className="flex justify-between text-xs text-slate-500 dark:text-slate-400 mb-1">
          <span>This week · {currentDisplay.toFixed(1)} / {targetDisplay.toFixed(1)} {unit}</span>
          <span>{Math.round((currentDisplay / targetDisplay) * 100)}%</span>
        </div>
        <ProgressBar value={currentDisplay} max={targetDisplay} color="blue" />
      </div>
    );
  }

  return null;
}

// Base → Build → Peak → Taper → Race timeline for active event goals.
function TrainingPhaseTimeline({ goal }) {
  if (goal.goal_type !== "event" || !goal.event_date) return null;

  const { currentPhase: current } = planPhaseInfo(goal);
  const currentIdx = PHASES_CONFIG.findIndex(p => p.id === current);

  return (
    <div className="mt-3 pt-2.5 border-t border-slate-100 dark:border-slate-800">
      <p className={LABEL}>Training phase</p>
      <div className="flex items-start">
        {PHASES_CONFIG.map((phase, i) => {
          const isActive = phase.id === current;
          const isPast   = i < currentIdx;
          return (
            <Fragment key={phase.id}>
              <div className="flex flex-col items-center gap-1">
                <div className={`w-3 h-3 rounded-full border-2 ${
                  isActive ? PHASE_DOT_ACTIVE[phase.id]
                  : isPast ? "border-slate-300 dark:border-slate-600 bg-slate-200 dark:bg-slate-700"
                           : "border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800"
                }`} />
                <span className={`text-xs ${
                  isActive ? PHASE_LABEL_ACTIVE[phase.id]
                  : isPast ? "text-slate-400 dark:text-slate-500"
                           : "text-slate-300 dark:text-slate-600"
                }`}>{phase.label}</span>
              </div>
              {i < PHASES_CONFIG.length - 1 && (
                <div className={`flex-1 h-0.5 mt-1.5 mx-1 rounded-full ${
                  i < currentIdx ? "bg-slate-300 dark:bg-slate-600" : "bg-slate-100 dark:bg-slate-800"
                }`} />
              )}
            </Fragment>
          );
        })}
        <div className="flex flex-col items-center gap-1 ml-2 shrink-0">
          <span className="text-xs text-slate-400 dark:text-slate-500">Race</span>
        </div>
      </div>
      <p className="text-xs text-slate-500 dark:text-slate-400 mt-2 leading-snug">
        <span className={`font-medium ${PHASE_DESC_COLOR[current]}`}>{PHASES_CONFIG[currentIdx].label}:</span>{" "}
        {PHASES_CONFIG[currentIdx].desc}
      </p>
    </div>
  );
}

// Short "how coaching uses this goal" note for active non-event goals.
function CoachingImpactNote({ goal }) {
  if (!goal.is_active) return null;

  if (goal.goal_type === "volume_target" && goal.target_weekly_km) {
    return (
      <div className="mt-3 pt-2.5 border-t border-slate-100 dark:border-slate-800">
        <p className={LABEL}>Coaching impact</p>
        <p className="text-xs text-slate-500 dark:text-slate-400 leading-snug">
          Session durations boosted +10% to accumulate weekly volume. Intensity still follows readiness — volume comes from time, not harder efforts.
        </p>
      </div>
    );
  }

  return null;
}

// Inline days/week selector that PATCHes the goal (optimistic, reverts on error).
function DaysPerWeekEditor({ goal, onSaved }) {
  const [value,  setValue]  = useState(goal.days_per_week ?? 5);
  const [saving, setSaving] = useState(false);

  async function pick(n) {
    if (n === value || saving) return;
    setValue(n);
    setSaving(true);
    try {
      await api.updateGoal(goal.id, { days_per_week: n });
      onSaved();
    } catch {
      setValue(goal.days_per_week ?? 5);
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="flex items-center gap-2">
      <span className="text-xs text-slate-400 dark:text-slate-500">Workout days per week</span>
      <div className="flex gap-1">
        {[2, 3, 4, 5, 6, 7].map(n => (
          <button
            key={n}
            type="button"
            disabled={saving}
            onClick={() => pick(n)}
            className={`w-7 h-7 rounded-md text-xs font-semibold transition-colors disabled:opacity-40 ${
              value === n
                ? "bg-accent-500 text-white"
                : "border border-slate-300 dark:border-slate-700 text-slate-600 dark:text-slate-300 hover:border-accent-400 dark:hover:border-accent-500"
            }`}
          >
            {n}
          </button>
        ))}
      </div>
      {saving && <span className="text-xs text-slate-400 dark:text-slate-500 animate-pulse">Updating…</span>}
    </div>
  );
}

// Inline intensity slider that debounces a PATCH (600ms) and reverts on error.
function IntensityEditor({ goal, onSaved }) {
  const [value,  setValue]  = useState(goal.plan_intensity ?? 1.0);
  const [saving, setSaving] = useState(false);
  const timerRef            = useRef(null);

  function handleChange(v) {
    setValue(v);
    clearTimeout(timerRef.current);
    timerRef.current = setTimeout(async () => {
      setSaving(true);
      try {
        await api.updateGoal(goal.id, { plan_intensity: v });
        onSaved();
      } catch {
        setValue(goal.plan_intensity ?? 1.0);
      } finally {
        setSaving(false);
      }
    }, 600);
  }

  return (
    <div className="mt-2">
      <div className="flex items-center justify-between mb-1">
        <span className="text-xs text-slate-400 dark:text-slate-500">Intensity</span>
        {saving && <span className="text-xs text-slate-400 dark:text-slate-500 animate-pulse">Updating…</span>}
      </div>
      <IntensitySlider value={value} onChange={handleChange} />
    </div>
  );
}

// Inline fitness-ramp slider: debounced PATCH like IntensityEditor. The server
// rebuilds the plan on a ramp change, so a drag must not write every step.
// A goal saved before the slider stopped at +4 shows at the top, not off its end.
const clampRamp = v => Math.min(RAMP_MAX, Math.max(RAMP_MIN, v ?? 0));

function RampEditor({ goal, onSaved }) {
  const [value,  setValue]  = useState(clampRamp(goal.ctl_ramp_per_week));
  const [saving, setSaving] = useState(false);
  const timerRef            = useRef(null);

  function handleChange(v) {
    setValue(v);
    clearTimeout(timerRef.current);
    timerRef.current = setTimeout(async () => {
      setSaving(true);
      try {
        await api.updateGoal(goal.id, { ctl_ramp_per_week: v });
        onSaved();
      } catch {
        setValue(clampRamp(goal.ctl_ramp_per_week));
      } finally {
        setSaving(false);
      }
    }, 600);
  }

  return (
    <div className="mt-2">
      {saving && <span className="text-xs text-slate-400 dark:text-slate-500 animate-pulse">Updating…</span>}
      <RampSlider value={value} onChange={handleChange} />
    </div>
  );
}

// Lazily-fetched race-prediction badge (links to the race plan). Event goals
// with a known distance only.
function PredictedTimeBadge({ goal }) {
  const [data, setData] = useState(null);
  useEffect(() => {
    if (goal.goal_type !== "event" || !goal.event_distance_meters) return;
    api.getPredictedTime(goal.id).then(setData).catch(() => {});
  }, [goal.id, goal.event_distance_meters, goal.goal_type]);
  if (!data?.predicted_time) return null;
  return (
    <Link
      to={`/race-plans/${goal.id}`}
      className="inline-flex items-center gap-1.5 text-xs font-medium px-2 py-1 rounded-full bg-accent-100 text-accent-700 dark:bg-accent-900/30 dark:text-accent-300 hover:bg-accent-200 dark:hover:bg-accent-900/50 transition-colors"
    >
      <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
      </svg>
      Predicted {data.predicted_time}
    </Link>
  );
}

export default function GoalCard({ goal, ctl, weeklyKm, imperial, settings, onActivate, onDelete, onReload }) {
  const sport = goal.event_sport ?? goal.volume_sport ?? null;
  const [editing, setEditing] = useState(false);

  if (editing) {
    return (
      <div className="rounded-xl border-2 border-accent-400 dark:border-accent-600 p-3.5">
        <NewGoalForm goal={goal} imperial={imperial} settings={settings}
          onCancel={() => setEditing(false)}
          onSaved={() => { setEditing(false); onReload(); }} />
      </div>
    );
  }

  const title = (() => {
    if (goal.goal_type === "event")         return goal.event_name || "Race / Event";
    if (goal.goal_type === "fitness")       return "Fitness";
    if (goal.goal_type === "volume_target") {
      const v = imperial ? (goal.target_weekly_km * 0.621371).toFixed(1) : goal.target_weekly_km?.toFixed(1);
      return `${v} ${imperial ? "mi" : "km"} / week`;
    }
    return "Goal";
  })();

  const subtitle = (() => {
    if (goal.goal_type === "event") {
      const parts = [];
      if (goal.event_distance_meters) parts.push(fmtDistance(goal.event_distance_meters, imperial));
      if (sport)                       parts.push(SPORT_LABEL[sport] ?? sport);
      if (sport === "mountain biking" && goal.mtb_discipline) {
        parts.push(goal.mtb_discipline.toUpperCase());
      }
      if (sport === "cycling" && goal.cycling_discipline) {
        parts.push(goal.cycling_discipline.replace("_", " ").replace(/\b\w/g, c => c.toUpperCase()));
      }
      return parts.join(" · ");
    }
    if (goal.goal_type === "volume_target") return SPORT_LABEL[sport] ?? sport ?? "";
    if (goal.goal_type === "fitness")       return SPORT_LABEL[sport] ?? sport ?? "";
    return "";
  })();

  return (
    <div className={`rounded-xl border-2 p-3.5 transition-all ${
      goal.is_active
        ? "border-accent-400 dark:border-accent-600 bg-accent-50/40 dark:bg-accent-900/10"
        : "border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900"
    }`}>
      <div className="flex items-start justify-between gap-3">
        <div className="flex items-start gap-3 min-w-0 flex-1">
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-2 flex-wrap">
              <h3 className="text-base font-semibold text-slate-900 dark:text-white truncate">{title}</h3>
              {goal.is_active && <Pill tone="accent">Active</Pill>}
            </div>
            {subtitle && <p className="text-sm text-slate-500 dark:text-slate-400 mt-0.5">{subtitle}</p>}
            {goal.goal_type === "event" && (
              <div className="mt-1.5 space-y-2">
                <DaysPerWeekEditor goal={goal} onSaved={onReload} />
                <IntensityEditor goal={goal} onSaved={onReload} />
              </div>
            )}
            {goal.goal_type === "fitness" && (
              <div className="mt-1.5 space-y-2">
                <DaysPerWeekEditor goal={goal} onSaved={onReload} />
                <RampEditor goal={goal} onSaved={onReload} />
              </div>
            )}
            <div className="flex items-center gap-2 mt-1.5 flex-wrap">
              {goal.event_date && <span className="text-xs text-slate-500 dark:text-slate-400">{fmtDate(goal.event_date)}</span>}
              {goal.event_date && <CountdownChip iso={goal.event_date} />}
              {goal.goal_type === "event" && goal.event_distance_meters && (
                <PredictedTimeBadge goal={goal} />
              )}
            </div>
            {goal.notes && (
              <p className="text-xs text-slate-500 dark:text-slate-400 mt-2 italic">"{goal.notes}"</p>
            )}
            <GoalProgress goal={goal} ctl={ctl} weeklyKm={weeklyKm} imperial={imperial} />
            {goal.is_active && goal.goal_type === "event" && (
              <TrainingPhaseTimeline goal={goal} />
            )}
            {goal.is_active && goal.goal_type === "volume_target" && (
              <CoachingImpactNote goal={goal} />
            )}
          </div>
        </div>

        <div className="flex items-center gap-1 shrink-0">
          <button onClick={() => setEditing(true)} className="btn btn-tonal btn-sm">Edit</button>
          <button onClick={() => onActivate(goal)}
            title={goal.is_active ? "Deactivate this goal" : "Set as active goal"}
            className={`p-1 rounded-lg text-xs transition-colors ${
              goal.is_active
                ? "text-slate-400 dark:text-slate-500 hover:bg-slate-100 dark:hover:bg-slate-800"
                : "text-accent-600 dark:text-accent-400 hover:bg-accent-50 dark:hover:bg-accent-900/20"
            }`}>
            {goal.is_active
              ? <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" d="M10 9v6m4-6v6" /></svg>
              : <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" d="M14.752 11.168l-3.197-2.132A1 1 0 0010 9.87v4.263a1 1 0 001.555.832l3.197-2.132a1 1 0 000-1.664z" /></svg>}
          </button>
          <button onClick={() => onDelete(goal)}
            title="Delete this goal"
            className="p-1 rounded-lg text-xs text-slate-400 hover:text-red-600 dark:hover:text-red-400 hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors">
            <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6M1 7h22M9 7V4a2 2 0 012-2h2a2 2 0 012 2v3" />
            </svg>
          </button>
        </div>
      </div>
    </div>
  );
}
