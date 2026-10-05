// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Static lookup tables for the Race Plan page: the terrain-type radio options
// (metric / imperial copy), the pace-vs-HR mode options, and the per-leg styling
// used by the triathlon breakdown.

export const COURSE_OPTIONS_METRIC = [
  { value: "flat",        label: "Flat",        desc: "< 100 m / 100 km gain" },
  { value: "rolling",     label: "Rolling",     desc: "100–500 m / 100 km" },
  { value: "hilly",       label: "Hilly",       desc: "500–1500 m / 100 km" },
  { value: "mountainous", label: "Mountainous", desc: "> 1500 m / 100 km" },
];

export const COURSE_OPTIONS_IMPERIAL = [
  { value: "flat",        label: "Flat",        desc: "< 530 ft / 100 mi gain" },
  { value: "rolling",     label: "Rolling",     desc: "530–2,640 ft / 100 mi" },
  { value: "hilly",       label: "Hilly",       desc: "2,640–7,920 ft / 100 mi" },
  { value: "mountainous", label: "Mountainous", desc: "> 7,920 ft / 100 mi" },
];

export const HR_OPTIONS = [
  { value: "pace",    label: "Pace targets only" },
  { value: "pace_hr", label: "Pace + HR ceiling" },
];

export const TRI_LEG_STYLES = {
  swim: {
    card:  "bg-blue-50 dark:bg-blue-900/20",
    text:  "text-blue-700 dark:text-blue-400",
    label: "Swim",
  },
  bike: {
    card:  "bg-accent-50 dark:bg-accent-900/20",
    text:  "text-accent-700 dark:text-accent-400",
    label: "Bike",
  },
  run: {
    card:  "bg-violet-50 dark:bg-violet-900/20",
    text:  "text-violet-700 dark:text-violet-400",
    label: "Run",
  },
};
