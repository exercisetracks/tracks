// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import React from "react";

/**
 * DurationDisplay - Formats and displays duration values
 * Rounds seconds to nearest integer to avoid floating point precision issues
 */
export function DurationDisplay({ seconds, format = "detailed" }) {
  if (!seconds) return "—";

  // Round to nearest integer to avoid floating point precision issues
  const roundedSeconds = Math.round(seconds);

  if (format === "compact") {
    const h = Math.floor(roundedSeconds / 3600);
    const m = Math.floor((roundedSeconds % 3600) / 60);
    if (h > 0) return `${h}h ${m}m`;
    return `${m}m`;
  }

  if (format === "mmss") {
    const m = Math.floor(roundedSeconds / 60);
    const s = roundedSeconds % 60;
    return `${m}:${s.toString().padStart(2, "0")}`;
  }

  const h = Math.floor(roundedSeconds / 3600);
  const m = Math.floor((roundedSeconds % 3600) / 60);
  const sec = roundedSeconds % 60;
  if (h > 0) return `${h}h ${m}m ${sec}s`;
  if (m > 0) return `${m}m ${sec}s`;
  return `${sec}s`;
}

/**
 * DurationBar - Horizontal bar showing duration proportion
 */
export function DurationBar({ duration, totalDuration, color = "#10b981" }) {
  if (!duration || !totalDuration) return null;
  const width = (duration / totalDuration) * 100;

  return (
    <div className="h-2 rounded-full bg-slate-100 dark:bg-slate-800 overflow-hidden">
      <div style={{ width: `${width}%`, background: color }} className="h-full rounded-full" />
    </div>
  );
}