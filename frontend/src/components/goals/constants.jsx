// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Static lookup tables + config for the Goals page (sport/event presets,
// goal-type cards, intensity stops, training-phase definitions). These are pure
// data with no React/state dependency, so they live apart from the components
// that render them.

// ── Event presets organized by sport ──────────────────────────────────────────
// distance is in meters. null = distance-not-applicable (race-style, multi-stage).
// name is what picking the preset writes into the event's name: the label
// where it reads as one, something better where it names a format rather
// than an event ("Olympic (S/B/R)"), and null where it names nothing
// ("Track / Other"). The phone's GoalOptions.PRESETS is the same table;
// EventPresets.test.js checks every distance against what its label names.
// Triathlon distances are the legs summed (super sprint 0.4 + 10 + 2.5 km…).
export const EVENT_PRESETS = {
  running: [
    { id: "5k",      label: "5K",            distance: 5000   },
    { id: "10k",     label: "10K",           distance: 10000  },
    { id: "15k",     label: "15K",           distance: 15000  },
    { id: "10mile",  label: "10 Mile",       distance: 16093  },
    { id: "half",    label: "Half Marathon", distance: 21097  },
    { id: "marathon",label: "Marathon",      distance: 42195  },
    { id: "50k",     label: "50K Ultra",     distance: 50000  },
    { id: "50mile",  label: "50 Mile Ultra", distance: 80467  },
    { id: "100k",    label: "100K Ultra",    distance: 100000 },
    { id: "100mile", label: "100 Mile Ultra",distance: 160934 },
    { id: "track",   label: "Track / Other", distance: null, name: null },
  ],
  cycling: [
    { id: "tt20",     label: "20K Time Trial",  distance: 20000  },
    { id: "tt40",     label: "40K Time Trial",  distance: 40000  },
    { id: "metric",   label: "Metric Century",  distance: 100000 },
    { id: "century",  label: "Century (100 mi)",distance: 160934, name: "Century" },
    { id: "double",   label: "Double Century",  distance: 321869 },
    { id: "gravel",   label: "Gravel Race",     distance: null   },
    { id: "stage",    label: "Stage Race",      distance: null   },
    { id: "crit",     label: "Criterium",       distance: null   },
    { id: "roadrace", label: "Road Race",       distance: null   },
  ],
  "mountain biking": [
    { id: "xc",       label: "XC Race",        distance: null   },
    { id: "enduro",   label: "Enduro",         distance: null   },
    { id: "marathon", label: "Marathon (50K+)",distance: 50000, name: "MTB Marathon" },
    { id: "endur50",  label: "50 Mile",        distance: 80467, name: "50 Mile MTB" },
    { id: "endur100", label: "100 Mile",       distance: 160934, name: "100 Mile MTB" },
    { id: "bikepack", label: "Bikepacking",    distance: null   },
  ],
  swimming: [
    { id: "tri-sprint",label: "Sprint Tri Swim (750 m)", distance: 750,  name: "Sprint Tri Swim" },
    { id: "tri-oly",   label: "Olympic Tri Swim (1.5 K)",distance: 1500, name: "Olympic Tri Swim" },
    { id: "tri-half",  label: "70.3 Swim (1.9 K)",       distance: 1900, name: "70.3 Swim" },
    { id: "tri-full",  label: "Ironman Swim (3.8 K)",    distance: 3800, name: "Ironman Swim" },
    { id: "ow-1mi",    label: "1 Mile Open Water",       distance: 1609  },
    { id: "ow-5k",     label: "5K Open Water",           distance: 5000  },
    { id: "ow-10k",    label: "10K Open Water",          distance: 10000 },
    { id: "pool-meet", label: "Pool Meet",               distance: null  },
  ],
  triathlon: [
    { id: "supersprint", label: "Super Sprint",    distance: 12900,  name: "Super Sprint Triathlon" }, // 0.4+10+2.5
    { id: "sprint",      label: "Sprint (S/B/R)",  distance: 25750,  name: "Sprint Triathlon" },       // 0.75+20+5
    { id: "olympic",     label: "Olympic (S/B/R)", distance: 51500,  name: "Olympic Triathlon" },      // 1.5+40+10
    { id: "half",        label: "Half (70.3)",     distance: 113000, name: "70.3 Triathlon" },         // 1.9+90+21.1
    { id: "full",        label: "Ironman (140.6)", distance: 226000, name: "Ironman Triathlon" },      // 3.8+180+42.2
    { id: "aquabike",    label: "Aquabike",        distance: null   },
    { id: "duathlon",    label: "Duathlon",        distance: null   },
  ],
  hiking: [
    { id: "day",       label: "Day Hike",          distance: null },
    { id: "fkt",       label: "FKT Attempt",       distance: null },
    { id: "thru",      label: "Thru Hike",         distance: null },
    { id: "peak",      label: "Peak / Summit",     distance: null, name: "Summit" },
  ],
  skiing: [
    { id: "race",      label: "Ski Race",          distance: null  },
    { id: "skimo",     label: "Ski Mountaineering",distance: null  },
    { id: "tour",      label: "Backcountry Tour",  distance: null  },
  ],
  rowing: [
    { id: "2k",        label: "2000 m Erg",        distance: 2000, name: "2K Erg" },
    { id: "5k",        label: "5K Erg",            distance: 5000  },
    { id: "head",      label: "Head Race",         distance: null  },
    { id: "sprint",    label: "Sprint Race",       distance: null  },
  ],
  climbing: [
    { id: "project",   label: "Project / Redpoint", distance: null },
    { id: "comp",      label: "Competition",        distance: null },
    { id: "trip",      label: "Climbing Trip",      distance: null },
  ],
  other: [
    { id: "custom",    label: "Custom event",      distance: null, name: null },
  ],
  strength_training: [
    { id: "bodybuilding", label: "Bodybuilding / Hypertrophy", distance: null, name: "Bodybuilding" },
    { id: "powerlifting", label: "Powerlifting",               distance: null },
    { id: "general",      label: "General Strength",           distance: null },
  ],
};

