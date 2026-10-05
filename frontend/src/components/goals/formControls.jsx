// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Presentational form controls for the new-goal form. Each is a pure,
// controlled component: it takes a `value` + `onChange` (plus a little config)
// and renders selection UI. No internal state, no data fetching.

import {
  EVENT_PRESETS,
  SPORT_LABEL,
  GOAL_TYPES,
  INTENSITY_STOPS,
  INTENSITY_INFO,
  STRENGTH_FOCUS,
  STRENGTH_FOCUS_INFO,
  FITNESS_RAMP_INFO,
  FITNESS_SPORTS,
  FITNESS_SPORTS_INFO,
  RAMP_MIN,
  RAMP_MAX,
  RAMP_STEP,
  RAMP_RISK,
  SPORT_VARIANTS,
  sportChip,
} from "./constants";
import { InfoTooltip } from "../Charts/fitness/FitnessChartParts";
import { EXPERIENCE_OPTIONS } from "../../lib/experienceLevels";
import { fmtDistance, intensityColor, intensityLabel, rampColor, rampLabel, rampWord, strengthColor } from "./helpers";

// Strength-training experience picker (brand new → advanced). `value` is the
// selected level key; `onChange` receives the key.
export function ExperienceChooser({ value, onChange }) {
  return (
    <div className="grid sm:grid-cols-2 gap-2">
      {EXPERIENCE_OPTIONS.map(o => (
        <button key={o.value} type="button" onClick={() => onChange(o.value)}
          className={`text-left px-2.5 py-1.5 rounded-lg border text-sm transition-colors ${
            value === o.value
              ? "bg-accent-500 border-accent-500 text-white"
              : "border-slate-300 dark:border-slate-700 hover:border-accent-400 dark:hover:border-accent-500 text-slate-700 dark:text-slate-200"
          }`}>
          <div className="font-semibold">{o.label}</div>
          <div className={`text-xs ${value === o.value ? "text-white/80" : "text-slate-500 dark:text-slate-400"}`}>{o.blurb}</div>
        </button>
      ))}
    </div>
  );
}

// Grid of goal-type cards (Race/Event, Build Fitness, Weekly Volume, Maintain).
export function GoalTypeChooser({ value, onChange }) {
  return (
    <div className="grid grid-cols-2 lg:grid-cols-3 gap-3">
      {GOAL_TYPES.map(g => (
        <button key={g.id} type="button" onClick={() => onChange(g.id)}
          className={`text-left rounded-xl border-2 p-2.5 transition-colors ${
            value === g.id
              ? "border-accent-500 bg-accent-50 dark:bg-accent-900/20"
              : "border-slate-200 dark:border-slate-700 hover:border-slate-300 dark:hover:border-slate-600"
          }`}>
          <div className={`mb-2 inline-flex p-1 rounded-lg ${
            value === g.id ? "text-accent-600 dark:text-accent-400" : "text-slate-500 dark:text-slate-400"
          }`}>
            {g.icon}
          </div>
          <p className={`text-sm font-semibold ${
            value === g.id ? "text-accent-700 dark:text-accent-300" : "text-slate-700 dark:text-slate-200"
          }`}>{g.title}</p>
          <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5 leading-snug">{g.description}</p>
        </button>
      ))}
    </div>
  );
}

// Pill row of sports (keys of EVENT_PRESETS).
export function SportChooser({ value, onChange }) {
  return (
    <div className="flex flex-wrap gap-2">
      {Object.keys(EVENT_PRESETS).map(s => (
        <button key={s} type="button" onClick={() => onChange(s)}
          className={`flex items-center gap-1.5 px-2.5 py-1 rounded-full border text-sm transition-colors ${
            sportChip(value) === s
              ? "bg-accent-500 border-accent-500 text-white"
              : "border-slate-300 dark:border-slate-700 text-slate-600 dark:text-slate-300 hover:border-accent-400 dark:hover:border-accent-500"
          }`}>
          <span>{SPORT_LABEL[s]}</span>
        </button>
      ))}
    </div>
  );
}

// Which kind of swimming or skiing, for the sports that have kinds
// (SPORT_VARIANTS). Writes the specific sport; the explanation is behind "?".
export function SportVariantChooser({ sport, onChange }) {
  const variants = SPORT_VARIANTS[sportChip(sport)];
  if (!variants) return null;
  const selected = variants.options.some(o => o.key === sport) ? sport : variants.options[0].key;
  return (
    <div className="flex items-center gap-2 flex-wrap">
      {variants.options.map(o => (
        <button key={o.key} type="button" onClick={() => onChange(o.key)}
          className={`px-2.5 py-1 rounded-full border text-sm transition-colors ${
            selected === o.key
              ? "bg-accent-500 border-accent-500 text-white"
              : "border-slate-300 dark:border-slate-700 text-slate-600 dark:text-slate-300 hover:border-accent-400 dark:hover:border-accent-500"
          }`}>
          {o.label}
        </button>
      ))}
      <InfoTooltip>
        {variants.info.map(p => <p key={p}>{p}</p>)}
      </InfoTooltip>
    </div>
  );
}

