// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Horizontal injury timeline: a Gantt-style bar per injury over the full
// history (or zoomed to the past year), with smart year/quarter/month tick
// labels. Owns its own zoom state; selection is lifted to the parent via
// `selectedId` / `onSelect`.

import { useState } from "react";
import { isoToday } from "./helpers";
import { TIMELINE_COLORS } from "./constants";

export default function InjuryTimeline({ injuries, selectedId, onSelect }) {
  const [zoom, setZoom] = useState("all");
  if (!injuries.length) return null;

  const today = new Date();
  const todayMs = today.getTime();

  // Compute full history range
  const earliest = injuries.reduce((min, inj) => {
    const d = new Date(inj.start_date + "T00:00:00");
    return d < min ? d : min;
  }, today);

  const fullSpanDays = (todayMs - earliest.getTime()) / 86400000;

  // Range: zoom to past year when requested, else full history (min 6 months)
  let rangeStart;
  if (zoom === "year") {
    rangeStart = new Date(today);
    rangeStart.setFullYear(today.getFullYear() - 1);
  } else {
    const sixMonthsAgo = new Date(today);
    sixMonthsAgo.setMonth(today.getMonth() - 6);
    rangeStart = earliest < sixMonthsAgo ? new Date(earliest) : sixMonthsAgo;
  }
  const rangeMs = todayMs - rangeStart.getTime();
  const rangeSpanDays = rangeMs / 86400000;

  function pct(dateStr) {
    const ms = new Date(dateStr + "T00:00:00").getTime() - rangeStart.getTime();
    return Math.max(0, Math.min(100, (ms / rangeMs) * 100));
  }

  // Smart tick labels: years when span > 2 years, quarters for 1-2 years, months otherwise
  const ticks = [];
  const cur = new Date(rangeStart);
  cur.setDate(1);
  if (rangeSpanDays > 2 * 365) {
    // First of each year
    cur.setMonth(0);
    cur.setFullYear(cur.getFullYear() + 1);
    while (cur <= today) {
      ticks.push({ date: new Date(cur), label: cur.getFullYear().toString() });
      cur.setFullYear(cur.getFullYear() + 1);
    }
  } else if (rangeSpanDays > 365) {
    // Every 3 months (quarters)
    const qMonth = Math.ceil((cur.getMonth() + 1) / 3) * 3;
    cur.setMonth(qMonth % 12);
    if (qMonth >= 12) cur.setFullYear(cur.getFullYear() + 1);
    while (cur <= today) {
      ticks.push({ date: new Date(cur), label: cur.toLocaleDateString(undefined, { month: "short", year: "2-digit" }) });
      cur.setMonth(cur.getMonth() + 3);
    }
  } else {
    // Every month
    cur.setMonth(cur.getMonth() + 1);
    while (cur <= today) {
      ticks.push({ date: new Date(cur), label: cur.toLocaleDateString(undefined, { month: "short" }) });
      cur.setMonth(cur.getMonth() + 1);
    }
  }

  const sorted = [...injuries].sort((a, b) => a.start_date.localeCompare(b.start_date));

  return (
    <div className="mb-4">
      <div className="flex items-center justify-between mb-1.5">
        <span className="text-xs text-slate-400 dark:text-slate-500">
          {zoom === "year" ? "Past 12 months" : "All time"}
        </span>
        {fullSpanDays > 365 && (
          <button
            onClick={() => setZoom(z => z === "year" ? "all" : "year")}
            className="btn btn-neutral btn-sm"
          >
            {zoom === "year" ? "Show all time" : "Zoom to past year"}
          </button>
        )}
      </div>
      <div className="relative h-7 bg-slate-100 dark:bg-slate-800 rounded-lg overflow-hidden cursor-pointer">
        {sorted.map((inj, i) => {
          const startPct = pct(inj.start_date);
          const endPct = pct(inj.end_date || isoToday());
          const widthPct = Math.max(endPct - startPct, 0.8);
          const isSelected = inj.id === selectedId;
          return (
            <div
              key={inj.id}
              onClick={() => onSelect(isSelected ? null : inj.id)}
              className={`absolute top-1 bottom-1 rounded transition-all ${TIMELINE_COLORS[i % TIMELINE_COLORS.length]} ${isSelected ? "opacity-100 ring-2 ring-white ring-offset-1" : "opacity-70 hover:opacity-90"}`}
              style={{ left: `${startPct}%`, width: `${widthPct}%` }}
              title={`${inj.body_part} ${inj.injury_type} (${inj.start_date}${inj.end_date ? ` → ${inj.end_date}` : " → active"})`}
            />
          );
        })}
        <div className="absolute top-0 bottom-0 w-px bg-slate-400 dark:bg-slate-500" style={{ left: "99.5%" }} />
      </div>
      <div className="relative h-4 mt-0.5">
        {ticks.map((t, i) => {
          const p = ((t.date.getTime() - rangeStart.getTime()) / rangeMs) * 100;
          if (p < 2 || p > 98) return null;
          return (
            <span
              key={i}
              className="absolute text-xs text-slate-400 dark:text-slate-500 -translate-x-1/2"
              style={{ left: `${p}%` }}
            >
              {t.label}
            </span>
          );
        })}
        <span className="absolute right-0 text-xs text-slate-400 dark:text-slate-500">Today</span>
      </div>
    </div>
  );
}
