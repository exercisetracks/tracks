// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// A day's hours as two lines on one chart: temperature in orange, chance of
// rain in blue, each on its own scale, across the panel's width — the phone's
// HourChart.
//
// Nobody opens this to look up the temperature at 14:00; they open it to find
// the part of the day that is warm and dry, which is a shape, and the shape is
// where the two lines trade off. Temperature is scaled to the day's own range:
// a day between 12° and 19° drawn from freezing is a flat line. Rain is
// 0–100 % as it stands. Hours are in the browser's own clock, 12- or 24-hour;
// the pointer reads out the hour under it.
import { useState } from "react";

const TEMP = "#f97316";
const RAIN = "#3b82f6";

/** Whether this browser's locale writes times on a 12-hour clock. */
const twelveHour = () => {
  try { return !!new Intl.DateTimeFormat(undefined, { hour: "numeric" }).resolvedOptions().hour12; }
  catch { return true; }
};

/** "3 PM" / "15:00" for an ISO local time; `long` adds minutes on a 12-hour clock. */
export function clockLabel(iso, is24, long = false) {
  const h = Number(String(iso).slice(11, 13));
  if (Number.isNaN(h)) return "";
  if (is24) return `${String(h).padStart(2, "0")}:00`;
  const h12 = h % 12 === 0 ? 12 : h % 12;
  return `${h12}${long ? ":00" : ""} ${h < 12 ? "AM" : "PM"}`;
}

export default function HourChart({ hours, temp }) {
  const [at, setAt] = useState(null);
  const pts = hours.filter((h) => h.temp_c != null);
  if (pts.length < 2) return <p className="text-[11px] text-slate-400">No hourly forecast for this day.</p>;
  const is24 = !twelveHour();
  const W = 300, H = 70;
  let lo = Math.min(...pts.map((h) => h.temp_c)), hi = Math.max(...pts.map((h) => h.temp_c));
  if (hi - lo < 4) { hi += 2; lo -= 2; }
  const x = (i) => (i / (pts.length - 1)) * W;
  const yT = (c) => 6 + (1 - (c - lo) / (hi - lo)) * (H - 12);
  const yR = (p) => 6 + (1 - (p ?? 0) / 100) * (H - 12);
  const path = (f) => pts.map((h, i) => `${i ? "L" : "M"}${x(i).toFixed(1)},${f(h).toFixed(1)}`).join(" ");
  const ticks = [0, Math.round((pts.length - 1) / 2), pts.length - 1];
  const hover = at != null ? pts[at] : null;

  return (
    <div className="relative select-none">
      <div className="flex justify-between text-[10px] mb-0.5">
        <span style={{ color: TEMP }}>Temperature</span>
        {hover ? (
          <span className="tabular-nums text-slate-600 dark:text-slate-300">
            {clockLabel(hover.time, is24, true)} · <span style={{ color: TEMP }}>{temp(hover.temp_c)}</span> · <span style={{ color: RAIN }}>{hover.precip_prob_pct ?? 0}% rain</span>
          </span>
        ) : <span style={{ color: RAIN }}>Chance of rain</span>}
      </div>
      <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" className="w-full h-[70px] overflow-visible"
           onMouseMove={(e) => {
             const r = e.currentTarget.getBoundingClientRect();
             setAt(Math.round(((e.clientX - r.left) / r.width) * (pts.length - 1)));
           }}
           onMouseLeave={() => setAt(null)}>
        <path d={path((h) => yR(h.precip_prob_pct))} fill="none" stroke={RAIN} strokeWidth="2" vectorEffect="non-scaling-stroke" strokeLinejoin="round" />
        <path d={path((h) => yT(h.temp_c))} fill="none" stroke={TEMP} strokeWidth="2" vectorEffect="non-scaling-stroke" strokeLinejoin="round" />
        {hover && <line x1={x(at)} x2={x(at)} y1="0" y2={H} stroke="currentColor" className="text-slate-400" strokeWidth="1" vectorEffect="non-scaling-stroke" />}
      </svg>
      <div className="relative h-3 text-[10px] text-slate-400 tabular-nums">
        {ticks.map((i, k) => (
          <span key={i} className={`absolute ${k === 0 ? "left-0" : k === 2 ? "right-0" : "-translate-x-1/2"}`}
                style={k === 1 ? { left: `${(i / (pts.length - 1)) * 100}%` } : undefined}>
            {clockLabel(pts[i].time, is24)}
          </span>
        ))}
      </div>
    </div>
  );
}
