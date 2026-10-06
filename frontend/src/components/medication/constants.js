// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Shared style tokens and option lists for the Medication section. Centralised
// so the sub-components (form rows, dose cards, log rows) stay presentational
// and use one consistent input / button styling.

// Tiny className joiner (filters out falsy values).
export function clsx(...args) { return args.filter(Boolean).join(" "); }

// Shared control styles.
export const INPUT = "field";
// The small pills of the shared button family (tailwind.config.js `.btn`);
// these rows are dense, so every button here is the 32px size.
export const BTN_PRIMARY = "btn btn-primary btn-sm";
export const BTN_TONAL   = "btn btn-tonal btn-sm";
export const BTN_GHOST   = "btn btn-neutral btn-sm";
export const BTN_DANGER  = "btn btn-danger btn-sm";

// Weekday labels indexed 0=Sun … 6=Sat (matches JS Date.getDay()).
export const DAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
export const FORMS = ["Tablet", "Capsule", "Liquid", "Injection", "Patch", "Cream", "Inhaler", "Drop", "Other"];
export const DOSE_UNITS = ["mg", "mcg", "g", "ml", "IU", "tablet(s)", "capsule(s)", "drop(s)", "puff(s)", "unit(s)"];
