// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import React from "react";
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Cell } from "recharts";
import { CHART_GRID } from "../../../design/chartGrid";

/**
 * IntensityZoneChart - Shows time distribution across heart rate zones
 */
export function IntensityZoneChart({ data, maxHR = 200, imperial = false }) {
  if (!data?.length) return null;

  // Define HR zones based on maxHR
  const zones = [
    { name: "Zone 1", label: "Recovery", min: 0, max: 0.6 * maxHR, color: "#22c55e" },
    { name: "Zone 2", label: "Aerobic", min: 0.6 * maxHR, max: 0.7 * maxHR, color: "#84cc16" },
    { name: "Zone 3", label: "Tempo", min: 0.7 * maxHR, max: 0.8 * maxHR, color: "#eab308" },
    { name: "Zone 4", label: "Threshold", min: 0.8 * maxHR, max: 0.9 * maxHR, color: "#f97316" },
    { name: "Zone 5", label: "VO2 Max", min: 0.9 * maxHR, max: maxHR, color: "#ef4444" },
  ];

  // Calculate time in each zone from track data
  const zoneData = zones.map(zone => ({
    ...zone,
    seconds: data.reduce((sum, point) => {
      if (point.heart_rate >= zone.min && point.heart_rate < zone.max) {
        return sum + (point.duration || 1);
      }
      return sum;
    }, 0),
  }));

  const totalSeconds = zoneData.reduce((sum, z) => sum + z.seconds, 0);

  function formatDuration(sec) {
    if (!sec) return "0m";
    const m = Math.round(sec / 60);
    if (m >= 60) {
      const h = Math.floor(m / 60);
      const remM = m % 60;
      return `${h}h ${remM}m`;
    }
    return `${m}m`;
  }

  return (
    <div className="card">
      <div className="flex items-center gap-1.5 mb-3">
        <p className="section-title">Intensity Zones</p>
      </div>
      <ResponsiveContainer width="100%" height={200}>
        <BarChart data={zoneData} margin={{ top: 10, right: 20, bottom: 30, left: 0 }}>
          <CartesianGrid {...CHART_GRID} />
          <XAxis 
            dataKey="name" 
            tick={{ fontSize: 10, fill: "#94a3b8" }} 
            axisLine={false} 
            tickLine={false}
            angle={-45}
            textAnchor="end"
            height={60}
          />
          <YAxis 
            tick={{ fontSize: 10, fill: "#94a3b8" }} 
            axisLine={false} 
            tickLine={false}
            tickFormatter={formatDuration}
          />
          <Tooltip
            contentStyle={{ background: "#0f172a", border: "none", borderRadius: 8, fontSize: 11 }}
            labelStyle={{ color: "#94a3b8" }}
            formatter={(value, name, props) => {
              const idx = props?.payload?.index;
              if (idx == null) return [value, name];
              const zone = zoneData[idx];
              if (!zone) return [value, name];
              const pct = totalSeconds > 0 ? ((value / totalSeconds) * 100).toFixed(1) : "0";
              return [`${formatDuration(value)} (${pct}%)`, zone.label];
            }}
          />
          <Bar dataKey="seconds" radius={[4, 4, 0, 0]}>
            {zoneData.map((entry, index) => (
              <Cell key={index} fill={entry.color} />
            ))}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
      <div className="flex flex-wrap justify-center gap-3 mt-3">
        {zoneData.map((zone, i) => (
          <div key={i} className="flex items-center gap-1.5">
            <span className="w-2.5 h-2.5 rounded-sm" style={{ backgroundColor: zone.color }} />
            <span className="text-[10px] text-slate-500 dark:text-slate-400">{zone.label}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

export default IntensityZoneChart;