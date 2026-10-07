// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { todayDate } from "../lib/today";
import { workoutChip } from "../lib/workoutColors";

const PACE_LABELS = {
  easy: "Easy", z2: "Zone 2", marathon: "Marathon pace",
  threshold: "Threshold", tempo: "Tempo",
  interval: "Interval", vo2max: "VO₂max", race: "Race pace",
  repetition: "Rep pace",
};

function relDay(dateStr) {
  const today = todayDate();
  const d = new Date(dateStr + "T00:00:00");
  const diff = Math.round((d - today) / 86400000);
  if (diff === 0) return "Today";
  if (diff === 1) return "Tomorrow";
  if (diff <= 7) return d.toLocaleDateString(undefined, { weekday: "long" });
  return d.toLocaleDateString(undefined, { month: "short", day: "numeric" });
}

function fmtDur(min) {
  if (!min) return null;
  const h = Math.floor(min / 60);
  const m = min % 60;
  return h > 0 ? `${h}h ${m > 0 ? m + "m" : ""}`.trim() : `${m} min`;
}

function fmtDist(m, imperial = false) {
  if (!m) return null;
  if (imperial) {
    const miles = m / 1609.344;
    return miles >= 0.5 ? `${miles.toFixed(2)} mi` : `${Math.round(m * 1.09361)} yd`;
  }
  return m >= 1000 ? `${(m / 1000).toFixed(1)} km` : `${Math.round(m)} m`;
}

function stepDotClass(step) {
  if (step.type === "walk") return "bg-slate-300 dark:bg-slate-600";
  if (step.type === "warmup" || step.type === "cooldown") return "bg-slate-400 dark:bg-slate-500";
  if (step.type === "run") {
    const z = step.pace_zone ?? step.pace ?? "";
    if (z === "tempo" || z === "threshold") return "bg-amber-500";
    if (z === "interval" || z === "vo2max") return "bg-red-500";
    if (z === "race") return "bg-orange-500";
    return "bg-accent-400";
  }
  if (step.type === "interval_set") return "bg-red-500";
  if (step.type === "fartlek") return "bg-purple-500";
  return "bg-slate-400";
}

function stepLabel(step, imperial = false) {
  if (step.type === "walk") return "Walk";
  if (step.type === "warmup") return "Jog warm-up";
  if (step.type === "cooldown") return "Jog cool-down";
  if (step.type === "run") {
    const z = step.pace_zone ?? step.pace ?? "";
    return PACE_LABELS[z] ?? "Run";
  }
  if (step.type === "interval_set") {
    const pace = PACE_LABELS[step.pace] ?? "interval pace";
    const recLabel = step.rest_sec
      ? ` + ${step.rest_sec}s ${step.pace === "easy" ? "walk" : "rest"}`
      : "";
    if (step.distance_m) {
      const dist = imperial
        ? (step.distance_m >= 1600
            ? `${(step.distance_m / 1609.344).toFixed(2)} mi`
            : `${Math.round(step.distance_m * 1.09361)} yd`)
        : (step.distance_m >= 1000
            ? `${(step.distance_m / 1000).toFixed(1)} km`
            : `${step.distance_m} m`);
      return `${step.reps}× ${dist} @ ${pace}${recLabel}`;
    }
    if (step.duration_min_each != null) {
      const mins = step.duration_min_each;
      const durLabel = Number.isInteger(mins) ? `${mins} min` : `${Math.round(mins * 60)} sec`;
      return `${step.reps}× ${durLabel} @ ${pace}${recLabel}`;
    }
    // Legacy rep_steps format
    const rep = step.rep_steps?.[0];
    const rec = step.rep_steps?.[1];
    const repLabel = rep
      ? `${rep.duration_min} min @ ${PACE_LABELS[rep.pace_zone ?? rep.pace] ?? "interval"}`
      : "intervals";
    return `${step.reps} × ${repLabel}${rec ? ` + ${rec.duration_min} min recovery` : ""}`;
  }
  if (step.type === "fartlek") return "Fartlek";
  if (step.type === "strength_exercise") return step.name || "Exercise";
  if (step.type === "mobility_exercise") return step.name || "Stretch";
  return step.type;
}

