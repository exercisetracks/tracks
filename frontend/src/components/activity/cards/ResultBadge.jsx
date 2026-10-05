// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import React from "react";

/**
 * ResultBadge - Displays result status (send, attempt, etc.)
 */
export function ResultBadge({ result }) {
  if (result === 3) return (
    <span className="inline-flex items-center text-[10px] font-semibold text-accent-600 dark:text-accent-400 bg-accent-50 dark:bg-accent-900/30 rounded px-1 py-0.5">
      Send
    </span>
  );
  if (result === 2) return (
    <span className="inline-flex items-center text-[10px] font-semibold text-slate-400 dark:text-slate-500 bg-slate-100 dark:bg-slate-800 rounded px-1 py-0.5">
      Attempt
    </span>
  );
  return null;
}

/**
 * StatusBadge - Generic status badge
 */
export function StatusBadge({ status, type = "neutral" }) {
  const colors = {
    success: "text-accent-600 dark:text-accent-400 bg-accent-50 dark:bg-accent-900/30",
    warning: "text-amber-600 dark:text-amber-400 bg-amber-50 dark:bg-amber-900/30",
    error: "text-red-600 dark:text-red-400 bg-red-50 dark:bg-red-900/30",
    neutral: "text-slate-600 dark:text-slate-400 bg-slate-100 dark:bg-slate-800",
  };

  return (
    <span className={`inline-flex items-center text-xs font-semibold rounded px-1.5 py-0.5 ${colors[type]}`}>
      {status}
    </span>
  );
}

/**
 * ResultIcon - Simple icon for results
 */
export function ResultIcon({ result }) {
  if (result === 3) return <span className="text-accent-500">✓</span>;
  if (result === 2) return <span className="text-slate-400">○</span>;
  return null;
}