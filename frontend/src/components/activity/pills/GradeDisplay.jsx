// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import React from "react";

/**
 * GradeDisplay - Formats and displays climbing grades
 */
export function GradeDisplay({ grade, scale = "V" }) {
  if (grade == null) return "—";
  
  if (scale === "V") return `V${grade}`;
  if (scale === "YDS") return formatYDS(grade);
  if (scale === "French") return formatFrench(grade);
  
  return `V${grade}`;
}

/**
 * GradePill - Grade display in pill container
 */
export function GradePill({ grade, scale = "V", className = "" }) {
  return (
    <span className={`inline-flex items-center text-xs font-semibold text-accent-600 dark:text-accent-400 bg-accent-50 dark:bg-accent-900/30 rounded px-1.5 py-0.5 ${className}`}>
      <GradeDisplay grade={grade} scale={scale} />
    </span>
  );
}

/**
 * GradeRange - Displays a range of grades
 */
export function GradeRange({ min, max, scale = "V" }) {
  if (min == null && max == null) return "—";
  if (min == null) return <GradeDisplay grade={max} scale={scale} />;
  if (max == null) return <GradeDisplay grade={min} scale={scale} />;
  
  return `${<GradeDisplay grade={min} scale={scale} />}–${<GradeDisplay grade={max} scale={scale} />}`;
}

function formatYDS(grade) {
  const classes = ["5.0", "5.1", "5.2", "5.3", "5.4", "5.5", "5.6", "5.7", "5.8", "5.9", "5.10", "5.11", "5.12", "5.13", "5.14"];
  return classes[grade] || `5.${grade}`;
}

function formatFrench(grade) {
  const classes = ["3", "4a", "4b", "4c", "5a", "5b", "5c", "6a", "6b", "6c", "7a", "7b", "7c", "8a", "8b", "8c", "9a"];
  return classes[grade] || `${grade}`;
}