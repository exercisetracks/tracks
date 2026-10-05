// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Shared style tokens and tiny pure helpers for the Meal section. Centralised so
// the presentational sub-components (meal form, log modal, chart) stay lean and
// share one consistent input / button styling. No React, no state here.

// Format a numeric value to `dec` decimals, or an em-dash when null/undefined.
export function fmtNum(v, dec = 0) { return v != null ? Number(v).toFixed(dec) : "—"; }

// Tiny className joiner (filters out falsy values).
export function clsx(...args) { return args.filter(Boolean).join(" "); }

// Shared control styles.
export const INPUT = "text-sm rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-100 px-2.5 py-1 focus:outline-none focus:ring-2 focus:ring-accent-500 w-full";
// The small pills of the shared button family (tailwind.config.js `.btn`);
// these rows are dense, so every button here is the 32px size.
export const BTN_PRIMARY = "btn btn-primary btn-sm";
export const BTN_TONAL   = "btn btn-tonal btn-sm";
export const BTN_GHOST   = "btn btn-neutral btn-sm";
export const BTN_DANGER  = "btn btn-danger btn-sm";

// 24 hour-of-day labels ("12a", "1a" … "11p"), indexed 0-23 to match Date.getHours().
export const HOUR_LABELS = Array.from({ length: 24 }, (_, h) => {
  if (h === 0)  return "12a";
  if (h < 12)   return `${h}a`;
  if (h === 12) return "12p";
  return `${h - 12}p`;
});
