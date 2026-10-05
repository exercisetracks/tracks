// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Weight display/entry helpers. The backend stores weight in kg (FIT spec) even
// after rounding to imperial plates, so raw values can be ugly floats like
// 9.0718 kg (= 20 lb). These convert cleanly in both directions for the user's
// unit and are the single source of truth for weight formatting.

const LB_PER_KG = 2.20462;

// Format a stored kg weight for display. Returns "BW" for bodyweight (0/null).
export function fmtWeight(kg, imperial) {
  if (!kg || kg <= 0) return "BW";
  const v = imperial ? kg * LB_PER_KG : kg;
  const num = Math.abs(v - Math.round(v)) < 0.05 ? Math.round(v) : v.toFixed(1);
  return `${num} ${imperial ? "lb" : "kg"}`;
}

// Stored kg -> a number in the user's unit, for prefilling an editable field.
export function kgToDisplay(kg, imperial) {
  if (!kg || kg <= 0) return 0;
  const v = imperial ? kg * LB_PER_KG : kg;
  return Math.abs(v - Math.round(v)) < 0.05 ? Math.round(v) : Math.round(v * 10) / 10;
}

// A user-entered number in their unit -> kg for storage/logging.
export function displayToKg(value, imperial) {
  const n = Number(value) || 0;
  return imperial ? n / LB_PER_KG : n;
}

export const weightUnit = (imperial) => (imperial ? "lb" : "kg");