// The name picking a preset writes: its own name if it has one, else its label.
export const presetName = p => (p.name === undefined ? p.label : p.name);

// Every name a preset writes. A name still equal to one of these was filled
// in, not typed, so the next preset may replace it.
export const PRESET_NAMES = new Set(
  Object.values(EVENT_PRESETS).flat().map(presetName).filter(Boolean),
);

export const SPORT_LABEL = {
  running:           "Running",
  cycling:           "Cycling",
  "mountain biking": "Mountain Biking",
  swimming:          "Swimming",
  triathlon:         "Triathlon",
  hiking:            "Hiking",
  skiing:            "Skiing",
  rowing:            "Rowing",
  climbing:          "Climbing",
  strength_training: "Strength Training",
  // The specific sports a variant chooser writes (below), for goal cards.
  open_water_swimming:  "Open-Water Swimming",
  cross_country_skiing: "Cross-Country Skiing",
  backcountry_skiing:   "Ski Mountaineering",
  alpine_skiing:        "Alpine Skiing",
  other:             "Other",
};

// ── Goal-type chooser cards ───────────────────────────────────────────────────
export const GOAL_TYPES = [
  {
    id: "event",
    title: "Race / Event",
    description: "Train for a specific event on a target date.",
    icon: (
      <svg className="w-5 h-5" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
      </svg>
    ),
  },
  {
    id: "fitness",
    title: "Fitness",
    description: "Build, hold or ease off your fitness — a rolling four-week plan.",
    icon: (
      <svg className="w-5 h-5" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" />
      </svg>
    ),
  },
  {
    id: "volume_target",
    title: "Weekly Volume",
    description: "Hit a target weekly distance for a sport.",
    icon: (
      <svg className="w-5 h-5" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" d="M9 19V6l12-3v13M9 19c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2zm12-3c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2zM9 10l12-3" />
      </svg>
    ),
  },
];

// ── Fitness goal: CTL change per week ─────────────────────────────────────────
// The slider's range and step, and where it turns red. The phone's GoalOptions
// (mobile/.../ui/goals/GoalDraft.kt) holds the same numbers. The top is +6:
// the plan delivers it (sized to its load in TSS), and past it the first
// week's load is about twice fitness — backend schemas/coaching.py CtlRamp
// has the measurements and the reasoning. Everything above +3 is red.
export const RAMP_MIN = -2;
export const RAMP_MAX = 6;
export const RAMP_STEP = 0.5;
export const RAMP_RISK = 3;

