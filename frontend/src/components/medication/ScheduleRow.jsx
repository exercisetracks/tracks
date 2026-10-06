// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// One editable schedule row inside the medication form: dose time, optional
// start/end dates, a day-of-week selector (or "every day"), and the browser-
// reminder / as-needed toggles. Fully controlled — the parent owns the schedule
// object and receives the whole updated object via onChange (or onRemove).

import { INPUT, DAYS } from "./constants";
import DatePicker from "../ui/DatePicker";

export default function ScheduleRow({ sched, onChange, onRemove }) {
  const everydayMode = !sched.days_of_week || sched.days_of_week.length === 0;

  function toggleDay(d) {
    const current = sched.days_of_week ?? [];
    const next = current.includes(d) ? current.filter(x => x !== d) : [...current, d].sort();
    onChange({ ...sched, days_of_week: next.length === 0 ? null : next });
  }

  return (
    <div className="bg-slate-50 dark:bg-slate-800/50 rounded-lg p-2.5 space-y-2 border border-slate-200 dark:border-slate-700">
      <div className="flex items-center gap-2">
        <div className="flex-1">
          <label className="field-label">Time</label>
          <input
            type="time"
            className={INPUT}
            value={sched.time_of_day}
            onChange={e => onChange({ ...sched, time_of_day: e.target.value })}
          />
        </div>
        <div className="flex-1">
          <label className="field-label">Start date</label>
          <DatePicker
            className="w-full"
            value={sched.start_date ?? ""}
            onChange={v => onChange({ ...sched, start_date: v || null })}
          />
        </div>
        <div className="flex-1">
          <label className="field-label">End date</label>
          <DatePicker
            className="w-full"
            value={sched.end_date ?? ""}
            onChange={v => onChange({ ...sched, end_date: v || null })}
          />
        </div>
        <button type="button" onClick={onRemove} className="icon-btn icon-btn-sm icon-btn-danger mt-5">×</button>
      </div>

      <div className="flex flex-wrap gap-1">
        <span className="text-xs text-slate-400 self-center mr-1">Days:</span>
        <button
          type="button"
          onClick={() => onChange({ ...sched, days_of_week: null })}
          aria-pressed={everydayMode} className="chip chip-sm"
        >
          Every day
        </button>
        {DAYS.map((d, i) => {
          const selected = !everydayMode && (sched.days_of_week ?? []).includes(i);
          return (
            <button
              key={d}
              type="button"
              onClick={() => {
                if (everydayMode) onChange({ ...sched, days_of_week: [i] });
                else toggleDay(i);
              }}
              aria-pressed={selected} className="chip chip-sm"
            >
              {d}
            </button>
          );
        })}
      </div>

      <div className="flex items-center gap-4">
        <label className="flex items-center gap-2 text-xs text-slate-500 dark:text-slate-400 cursor-pointer">
          <input
            type="checkbox"
            checked={sched.notify}
            onChange={e => onChange({ ...sched, notify: e.target.checked })}
          />
          Browser reminder
        </label>
        <label className="flex items-center gap-2 text-xs text-slate-500 dark:text-slate-400 cursor-pointer">
          <input
            type="checkbox"
            checked={sched.is_as_needed}
            onChange={e => onChange({ ...sched, is_as_needed: e.target.checked })}
          />
          As-needed only
        </label>
      </div>
    </div>
  );
}
