// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// VO2max widget: trends the user's estimated VO2max over time as a recharts line
// with the fitness-classification bands (Poor→Elite, per the ZONES scale below)
// shaded behind it. Renders the current value on a shared radial gauge coloured
// by its classification zone, and opens a history popup when clicked.
import { useState, useEffect, useRef } from "react";
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip,
  ReferenceLine, ResponsiveContainer,
} from "recharts";
import RadialGauge, { zoneFor } from "./RadialGauge";

// ── Classification scale ──────────────────────────────────────────────────────

const ZONES = [
  { label: "Poor",      min: 20, max: 34, color: "#ef4444" },
  { label: "Fair",      min: 34, max: 42, color: "#f97316" },
  { label: "Good",      min: 42, max: 50, color: "#eab308" },
  { label: "Excellent", min: 50, max: 57, color: "#22c55e" },
  { label: "Superior",  min: 57, max: 65, color: "#06b6d4" },
  { label: "Elite",     min: 65, max: 80, color: "#a855f7" },
];

const VO2_DISPLAY_MIN = 20;
const VO2_DISPLAY_MAX = 80;
const LINE_COLOR      = "#06b6d4";

// ── History popup ─────────────────────────────────────────────────────────────

function fmtDate(iso) {
  return new Date(iso + "T00:00:00").toLocaleDateString(undefined, {
    month: "short", day: "numeric", year: "numeric",
  });
}

function fmtShortDate(iso) {
  return new Date(iso + "T00:00:00").toLocaleDateString(undefined, {
    month: "short", year: "2-digit",
  });
}

function HistoryPopup({ history, onClose }) {
  const overlayRef = useRef(null);

  useEffect(() => {
    function onKey(e) { if (e.key === "Escape") onClose(); }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose]);

  const min = history.length ? Math.min(...history.map(p => p.value)) - 3 : 30;
  const max = history.length ? Math.max(...history.map(p => p.value)) + 3 : 70;

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
      className="modal-backdrop"
      onClick={e => { if (e.target === overlayRef.current) onClose(); }}
    >
      <div className="modal max-w-2xl p-5">
        <div className="flex items-center justify-between mb-5">
          <div>
            <h2 className="modal-title">VO₂ Max History</h2>
            <p className="text-xs text-slate-400 mt-0.5">Garmin fitness estimate from activities</p>
          </div>
          <button
            onClick={onClose}
            className="icon-btn"
          >
            ×
          </button>
        </div>

        {/* Zone legend */}
        <div className="flex flex-wrap gap-2 mb-4">
          {ZONES.map(z => (
            <span key={z.label} className="flex items-center gap-1 text-[10px] text-slate-500 dark:text-slate-400">
              <span className="inline-block w-2 h-2 rounded-full" style={{ background: z.color }} />
              {z.label} ({z.min}–{z.max < 80 ? z.max : "+"})
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
                dataKey="value"
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

export default function Vo2MaxWidget({ history = [] }) {
  const [open, setOpen] = useState(false);

  const latest    = history.length ? history[history.length - 1] : null;
  const value     = latest?.value ?? null;
  const latestDate = latest?.date ?? null;

  return (
    <>
      <button
        onClick={() => setOpen(true)}
        className="card group h-full w-full p-2 flex flex-col items-center justify-center gap-1.5 transition-transform duration-150 hover:scale-[1.03] focus:outline-none focus:ring-2 focus:ring-cyan-500"
        title="Click to view VO₂ Max history"
      >
        <RadialGauge value={value} min={VO2_DISPLAY_MIN} max={VO2_DISPLAY_MAX} zones={ZONES} title="VO₂ MAX" />
        {latestDate && (
          <p className="text-[10px] text-slate-400 dark:text-slate-500 tabular-nums">
            {fmtDate(latestDate)}
          </p>
        )}
        {!value && (
          <p className="text-xs text-slate-400">No data</p>
        )}
      </button>

      {open && (
        <HistoryPopup history={history} onClose={() => setOpen(false)} />
      )}
    </>
  );
}
