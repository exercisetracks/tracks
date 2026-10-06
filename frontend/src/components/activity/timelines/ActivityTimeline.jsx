// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import React from "react";

/**
 * ActivityTimeline - Displays a horizontal timeline strip showing activity phases
 */
export function ActivityTimeline({
  splits,
  totalDuration,
  getBgColor,
  showLabels = true,
  activeType = "active",
}) {
  if (!splits?.length || !totalDuration) return null;

  const defaultGetBgColor = (split) => {
    const isActive = split.split_type === activeType;
    if (isActive && split.max_heart_rate) {
      return hrColor(Math.min(1, split.max_heart_rate / 200));
    }
    return isActive ? "#10b981" : "rgba(148,163,184,0.25)";
  };

  const colorFn = getBgColor || defaultGetBgColor;

  return (
    <div>
      <p className="section-title mb-2">
        Session Timeline
      </p>
      <div className="relative h-10 rounded-lg overflow-hidden bg-slate-100 dark:bg-slate-800 flex">
        {splits.map((split, i) => {
          const width = ((split.duration_seconds ?? 0) / totalDuration) * 100;
          const bg = colorFn(split);
          return (
            <div
              key={i}
              title={`${split.split_type}: ${Math.round(split.duration_seconds ?? 0)}s`}
              style={{
                width: `${width}%`,
                background: bg,
                minWidth: split.split_type === activeType ? 2 : 1,
              }}
            />
          );
        })}
      </div>
      {showLabels && (
        <div className="flex justify-between text-xs text-slate-400 dark:text-slate-500 mt-1">
          <span>0:00</span>
          <span>{fmtElapsed(Math.round(totalDuration / 2))}</span>
          <span>{fmtElapsed(Math.round(totalDuration))}</span>
        </div>
      )}
    </div>
  );
}

/**
 * SimpleTimeline - Simplified timeline with custom segment colors
 */
export function SimpleTimeline({ segments, totalDuration }) {
  if (!segments?.length || !totalDuration) return null;

  return (
    <div className="relative h-8 rounded-lg overflow-hidden bg-slate-100 dark:bg-slate-800 flex">
      {segments.map((seg, i) => {
        const width = (seg.duration / totalDuration) * 100;
        return (
          <div
            key={i}
            title={seg.label}
            style={{
              width: `${width}%`,
              background: seg.color,
              minWidth: 1,
            }}
          />
        );
      })}
    </div>
  );
}

// Helper imports
import { fmtElapsed, hrColor } from "../../../utils/formatUtils";