// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Static configuration for the Dashboard page.
//
// Pure data with no coupling to any component's state — the page and its
// extracted widgets all import from here so the period selector, phase colours,
// and day-in-milliseconds constant stay defined in exactly one place.

// Tailwind text colours for the current training phase badge in the readiness
// card. Falls back to a neutral slate when the phase isn't one of these.
export const PHASE_TEXT_COLORS = {
  base:  "text-accent-600 dark:text-accent-400",
  build: "text-blue-600 dark:text-blue-400",
  peak:  "text-amber-600 dark:text-amber-400",
  taper: "text-red-600 dark:text-red-400",
};

// Period selector options. `days` is the look-back window used to build the
// `after` query param (null = lifetime, i.e. no filter).
export const PERIODS = [
  { value: "lifetime", label: "Lifetime",    days: null },
  { value: "yearly",   label: "This year",   days: 365 },
  { value: "monthly",  label: "Last 30 days", days: 30 },
  { value: "weekly",   label: "Last 7 days",  days: 7 },
];

export const DAY_MS = 86400000;
