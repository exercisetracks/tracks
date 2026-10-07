// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Pure (non-React) helpers for the Goals page: unit/date formatting, intensity
// colour mapping, the new-goal draft factory, and the training-phase maths that
// mirrors the backend. Kept framework-free so they're trivially testable.

import { EVENT_PRESETS, INTENSITY_STOPS, PRESET_NAMES, RAMP_RISK, presetName } from "./constants";
import { todayDate, todayIso } from "../../lib/today";

// ── Formatting helpers ────────────────────────────────────────────────────────

export function fmtDistance(meters, imperial) {
  if (meters == null) return null;
  if (imperial) {
    const mi = meters / 1609.34;
    if (mi >= 1)   return `${mi.toFixed(mi >= 100 ? 0 : 2)} mi`;
    return `${Math.round(meters * 1.09361)} yd`;
  }
  if (meters >= 1000) return `${(meters / 1000).toFixed(meters >= 100000 ? 0 : 2)} km`;
  return `${Math.round(meters)} m`;
}

export function fmtDate(iso) {
  if (!iso) return "—";
  return new Date(iso + "T00:00:00").toLocaleDateString(undefined, {
    weekday: "short", month: "short", day: "numeric", year: "numeric",
  });
}

export function daysBetween(iso) {
  if (!iso) return null;
  const target = new Date(iso + "T00:00:00").getTime();
  const today  = todayDate().getTime();
  return Math.round((target - today) / 86400000);
}

export function todayISO() {
  return todayIso();
}

// ── Intensity slider colour/label ─────────────────────────────────────────────

export function intensityColor(v) {
  const clamped = Math.max(0.5, Math.min(1.5, v));
  if (clamped <= 0.5)  return "#60a5fa";
  if (clamped <= 0.75) {
    const t = (clamped - 0.5) / 0.25;
    return `hsl(${157 + (140 - 157) * t}, ${70 + 10 * t}%, ${55 + 2 * t}%)`;
  }
  if (clamped <= 1.0)  return "#10b981";
  if (clamped <= 1.25) {
    const t = (clamped - 1.0) / 0.25;
    return `hsl(${160 - 119 * t}, 85%, ${55 - 15 * t}%)`;
  }
  const t = (clamped - 1.25) / 0.25;
  return `hsl(${41 - 41 * t}, ${80 + 5 * t}%, ${50 - 2 * t}%)`;
}

export function intensityLabel(v) {
  const stop = INTENSITY_STOPS.reduce((best, s) =>
    Math.abs(s.value - v) < Math.abs(best.value - v) ? s : best
  );
  return stop.label;
}

// ── Fitness goal ramp ─────────────────────────────────────────────────────────

// "+3 CTL / week", "−1.5 CTL / week", "0 CTL / week".
export function rampLabel(v) {
  const n = Math.abs(v);
  const sign = v > 0 ? "+" : v < 0 ? "−" : "";
  return `${sign}${Number.isInteger(n) ? n : n.toFixed(1)} CTL / week`;
}

// The word for where a ramp sits — the phone's GoalOptions.rampWord.
export function rampWord(v) {
  if (v < 0) return "Detraining";
  if (v < 1) return "Maintain";
  if (v <= RAMP_RISK) return "Build";
  return "Aggressive";
}

// The band a ramp falls in, coloured as the slider's track shows it.
export function rampColor(v) {
  if (v > RAMP_RISK) return "#ef4444";
  if (v < 0) return "#60a5fa";
  if (v < 1) return "#10b981";
  return "#f59e0b";
}

// Goals a training plan is built for: future events, and fitness goals
// (rolling, so always). A weekly-volume goal is a number to hit, not a plan.
export function hasPlan(goal) {
  return goal.goal_type === "fitness" || (goal.goal_type === "event" && !!goal.event_date);
}

// A strength tier's colour: its place on the intensity slider's track
// (1 at Easy, 5 at Max), as the phone's strengthColor.
export function strengthColor(tier) {
  return intensityColor(0.5 + (Math.min(5, Math.max(1, tier)) - 1) * 0.25);
}

// ── Event presets ─────────────────────────────────────────────────────────────

// A name nobody typed: blank, or still exactly what a preset filled in.
const nameIsAuto = name => !name?.trim() || PRESET_NAMES.has(name);

// A preset picked: its distance and its name, both. The name used to be
// filled only while blank, so "10 Mile" then "10K" left a 10K race called
// "10 Mile"; a name the person typed is still kept. A preset with no
// distance clears one the previous preset filled, and keeps one typed.
// The phone's GoalDraft.pickPreset.
export function pickPreset(d, p) {
  return {
    ...d,
    // An open-water race is an open-water plan.
    event_sport: p.id?.startsWith("ow-") ? "open_water_swimming" : d.event_sport,
    _preset: p,
    _custom_distance: "",
    event_distance_meters: p.distance ?? (d._preset ? null : d.event_distance_meters),
    event_name: nameIsAuto(d.event_name) ? (presetName(p) ?? "") : d.event_name,
  };
}

// Another sport: its presets differ, so what the last one filled in goes too.
export function pickSport(d, sport) {
  return {
    ...d,
    event_sport: sport,
    _preset: null,
    _custom_distance: "",
    event_distance_meters: null,
    event_name: nameIsAuto(d.event_name) ? "" : d.event_name,
  };
}