// A fitness goal's sports, as the concrete sports the planner reads (each
// swimming and skiing kind its own choice). `family` is the planner's sport
// family: two picks of one family would plan as one. Strength comes with the
// goal's strength settings, so it is not here. The phone's
// GoalOptions.FITNESS_SPORTS and Explain.FitnessSports match.
export const FITNESS_SPORTS = [
  { key: "running", label: "Running", family: "running" },
  { key: "cycling", label: "Cycling", family: "cycling" },
  { key: "mountain biking", label: "Mountain Biking", family: "mountain_biking" },
  { key: "swimming", label: "Pool Swimming", family: "swimming" },
  { key: "open_water_swimming", label: "Open-Water Swimming", family: "swimming" },
  { key: "triathlon", label: "Triathlon", family: "triathlon" },
  { key: "hiking", label: "Hiking", family: "hiking" },
  { key: "cross_country_skiing", label: "Cross-Country Skiing", family: "nordic_skiing" },
  { key: "backcountry_skiing", label: "Skimo / Touring", family: "nordic_skiing" },
  { key: "alpine_skiing", label: "Alpine Skiing", family: "alpine_skiing" },
  { key: "rowing", label: "Rowing", family: "rowing" },
  { key: "climbing", label: "Climbing", family: "climbing" },
];
export const FITNESS_SPORTS_INFO = [
  "Pick every sport you want to train. The plan shares one weekly load between them — fitness is one number, whatever made it.",
  "The share follows what you have actually been doing lately, and each sport keeps at least a day. Hard days and running days are spread so they do not land back to back.",
  "Triathlon trains swimming, cycling and running.",
];

// Behind the slider's "?". The phone's Explain.FitnessRamp says the same.
export const FITNESS_RAMP_INFO = [
  "How many CTL points your fitness should gain each week. 0 holds it; below 0 eases off, as in an off-season.",
  "Every fourth week is lighter so the gains settle. The plan rolls four weeks ahead and rebuilds from your actual fitness as you train.",
  "Above +3 is aggressive: hard to absorb for long, and injury risk rises. Past +6 a week the first week asks for about twice your usual daily load, so the slider stops there.",
];

// ── Intensity slider stops (value = duration multiplier) ──────────────────────
export const INTENSITY_STOPS = [
  { value: 0.5,  label: "Easy",     color: "#60a5fa" },
  { value: 0.75, label: "Light",    color: "#34d399" },
  { value: 1.0,  label: "Moderate", color: "#10b981" },
  { value: 1.25, label: "Hard",     color: "#f59e0b" },
  { value: 1.5,  label: "Max",      color: "#ef4444" },
];

// Behind the intensity slider's "?". The phone's Explain.PlanIntensity says the same.
export const INTENSITY_INFO = [
  "Scales how long every planned session is: Easy is half, Moderate the plan as designed, Max half again.",
  "Too hard? Turn it down any time — the plan rebuilds from today, and what you have done stays.",
];

// ── Strength focus slider (the goal's strength_tier, 1–5) ─────────────────────
// Sessions a week, and the word for each stop. The phone's GoalOptions.STRENGTH_FOCUS.
export const STRENGTH_FOCUS = [
  { value: 1, label: "Maintain" },
  { value: 2, label: "Supplement" },
  { value: 3, label: "Balanced" },
  { value: 4, label: "Strength-first" },
  { value: 5, label: "Athlete" },
];

// Behind the strength slider's "?". The phone's Explain.StrengthFocus says the same.
export const STRENGTH_FOCUS_INFO = [
  "How many strength sessions a week the plan adds, 1 to 5, and how heavy the split is: 1 keeps what you have, 5 is a dedicated strength block.",
  "Change it any time; the plan rebuilds.",
];

