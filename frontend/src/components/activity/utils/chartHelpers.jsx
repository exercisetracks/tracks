// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Chart utilities and helpers - moved from chartComponents.jsx

import React, { createContext, useContext } from "react";
import { fmtElapsed } from "../../../utils/formatUtils";
import { HR_MODELS } from "../../../spec/zones";

/**
 * The user's own max heart rate (Settings, set or worked out from history),
 * provided by ActivityView so every HR chart colours by the same zones on
 * every activity. Null when unknown; charts then fall back to the activity's
 * own peak, which is what they all used before.
 */
export const UserMaxHRContext = createContext(null);

/** The max HR a chart should zone against: the user's, else [fallback]. */
export function useZoneMaxHR(fallback) {
  return useContext(UserMaxHRContext) ?? fallback;
}

/** The colour of the display zone (spec/zones.yaml display_maxhr) [hr] falls in. */
export function hrZoneColor(hr, maxHR) {
  const pct = maxHR ? hr / maxHR : 0;
  const zones = HR_MODELS.display_maxhr.zones;
  return (zones.find((z) => z.max_pct == null || pct < z.max_pct) ?? zones[zones.length - 1]).color;
}

/**
 * HRColorLine - Heart rate line rendered as raw SVG, each stretch in the colour
 * of the heart-rate zone it is in — the user's zones (UserMaxHRContext), so the
 * colour changes exactly where the value crosses from one zone to the next.
 * The phone draws its HR stream the same way (DetailCards.kt rememberZoneLine).
 */
export const HRColorLine = React.memo(function HRColorLine({ xAxisMap, yAxisMap, formData, maxHR: activityMax }) {
  const maxHR = useZoneMaxHR(activityMax);
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
    lines.push(
      <line key={i} x1={a[0]} y1={a[1]} x2={b[0]} y2={b[1]}
        stroke={hrZoneColor(formData[i].hr || 0, maxHR)} strokeWidth={2} strokeLinecap="round" />
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
