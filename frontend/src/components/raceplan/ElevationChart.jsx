// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Course elevation profile for the Race Plan page. Computes cumulative distance
// (haversine) vs. elevation from the [lat, lon, ele?] course path and renders a
// compact Recharts area chart. Returns null when the path carries no elevation.

import { useMemo } from "react";
import { AreaChart, Area, XAxis, YAxis, Tooltip, ResponsiveContainer } from "recharts";

export default function ElevationChart({ coursePath, isDark, imperial = false }) {
  const data = useMemo(() => {
    if (!coursePath?.length) return null;
    const hasEle = coursePath.some(p => p[2] != null);
    if (!hasEle) return null;

    let cumDist = 0;
    const R = 6_371_000;
    const pts = [];
    for (let i = 0; i < coursePath.length; i++) {
      if (i > 0) {
        const [lat1, lon1] = coursePath[i - 1];
        const [lat2, lon2] = coursePath[i];
        const dLat = (lat2 - lat1) * Math.PI / 180;
        const dLon = (lon2 - lon1) * Math.PI / 180;
        const a = Math.sin(dLat/2)**2 + Math.cos(lat1*Math.PI/180)*Math.cos(lat2*Math.PI/180)*Math.sin(dLon/2)**2;
        cumDist += R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
      }
      const ele = coursePath[i][2];
      if (ele != null) {
        const dist = imperial
          ? +(cumDist / 1609.344).toFixed(2)
          : +(cumDist / 1000).toFixed(2);
        pts.push({ dist, ele: Math.round(ele) });
      }
    }
    return pts.length > 1 ? pts : null;
  }, [coursePath, imperial]);

  if (!data) return null;

  const elevations = data.map(d => d.ele);
  const minEle = Math.min(...elevations);
  const maxEle = Math.max(...elevations);
  const padding = Math.max(10, (maxEle - minEle) * 0.15);
  const stroke = isDark ? "#8b5cf6" : "#7c3aed";

  return (
    <div style={{ height: 88 }} className="w-full mt-2 rounded-lg overflow-hidden">
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={data} margin={{ top: 4, right: 4, left: 0, bottom: 0 }}>
          <defs>
            <linearGradient id="eleGrad" x1="0" y1="0" x2="0" y2="1">
              <stop offset="5%"  stopColor={stroke} stopOpacity={0.3} />
              <stop offset="95%" stopColor={stroke} stopOpacity={0.02} />
            </linearGradient>
          </defs>
          <XAxis dataKey="dist" hide />
          <YAxis domain={[minEle - padding, maxEle + padding]} hide />
          <Tooltip
            contentStyle={{
              background: isDark ? "#1e293b" : "#fff",
              border: "1px solid " + (isDark ? "#334155" : "#e2e8f0"),
              borderRadius: 6,
              fontSize: 11,
              padding: "3px 8px",
            }}
            labelStyle={{ color: isDark ? "#94a3b8" : "#475569" }}
            itemStyle={{ color: isDark ? "#e2e8f0" : "#1e293b" }}
            formatter={v => [`${v} m`, "Elevation"]}
            labelFormatter={v => `${v} ${imperial ? "mi" : "km"}`}
          />
          <Area
            type="monotone" dataKey="ele"
            stroke={stroke} strokeWidth={1.5}
            fill="url(#eleGrad)" dot={false} isAnimationActive={false}
          />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}
