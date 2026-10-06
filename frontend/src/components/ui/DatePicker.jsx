// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Accent-themed calendar date picker — a drop-in replacement for native
// <input type="date"> with a consistent, larger, on-brand popup that matches the
// app's accent colour. value/onChange use ISO "YYYY-MM-DD" strings (same as the
// native input) so it slots into existing controlled forms. The calendar renders
// through AnchoredPopover so it's never clipped by a card's overflow.
import { useMemo, useRef, useState } from "react";
import AnchoredPopover from "./AnchoredPopover";

const WEEKDAYS = ["Su", "Mo", "Tu", "We", "Th", "Fr", "Sa"];
const MONTHS = ["January", "February", "March", "April", "May", "June",
  "July", "August", "September", "October", "November", "December"];

// Parse/format as LOCAL dates so an ISO string never shifts a day across tz.
function parseISO(s) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s || "");
  return m ? new Date(+m[1], +m[2] - 1, +m[3]) : null;
}
function toISO(d) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}
function sameDay(a, b) {
  return a && b && a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
}

export default function DatePicker({
  value, onChange, min, max, placeholder = "Select date", className = "", id,
}) {
  const [open, setOpen] = useState(false);
  const anchorRef = useRef(null);
  const selected = parseISO(value);
  const today = new Date();
  // The month currently shown in the grid (defaults to the selected month / now).
  const [view, setView] = useState(() => selected || today);

  const minD = parseISO(min), maxD = parseISO(max);
  const disabled = (d) => (minD && d < minD) || (maxD && d > maxD);

  const cells = useMemo(() => {
    const first = new Date(view.getFullYear(), view.getMonth(), 1);
    const start = new Date(first);
    start.setDate(1 - first.getDay());           // back up to the Sunday on/before the 1st
    return Array.from({ length: 42 }, (_, i) => {
      const d = new Date(start);
      d.setDate(start.getDate() + i);
      return d;
    });
  }, [view]);

  const label = selected
    ? `${MONTHS[selected.getMonth()].slice(0, 3)} ${selected.getDate()}, ${selected.getFullYear()}`
    : placeholder;

  const pick = (d) => { onChange?.(toISO(d)); setOpen(false); };
  const shiftMonth = (n) => setView((v) => new Date(v.getFullYear(), v.getMonth() + n, 1));

  return (
    <>
      <button
        type="button" id={id} ref={anchorRef}
        onClick={() => { setView(selected || today); setOpen((o) => !o); }}
        className={`field w-auto inline-flex items-center justify-between gap-2 text-left
          ${selected ? "" : "!text-slate-400 dark:!text-slate-500"} ${className}`}
      >
        <span className="truncate">{label}</span>
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
             className="shrink-0 text-slate-400">
          <rect x="3" y="4" width="18" height="18" rx="2" /><path d="M16 2v4M8 2v4M3 10h18" />
        </svg>
      </button>

      <AnchoredPopover anchorRef={anchorRef} open={open} onClose={() => setOpen(false)} align="left">
        <div className="w-72 p-2.5 rounded-xl bg-white dark:bg-slate-900 shadow-2xl border border-slate-200 dark:border-slate-700">
          <div className="flex items-center justify-between mb-2">
            <button type="button" onClick={() => shiftMonth(-1)}
              className="w-8 h-8 grid place-items-center rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 text-slate-500">‹</button>
            <div className="text-sm font-semibold text-slate-700 dark:text-slate-200">
              {MONTHS[view.getMonth()]} {view.getFullYear()}
            </div>
            <button type="button" onClick={() => shiftMonth(1)}
              className="w-8 h-8 grid place-items-center rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 text-slate-500">›</button>
          </div>

          <div className="grid grid-cols-7 gap-0.5 mb-1">
            {WEEKDAYS.map((w) => (
              <div key={w} className="text-[10px] font-medium text-center text-slate-400 dark:text-slate-500 py-1">{w}</div>
            ))}
          </div>

          <div className="grid grid-cols-7 gap-0.5">
            {cells.map((d) => {
              const inMonth = d.getMonth() === view.getMonth();
              const isSel = sameDay(d, selected);
              const isToday = sameDay(d, today);
              const off = disabled(d);
              return (
                <button
                  key={d.toISOString()} type="button" disabled={off}
                  onClick={() => pick(d)}
                  className={`h-9 rounded-lg text-sm transition-colors
                    ${isSel
                      ? "bg-accent-500 text-white font-semibold"
                      : off
                        ? "text-slate-300 dark:text-slate-700 cursor-not-allowed"
                        : inMonth
                          ? "text-slate-700 dark:text-slate-200 hover:bg-accent-50 dark:hover:bg-accent-900/30"
                          : "text-slate-300 dark:text-slate-600 hover:bg-slate-100 dark:hover:bg-slate-800"}
                    ${isToday && !isSel ? "ring-1 ring-accent-400 font-semibold" : ""}`}
                >
                  {d.getDate()}
                </button>
              );
            })}
          </div>

          <div className="flex items-center justify-between mt-2 pt-1.5 border-t border-slate-100 dark:border-slate-800">
            <button type="button" onClick={() => pick(today)}
              className="btn btn-tonal btn-sm">Today</button>
            {value && (
              <button type="button" onClick={() => { onChange?.(""); setOpen(false); }}
                className="btn btn-neutral btn-sm">Clear</button>
            )}
          </div>
        </div>
      </AnchoredPopover>
    </>
  );
}