function textClass(step) {
  if (step.type === "walk") return "text-slate-400 dark:text-slate-500";
  if (step.type === "warmup" || step.type === "cooldown") return "text-slate-500 dark:text-slate-400";
  return "text-slate-700 dark:text-slate-200";
}

function StepLine({ step, imperial = false }) {
  const isComplex = step.type === "interval_set" || step.type === "fartlek";
  return (
    <div className="flex items-start gap-2.5 py-1">
      <div className={`w-1.5 h-1.5 rounded-full shrink-0 mt-1.5 ${stepDotClass(step)}`} />
      <div className="min-w-0 flex-1">
        <span className={`text-xs font-medium ${textClass(step)}`}>{stepLabel(step, imperial)}</span>
        {!isComplex && step.duration_min && (
          <span className="text-xs text-slate-400 dark:text-slate-500 ml-1.5">{step.duration_min} min</span>
        )}
      </div>
    </div>
  );
}

function WorkoutDetail({ w, imperial = false }) {
  // One colour for both chips, the workout's own (lib/workoutColors.js): the
  // sport chip used to have a palette of three sports and the type chip one
  // of eight types, so most sessions got a grey type beside an accent sport.
  const badgeClass = workoutChip(w);
  const sportClass = badgeClass;
  const day = relDay(w.scheduled_date);
  const isToday = day === "Today";

  return (
    <div className="space-y-2.5">
      <div className="flex items-center gap-2 flex-wrap">
        <span className={`text-xs font-semibold px-1.5 py-0.5 rounded-full ${sportClass}`}>
          {w.sport}
        </span>
        <span className={`text-xs font-semibold px-1.5 py-0.5 rounded-full ${badgeClass}`}>
          {w.workout_type.replace(/_/g, " ")}
        </span>
        <span className={`text-xs font-medium ${isToday ? "text-accent-600 dark:text-accent-400" : "text-slate-400 dark:text-slate-500"}`}>
          {day}
        </span>
      </div>

      <p className="text-sm font-semibold text-slate-900 dark:text-white leading-snug">{w.title}</p>

      <div className="flex gap-3 text-xs text-slate-500 dark:text-slate-400">
        {w.duration_minutes && <span>{fmtDur(w.duration_minutes)}</span>}
        {w.distance_meters  && <span>{fmtDist(w.distance_meters, imperial)}</span>}
      </div>

      {w.description && (
        <p className="text-xs text-slate-500 dark:text-slate-400 leading-relaxed">{w.description}</p>
      )}

      {w.steps?.length > 0 && (
        <div className="border-t border-slate-100 dark:border-slate-800 pt-1.5 divide-y divide-slate-50 dark:divide-slate-800/60">
          {w.steps.map((s, i) => <StepLine key={i} step={s} imperial={imperial} />)}
        </div>
      )}
    </div>
  );
}


// The next few planned sessions, side by side: one alone said what today is
// but not what the week around it looks like. The columns fill at a minimum
// width rather than at a breakpoint, because this card shares its row with
// the gauge rail and its width depends on the sidebar as much as the window;
// on a narrow screen the later ones wrap underneath instead of squeezing.
const UPCOMING_SHOWN = 3;

export default function PlannedWorkoutCard({ workouts, imperial = false }) {
  const shown = workouts.slice(0, UPCOMING_SHOWN);

  return (
    <div
      className="card h-full min-w-0 grid gap-x-5 gap-y-4"
      style={{ gridTemplateColumns: "repeat(auto-fit, minmax(13rem, 1fr))" }}
    >
      {shown.map((w, i) => (
        <div
          key={w.id ?? i}
          className={`min-w-0 ${i > 0 ? "border-t pt-4 border-slate-100 dark:border-slate-800 sm:border-t-0 sm:pt-0 sm:border-l sm:pl-5" : ""}`}
        >
          <WorkoutDetail w={w} imperial={imperial} />
        </div>
      ))}
    </div>
  );
}
