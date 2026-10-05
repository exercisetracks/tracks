// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import React from "react";

/**
 * WeightDisplay - Formats and displays weight values
 */
export function WeightDisplay({ kg, imperial = false }) {
  if (kg == null || kg === 0) return "Bodyweight";
  if (imperial) return `${(kg * 2.20462).toFixed(1)} lbs`;
  return `${kg % 1 === 0 ? kg : kg.toFixed(1)} kg`;
}

/**
 * WeightPill - Weight display in pill container
 */
export function WeightPill({ kg, imperial = false, label }) {
  return (
    <div className="flex flex-col items-center gap-0.5 min-w-0">
      <span className="font-semibold text-white tabular-nums text-base leading-tight">
        <WeightDisplay kg={kg} imperial={imperial} />
      </span>
      {label && <span className="text-xs text-slate-400 uppercase tracking-wider text-center">{label}</span>}
    </div>
  );
}

/**
 * WeightRange - Displays a range of weights
 */
export function WeightRange({ minKg, maxKg, imperial = false }) {
  if (minKg == null && maxKg == null) return "—";
  if (minKg == null) return <WeightDisplay kg={maxKg} imperial={imperial} />;
  if (maxKg == null) return <WeightDisplay kg={minKg} imperial={imperial} />;

  const min = imperial ? (minKg * 2.20462).toFixed(1) : minKg % 1 === 0 ? minKg : minKg.toFixed(1);
  const max = imperial ? (maxKg * 2.20462).toFixed(1) : maxKg % 1 === 0 ? maxKg : maxKg.toFixed(1);
  const unit = imperial ? "lbs" : "kg";

  return `${min}–${max} ${unit}`;
}