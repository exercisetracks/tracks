// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Readiness widget: shows the athlete's current recovery/readiness score (0–100)
// on a circular gauge with recovery-classification bands (Low→Prime, per the
// ZONES scale below), and — like the VO2max widget — opens a history popup with
// the daily readiness trend when clicked. The score blends physiological markers
// (HRV, sleep, resting HR) with training load; see calculators/readiness.py.
import { useState, useEffect, useRef } from "react";
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip,
  ReferenceLine, ResponsiveContainer,
} from "recharts";
import RadialGauge, { zoneFor } from "./RadialGauge";

// ── Classification scale ──────────────────────────────────────────────────────
// Segment edges at 60 and 80 mirror the backend's own thresholds
// (≥80 "well recovered", ≥60 "moderate", <60 "low"); the top band is split into
// Good/Prime for a richer gauge.

const ZONES = [
  { label: "Low",      min: 0,  max: 40,  color: "#ef4444" },
  { label: "Fair",     min: 40, max: 60,  color: "#f97316" },
  { label: "Moderate", min: 60, max: 80,  color: "#eab308" },
  { label: "Good",     min: 80, max: 92,  color: "#22c55e" },
  { label: "Prime",    min: 92, max: 100, color: "#14b8a6" },
];

const READY_MIN   = 0;
const READY_MAX   = 100;
const LINE_COLOR  = "#14b8a6";

// ── History popup ─────────────────────────────────────────────────────────────

function fmtDate(iso) {
  return new Date(iso + "T00:00:00").toLocaleDateString(undefined, {
    month: "short", day: "numeric", year: "numeric",
  });
}

function fmtShortDate(iso) {
  return new Date(iso + "T00:00:00").toLocaleDateString(undefined, {
    month: "short", day: "numeric",
  });
}

function HistoryPopup({ history, onClose }) {
  const overlayRef = useRef(null);

  useEffect(() => {
    function onKey(e) { if (e.key === "Escape") onClose(); }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose]);

  const min = history.length ? Math.max(0, Math.min(...history.map(p => p.score)) - 5) : 0;
  const max = history.length ? Math.min(100, Math.max(...history.map(p => p.score)) + 5) : 100;

  const CustomTooltip = ({ active, payload, label }) => {
    if (!active || !payload?.length) return null;
    const v = payload[0]?.value;
    const zone = zoneFor(v, ZONES);
    return (
      <div className="bg-slate-900 border border-slate-700 rounded-lg px-2.5 py-1.5 text-xs shadow-xl pointer-events-none">
        <p className="text-slate-400 mb-1">{fmtDate(label)}</p>
        <p className="font-bold" style={{ color: zone.color }}>
          {Math.round(v)} <span className="font-normal opacity-70">— {zone.label}</span>
        </p>
      </div>
    );
  };

  return (
    <div
      ref={overlayRef}
      className="fixed inset-0 z-50 flex items-center justify-center p-3.5 bg-black/60 backdrop-blur-sm"
      onClick={e => { if (e.target === overlayRef.current) onClose(); }}
    >
      <div className="bg-white dark:bg-slate-900 rounded-2xl border border-slate-200 dark:border-slate-700 shadow-2xl w-full max-w-2xl p-5">
        <div className="flex items-center justify-between mb-5">
          <div>
            <h2 className="text-base font-bold text-slate-900 dark:text-white">Readiness History</h2>
            <p className="text-xs text-slate-400 mt-0.5">Daily recovery score from health data and training load</p>
          </div>
          <button
            onClick={onClose}
            className="w-7 h-7 rounded-full flex items-center justify-center text-slate-400 hover:text-slate-700 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors text-lg leading-none"
          >
            ×
          </button>
        </div>

        {/* Zone legend */}
        <div className="flex flex-wrap gap-2 mb-4">
          {ZONES.map(z => (
            <span key={z.label} className="flex items-center gap-1 text-[10px] text-slate-500 dark:text-slate-400">
              <span className="inline-block w-2 h-2 rounded-full" style={{ background: z.color }} />
              {z.label} ({z.min}–{z.max < 100 ? z.max : "+"})
            </span>
          ))}
        </div>

        {history.length === 0 ? (
          <div className="flex items-center justify-center h-48 text-sm text-slate-400">No data for this period</div>
        ) : (
          <ResponsiveContainer width="100%" height={260}>
            <LineChart data={history} margin={{ top: 8, right: 16, bottom: 0, left: 0 }}>
              <CartesianGrid stroke="#1e293b" strokeDasharray="3 3" />
              <XAxis
                dataKey="date"
                tick={{ fontSize: 10, fill: "#94a3b8" }}
                axisLine={false} tickLine={false}
                tickFormatter={fmtShortDate}
                tickCount={6}
              />
              <YAxis
                domain={[Math.floor(min), Math.ceil(max)]}
                tick={{ fontSize: 10, fill: "#94a3b8" }}
                axisLine={false} tickLine={false}
                width={32}
              />
              <Tooltip content={<CustomTooltip />} />
              {/* Zone reference lines */}
              {ZONES.slice(0, -1).map(z => (
                <ReferenceLine key={z.label} y={z.max}
                  stroke={z.color} strokeOpacity={0.3} strokeDasharray="3 3" strokeWidth={1} />
              ))}
              <Line
                type="monotone"
                dataKey="score"
                stroke={LINE_COLOR}
                strokeWidth={2}
                dot={{ r: 2, fill: LINE_COLOR, strokeWidth: 0 }}
                activeDot={{ r: 4 }}
              />
            </LineChart>
          </ResponsiveContainer>
        )}
      </div>
    </div>
  );
}

// ── Public widget ─────────────────────────────────────────────────────────────

export default function ReadinessWidget({ history = [] }) {
  const [open, setOpen] = useState(false);

  const latest     = history.length ? history[history.length - 1] : null;
  const value      = latest?.score ?? null;
  const latestDate = latest?.date ?? null;

  return (
    <>
      <button
        onClick={() => setOpen(true)}
        className="group h-full w-full bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 p-2 flex flex-col items-center justify-center gap-1.5 transition-transform duration-150 hover:scale-[1.03] focus:outline-none focus:ring-2 focus:ring-teal-500"
        title="Click to view readiness history"
      >
        <RadialGauge value={value} min={READY_MIN} max={READY_MAX} zones={ZONES} title="READINESS" />
        {latestDate && (
          <p className="text-[10px] text-slate-400 dark:text-slate-500 tabular-nums">
            {fmtDate(latestDate)}
          </p>
        )}
        {value == null && (
          <p className="text-xs text-slate-400">No data</p>
        )}
      </button>

      {open && (
        <HistoryPopup history={history} onClose={() => setOpen(false)} />
      )}
    </>
  );
}