// ── New-goal draft factory ────────────────────────────────────────────────────

export function newDraft() {
  return {
    goal_type: "event",
    event_name: "",
    event_sport: "running",
    event_date: "",
    event_distance_meters: null,
    ctl_ramp_per_week: 2,        // fitness: a steady build nobody gets hurt on
    fitness_sports: ["running"], // fitness: the sports it trains, in pick order
    target_weekly_km: null,
    volume_sport: "running",
    days_per_week: 5,
    plan_intensity: 1.0,
    mtb_discipline: "trail",         // only used when event_sport === "mountain biking"
    cycling_discipline: "road_race", // only used when event_sport is a road-cycling family
    notes: "",
    _preset: null,             // local: which preset is selected
    _custom_distance: "",      // local: custom-distance string for events
    _schedule_tests: false,    // local: opt-in to field-test sessions
    include_strength: false,
    // null until the slider moves: the form shows (and saves) the tier the
    // strength experience suggests. A 3 here made that default dead code.
    strength_tier: null,
    strength_days_per_week: null,
    _date_auto: false,         // local: event_date is still the recommended one
    _date_advice: null,        // local: the recommendation, for the date's "?"
  };
}

// The form's draft for a goal being edited: its own values, in the form's shape.
export function draftFromGoal(goal, imperial) {
  const d = newDraft();
  const sport = goal.event_sport ?? d.event_sport;
  const own = Object.keys(d).filter(k => !k.startsWith("_") && goal[k] != null);
  return {
    ...d,
    ...Object.fromEntries(own.map(k => [k, goal[k]])),
    event_name: goal.event_name ?? "",
    notes: goal.notes ?? "",
    target_weekly_km: goal.target_weekly_km != null && imperial
      ? +(goal.target_weekly_km / 1.609344).toFixed(1) : goal.target_weekly_km,
    _preset: (EVENT_PRESETS[sport] ?? []).find(p => p.distance != null && p.distance === goal.event_distance_meters) ?? null,
    _schedule_tests: !!goal.schedule_tests,
    // A goal from before fitness_sports trains its one sport.
    fitness_sports: goal.fitness_sports?.length ? goal.fitness_sports : [sport],
  };
}

// Only the fields that differ from the goal as saved — an unchanged field is
// not re-sent, so it neither rebuilds the plan nor beats an edit made elsewhere.
// Lists (fitness_sports) compare by content: a fresh array is never === the
// saved one, and would rebuild the plan on every save.
export function changedFields(payload, goal) {
  const same = (a, b) => (Array.isArray(a) || Array.isArray(b))
    ? JSON.stringify(a ?? null) === JSON.stringify(b ?? null)
    : (a ?? null) === (b ?? null);
  return Object.fromEntries(Object.entries(payload).filter(([k, v]) => !same(goal[k], v)));
}

// ── Training-phase maths (mirrors backend) ────────────────────────────────────

// Mirror of the backend's _phase_for_week — phase by relative plan position.
function phaseForWeek(weekNum, totalWeeks) {
  if (totalWeeks <= 1) return "taper";
  const taperW = Math.min(3, Math.max(1, Math.floor(totalWeeks / 5)));
  const peakW  = totalWeeks > 6 ? Math.min(4, Math.max(1, Math.floor((totalWeeks - taperW) / 4))) : 0;
  const buildW = totalWeeks > 4 ? Math.min(5, Math.max(1, Math.floor((totalWeeks - taperW - peakW) / 3))) : 0;
  const baseW  = Math.max(1, totalWeeks - taperW - peakW - buildW);
  if (weekNum < baseW)              return "base";
  if (weekNum < baseW + buildW)     return "build";
  if (weekNum < baseW + buildW + peakW) return "peak";
  return "taper";
}

// Returns current phase and per-phase week counts for a goal.
// Uses created_at as the plan start so short plans always open with base.
export function planPhaseInfo(goal) {
  const today       = todayDate().getTime();
  const raceMs      = new Date(goal.event_date + "T00:00:00").getTime();
  const planStartMs = goal.created_at ? new Date(goal.created_at).getTime() : today;
  const totalDays   = Math.max(1, Math.round((raceMs - planStartMs) / 86400000));
  const totalWeeks  = Math.max(1, Math.ceil(totalDays / 7));
  const weekNum     = Math.floor(Math.max(0, today - planStartMs) / (7 * 86400000));
  const taperW = Math.min(3, Math.max(1, Math.floor(totalWeeks / 5)));
  const peakW  = totalWeeks > 6 ? Math.min(4, Math.max(1, Math.floor((totalWeeks - taperW) / 4))) : 0;
  const buildW = totalWeeks > 4 ? Math.min(5, Math.max(1, Math.floor((totalWeeks - taperW - peakW) / 3))) : 0;
  const baseW  = Math.max(1, totalWeeks - taperW - peakW - buildW);
  return {
    currentPhase: phaseForWeek(weekNum, totalWeeks),
    phaseWeeks: { base: baseW, build: buildW, peak: peakW, taper: taperW },
  };
}
