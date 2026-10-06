// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Chart utilities and helpers - moved from chartComponents.jsx

import React from "react";
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
    <div className="card">
      <div className="flex items-center gap-1.5 mb-3">
        <p className="section-title">{title}</p>
        {action}
      </div>
      {children}
    </div>
  );
}

// The "?" explainer is the app's shared one; re-exported for the charts that
// import it from here.
export { default as InfoTooltip } from "../../ui/InfoTooltip";

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