// ── Sport-specific discipline options (event goals) ───────────────────────────
export const MTB_DISCIPLINES = [
  { key: "xco",    label: "XCO",    blurb: "Olympic XC · 90 min, VO2 + W' heavy" },
  { key: "xcm",    label: "XCM",    blurb: "Marathon XC · 2–6 h, sweet spot + long" },
  { key: "enduro", label: "Enduro", blurb: "Timed descents · matchbook + skills heavy" },
  { key: "trail",  label: "Trail",  blurb: "Recreational riding · year-round periodization" },
];

export const CYCLING_DISCIPLINES = [
  { key: "road_race",  label: "Road race / Fondo", blurb: "Sweet-spot + threshold-heavy build · long Sunday ride" },
  { key: "time_trial", label: "Time Trial",        blurb: "Block VO2 → threshold + TT-pace · aero position" },
  { key: "hill_climb", label: "Hill climb",        blurb: "Polarized Z2 + sustained climbing · W/kg focused" },
  { key: "criterium",  label: "Criterium",         blurb: "Anaerobic + over-under + sprint blocks · Carmichael compressed" },
];

// ── Sport variants: which kind of swimming or skiing ─────────────────────────
// The variant is the sport itself — event_sport holds "open_water_swimming" or
// "alpine_skiing" — so there is no discipline column to add and the plan and
// the watch (pool vs open-water workouts) both read it straight off the goal.
// A bare "skiing" or "swimming" is the first option. The phone's GoalOptions
// holds the same lists.
export const SPORT_VARIANTS = {
  swimming: {
    options: [
      { key: "swimming",            label: "Pool" },
      { key: "open_water_swimming", label: "Open water" },
    ],
    info: [
      "Pool plans are sets in lengths (drills, kick, pull, CSS and VO2 sets) that run as pool-swim workouts on the watch.",
      "Open-water plans are timed, and practise sighting, drafting and the pace changes of a mass start.",
    ],
  },
  skiing: {
    options: [
      { key: "cross_country_skiing", label: "Cross-country" },
      { key: "backcountry_skiing",   label: "Skimo / touring" },
      { key: "alpine_skiing",        label: "Alpine" },
    ],
    info: [
      "Cross-country and skimo are endurance plans: distance, threshold and uphill intervals, with dry-land ski-walking and bounding.",
      "Alpine is a dry-land conditioning block: aerobic base, eccentric leg work, plyometrics, agility and ski-run intervals. Turn on strength for the heavy leg work.",
    ],
  },
};

// The sport chip a stored sport belongs to: "alpine_skiing" is under Skiing.
export function sportChip(sport) {
  for (const [chip, v] of Object.entries(SPORT_VARIANTS)) {
    if (sport === chip || v.options.some(o => o.key === sport)) return chip;
  }
  return sport;
}

// ── Training-phase timeline (event goals only) ────────────────────────────────
export const PHASES_CONFIG = [
  { id: "base",  label: "Base",  desc: "Aerobic foundation — easy efforts promoted to aerobic, session duration extended +10%." },
  { id: "build", label: "Build", desc: "Race-specific fitness — aerobic efforts pushed toward tempo intensity." },
  { id: "peak",  label: "Peak",  desc: "Sharpening — hard efforts maintained while fitness peaks before taper." },
  { id: "taper", label: "Taper", desc: "Arrive fresh — intensity capped at easy, volume reduced to 65% of normal." },
];

export const PHASE_DOT_ACTIVE = {
  base:  "border-accent-500 bg-accent-500",
  build: "border-blue-500 bg-blue-500",
  peak:  "border-amber-500 bg-amber-500",
  taper: "border-red-500 bg-red-500",
};
export const PHASE_LABEL_ACTIVE = {
  base:  "text-accent-700 dark:text-accent-400 font-semibold",
  build: "text-blue-700 dark:text-blue-400 font-semibold",
  peak:  "text-amber-700 dark:text-amber-400 font-semibold",
  taper: "text-red-700 dark:text-red-400 font-semibold",
};
export const PHASE_DESC_COLOR = {
  base:  "text-accent-600 dark:text-accent-400",
  build: "text-blue-600 dark:text-blue-400",
  peak:  "text-amber-600 dark:text-amber-400",
  taper: "text-red-600 dark:text-red-400",
};