// A fitness goal's sports: pills that each toggle on their own. `values` is
// the picked sport keys in pick order; the last cannot be unpicked (a goal
// with no sport has nothing to plan), and a second kind of a sport already
// picked (open water after pool) replaces it, since the planner trains one of
// each. Explanation behind the "?" — the phone's Explain.FitnessSports.
export function MultiSportChooser({ values, onChange }) {
  const toggle = key => {
    if (values.includes(key)) {
      if (values.length > 1) onChange(values.filter(v => v !== key));
      return;
    }
    const family = FITNESS_SPORTS.find(s => s.key === key)?.family;
    const i = values.findIndex(v => FITNESS_SPORTS.find(s => s.key === v)?.family === family);
    onChange(i >= 0 ? values.map((v, j) => (j === i ? key : v)) : [...values, key]);
  };
  return (
    <div className="space-y-1.5">
      <div className="flex items-center gap-2">
        <span className="text-xs font-semibold text-slate-600 dark:text-slate-300">Sports</span>
        <InfoTooltip>{FITNESS_SPORTS_INFO.map(p => <p key={p}>{p}</p>)}</InfoTooltip>
      </div>
      <div className="flex flex-wrap gap-2">
        {FITNESS_SPORTS.map(({ key, label }) => {
          const on = values.includes(key);
          return (
            <button key={key} type="button" aria-pressed={on} onClick={() => toggle(key)}
              className={`flex items-center gap-1.5 px-2.5 py-1 rounded-full border text-sm transition-colors ${
                on
                  ? "bg-accent-500 border-accent-500 text-white"
                  : "border-slate-300 dark:border-slate-700 text-slate-600 dark:text-slate-300 hover:border-accent-400 dark:hover:border-accent-500"
              }`}>
              <span>{label}</span>
            </button>
          );
        })}
      </div>
    </div>
  );
}

// Distance/event-type presets for the chosen sport. `value` is the selected
// preset object; `onChange` receives the whole preset.
export function PresetChooser({ sport, value, onChange, imperial }) {
  const presets = EVENT_PRESETS[sportChip(sport)] ?? [];
  return (
    <div className="flex flex-wrap gap-2">
      {presets.map(p => (
        <button key={p.id} type="button" onClick={() => onChange(p)}
          className={`text-left px-2.5 py-1.5 rounded-lg border transition-colors ${
            value?.id === p.id
              ? "bg-accent-500 border-accent-500 text-white"
              : "border-slate-300 dark:border-slate-700 hover:border-accent-400 dark:hover:border-accent-500 text-slate-700 dark:text-slate-200"
          }`}>
          <p className="text-sm font-medium">{p.label}</p>
          {p.distance != null && (
            <p className={`text-xs mt-0.5 ${value?.id === p.id ? "text-accent-100" : "text-slate-500 dark:text-slate-400"}`}>
              {fmtDistance(p.distance, imperial)}
            </p>
          )}
        </button>
      ))}
    </div>
  );
}

// Gradient intensity slider (0.5×–1.5× session-duration multiplier).
export function IntensitySlider({ value, onChange }) {
  const pct = ((value - 0.5) / 1.0) * 100;
  const color = intensityColor(value);
  return (
    <div className="space-y-1.5">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="text-xs font-semibold" style={{ color }}>{intensityLabel(value)}</span>
          <span className="text-xs text-slate-400 dark:text-slate-500">({value.toFixed(2)}×)</span>
          <InfoTooltip>
            {INTENSITY_INFO.map(p => <p key={p}>{p}</p>)}
          </InfoTooltip>
        </div>
      </div>
      <div className="relative flex items-center h-5">
        <div className="absolute inset-x-0 h-2 rounded-full overflow-hidden"
          style={{ background: "linear-gradient(to right, #60a5fa, #34d399, #10b981, #f59e0b, #ef4444)" }}>
          <div className="h-full rounded-full" style={{ width: `${pct}%`, background: "transparent" }} />
        </div>
        <input
          type="range"
          min={0.5}
          max={1.5}
          step={0.05}
          value={value}
          onChange={e => onChange(parseFloat(e.target.value))}
          className="relative w-full h-2 appearance-none bg-transparent cursor-pointer"
          style={{ "--thumb-color": color }}
        />
      </div>
      <div className="flex justify-between text-[10px] text-slate-400 dark:text-slate-500">
        {INTENSITY_STOPS.map(s => <span key={s.value}>{s.label}</span>)}
      </div>
    </div>
  );
}

