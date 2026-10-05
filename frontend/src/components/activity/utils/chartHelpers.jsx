// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Chart utilities and helpers - moved from chartComponents.jsx

import React, { useState, useRef } from "react";
import { fmtElapsed, hrColor } from "../../../utils/formatUtils";

/**
 * HRColorLine - Heart rate colored line rendered as raw SVG
 */
export const HRColorLine = React.memo(function HRColorLine({ xAxisMap, yAxisMap, formData, maxHR }) {
  if (!formData?.length || !xAxisMap || !yAxisMap) return null;
  const xAxis = Object.values(xAxisMap)[0], yAxis = Object.values(yAxisMap)[0];
  if (!xAxis?.scale || !yAxis?.scale) return null;
  const bw = xAxis.scale.bandwidth?.() ?? 0;

  function px(d) {
    const x = xAxis.scale(d.elapsed), y = d.hr == null ? null : yAxis.scale(d.hr);
    if (x == null || y == null) return null;
    return [x + bw / 2, y];
  }

  const lines = [];
  for (let i = 0; i < formData.length - 1; i++) {
    const a = px(formData[i]), b = px(formData[i + 1]);
    if (!a || !b) continue;
    const t = maxHR ? Math.max(0, Math.min(1, (formData[i].hr || 0) / maxHR)) : 0.5;
    lines.push(
      <line key={i} x1={a[0]} y1={a[1]} x2={b[0]} y2={b[1]}
        stroke={hrColor(t)} strokeWidth={2} strokeLinecap="round" />
    );
  }
  return <g>{lines}</g>;
});

/**
 * TimeTooltip - Chart tooltip with time formatting
 */
export function TimeTooltip({ active, payload, label, format }) {
  if (!active || !payload?.length) return null;
  const val = payload[0]?.value;
  return (
    <div className="bg-slate-900/90 text-white text-xs rounded-lg px-2.5 py-1.5 shadow-lg pointer-events-none">
      <p className="text-slate-400 mb-1">{fmtElapsed(label)}</p>
      <p className="font-medium">{format ? format(val) : (val != null ? Number(val).toFixed(1) : "—")}</p>
    </div>
  );
}

/**
 * ChartCard - Wrapper card for charts
 */
export function ChartCard({ title, action, children }) {
  return (
    <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 p-3.5">
      <div className="flex items-center gap-1.5 mb-3">
        <p className="text-xs font-semibold text-slate-400 dark:text-slate-500 uppercase tracking-wider">{title}</p>
        {action}
      </div>
      {children}
    </div>
  );
}

/**
 * InfoTooltip - Hover-reveal info popover
 */
export function InfoTooltip({ children }) {
  const [open, setOpen] = useState(false);
  const [openLeft, setOpenLeft] = useState(false);
  const btnRef = useRef(null);

  function handleEnter() {
    if (btnRef.current) {
      const rect = btnRef.current.getBoundingClientRect();
      setOpenLeft(rect.left > window.innerWidth / 2);
    }
    setOpen(true);
  }

  return (
    <span className="relative inline-flex shrink-0">
      <button
        ref={btnRef}
        onMouseEnter={handleEnter}
        onMouseLeave={() => setOpen(false)}
        className="w-4 h-4 rounded-full bg-slate-200 dark:bg-slate-700 text-slate-500 dark:text-slate-400 text-[10px] font-bold flex items-center justify-center hover:bg-slate-300 dark:hover:bg-slate-600 transition-colors"
      >
        ?
      </button>
      {open && (
        <div className={`absolute top-6 ${openLeft ? "right-0" : "left-0"} w-72 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-lg p-2.5 shadow-xl z-20 text-xs text-slate-600 dark:text-slate-300 space-y-2 pointer-events-none`}>
          {children}
        </div>
      )}
    </span>
  );
}

// Chart common props
export const xAxisProps = {
  dataKey: "elapsed", type: "number", domain: ["dataMin", "dataMax"],
  tickFormatter: fmtElapsed, tickCount: 6,
  tick: { fontSize: 10, fill: "#94a3b8" }, axisLine: false, tickLine: false,
};

export const yAxisProps = {
  tick: { fontSize: 10, fill: "#94a3b8" }, axisLine: false, tickLine: false, width: 42,
};

export const gridProps = { stroke: "#1e293b", strokeDasharray: "3 3" };