// The strength-focus slider (strength_tier 1–5), drawn as IntensitySlider is;
// the phone's StrengthFocusSlider is its twin. It replaced numbered buttons
// whose numbers meant nothing until a label was read.
export function StrengthSlider({ value, onChange }) {
  const color = strengthColor(value);
  const label = STRENGTH_FOCUS.find(s => s.value === value)?.label ?? "Balanced";
  return (
    <div className="space-y-1.5">
      <div className="flex items-center gap-2">
        <span className="text-xs font-semibold" style={{ color }}>{label}</span>
        <InfoTooltip>
          {STRENGTH_FOCUS_INFO.map(p => <p key={p}>{p}</p>)}
        </InfoTooltip>
      </div>
      <div className="relative flex items-center h-5">
        <div className="absolute inset-x-0 h-2 rounded-full"
          style={{ background: "linear-gradient(to right, #60a5fa, #34d399, #10b981, #f59e0b, #ef4444)" }} />
        <input
          type="range" min={1} max={5} step={1} value={value}
          aria-label="Strength focus"
          onChange={e => onChange(parseInt(e.target.value, 10))}
          className="relative w-full h-2 appearance-none bg-transparent cursor-pointer"
          style={{ "--thumb-color": color }}
        />
      </div>
      <div className="flex justify-between text-[10px] text-slate-400 dark:text-slate-500">
        <span>{STRENGTH_FOCUS[0].label}</span><span>{STRENGTH_FOCUS[STRENGTH_FOCUS.length - 1].label}</span>
      </div>
    </div>
  );
}

// Where on the −2 … +6 track a value sits, in percent.
const rampAt = v => ((v - RAMP_MIN) / (RAMP_MAX - RAMP_MIN)) * 100;

// A fitness goal's CTL change per week. Drawn like IntensitySlider; the track
// turns red past +3 so the risk shows before it is chosen. Everything
// explanatory is behind the "?" — the form carries no paragraphs.
export function RampSlider({ value, onChange }) {
  const color = rampColor(value);
  const track = `linear-gradient(to right, #60a5fa 0%, #10b981 ${rampAt(0)}%, `
    + `#f59e0b ${rampAt(RAMP_RISK)}%, #ef4444 ${rampAt(RAMP_RISK)}%, #ef4444 100%)`;
  return (
    <div className="space-y-1.5">
      <div className="flex items-center gap-2">
        <span className="text-xs font-semibold" style={{ color }}>
          {rampLabel(value)} · {rampWord(value)}
        </span>
        <InfoTooltip>
          {FITNESS_RAMP_INFO.map(p => <p key={p}>{p}</p>)}
        </InfoTooltip>
      </div>
      <div className="relative flex items-center h-5">
        <div className="absolute inset-x-0 h-2 rounded-full" style={{ background: track }} />
        <input
          type="range"
          min={RAMP_MIN}
          max={RAMP_MAX}
          step={RAMP_STEP}
          value={value}
          aria-label="Fitness change per week"
          onChange={e => onChange(parseFloat(e.target.value))}
          className="relative w-full h-2 appearance-none bg-transparent cursor-pointer"
          style={{ "--thumb-color": color }}
        />
      </div>
      <div className="flex justify-between text-[10px] text-slate-400 dark:text-slate-500">
        <span>−2</span><span>+{RAMP_MAX}</span>
      </div>
    </div>
  );
}

// 2–7 day-per-week selector.
export function DaysPerWeekPicker({ value, onChange }) {
  return (
    <div className="flex gap-1.5">
      {[2, 3, 4, 5, 6, 7].map(n => (
        <button
          key={n}
          type="button"
          onClick={() => onChange(n)}
          className={`w-9 h-9 rounded-lg text-sm font-semibold transition-colors ${
            value === n
              ? "bg-accent-500 text-white"
              : "border border-slate-300 dark:border-slate-700 text-slate-600 dark:text-slate-300 hover:border-accent-400 dark:hover:border-accent-500"
          }`}
        >
          {n}
        </button>
      ))}
    </div>
  );
}

// Two-up grid of sport disciplines (MTB / road-cycling). `options` is a list of
// { key, label, blurb }; `value` is the selected key.
export function DisciplineChooser({ options, value, onChange }) {
  return (
    <div className="grid sm:grid-cols-2 gap-2">
      {options.map(d => (
        <button key={d.key} type="button" onClick={() => onChange(d.key)}
          className={`text-left px-2.5 py-1.5 rounded-lg border text-sm transition-colors ${
            value === d.key
              ? "bg-accent-500 border-accent-500 text-white"
              : "border-slate-300 dark:border-slate-700 hover:border-accent-400 dark:hover:border-accent-500 text-slate-700 dark:text-slate-200"
          }`}>
          <div className="font-semibold">{d.label}</div>
          <div className={`text-xs ${value === d.key ? "text-white/80" : "text-slate-500 dark:text-slate-400"}`}>{d.blurb}</div>
        </button>
      ))}
    </div>
  );
}
