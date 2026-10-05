// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import React from "react";
import { ScatterChart, Scatter, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, ReferenceLine } from "recharts";

/**
 * PacePowerZoneChart - Shows effort distribution across pace/power zones
 * For running: shows time at different pace zones
 * For cycling: shows time at different power zones
 */
export function EffortZoneChart({ data, sportType = "running", imperial = false, maxHR = 200 }) {
  if (!data?.length) return null;

  if (sportType === "running") {
    // Pace zones (min/km or min/mi)
    const paceZones = [
      { name: "Z1", label: "Easy", max: 6, color: "#22c55e" },
      { name: "Z2", label: "Moderate", max: 5, color: "#84cc16" },
      { name: "Z3", label: "Tempo", max: 4.5, color: "#eab308" },
      { name: "Z4", label: "Threshold", max: 4, color: "#f97316" },
      { name: "Z5", label: "VO2 Max", max: 3.5, color: "#ef4444" },
    ];

    const zoneData = paceZones.map(zone => ({
      ...zone,
      count: data.filter(p => {
        const pace = imperial ? 1609.34 / (p.speed * 60) : 1000 / (p.speed * 60);
        return pace < zone.max;
      }).length,
    }));

    return (
      <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 p-3.5">
        <div className="flex items-center gap-1.5 mb-3">
          <p className="text-xs font-semibold text-slate-400 dark:text-slate-500 uppercase tracking-wider">Pace Distribution</p>
        </div>
        <div className="grid grid-cols-5 gap-2">
          {zoneData.map((zone, i) => (
            <div key={i} className="text-center p-2.5 rounded-lg" style={{ backgroundColor: `${zone.color}20` }}>
              <div className="text-xs text-slate-500 dark:text-slate-400">{zone.label}</div>
              <div className="text-lg font-bold text-slate-800 dark:text-slate-200">{zone.count}</div>
              <div className="text-[10px] text-slate-400">seconds</div>
            </div>
          ))}
        </div>
      </div>
    );
  }

  // Cycling power zones (%FTP)
  if (sportType === "cycling") {
    const powerZones = [
      { name: "Z1", label: "Active Recovery", max: 0.55, color: "#22c55e" },
      { name: "Z2", label: "Endurance", max: 0.75, color: "#84cc16" },
      { name: "Z3", label: "Tempo", max: 0.85, color: "#eab308" },
      { name: "Z4", label: "Threshold", max: 0.95, color: "#f97316" },
      { name: "Z5", label: "VO2 Max", max: 1.05, color: "#ef4444" },
      { name: "Z6", label: "Anaerobic", max: 1.2, color: "#a855f7" },
      { name: "Z7", label: "Neuromuscular", max: 999, color: "#ec4899" },
    ];

    const avgPower = data.reduce((sum, p) => sum + (p.power || 0), 0) / data.filter(p => p.power).length || 100;
    const ftp = avgPower * 1.5; // Estimate FTP from average power

    const zoneData = powerZones.map(zone => ({
      ...zone,
      count: data.filter(p => {
        const pct = p.power / ftp;
        return pct >= (zone.max - (zone.name === "Z1" ? 0 : 0.15)) && pct < zone.max;
      }).length,
    }));

    return (
      <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 p-3.5">
        <div className="flex items-center gap-1.5 mb-3">
          <p className="text-xs font-semibold text-slate-400 dark:text-slate-500 uppercase tracking-wider">Power Distribution</p>
          <span className="text-xs text-slate-400">Est. FTP: {Math.round(ftp)}W</span>
        </div>
        <div className="grid grid-cols-7 gap-1">
          {zoneData.map((zone, i) => (
            <div key={i} className="text-center p-1.5 rounded-lg" style={{ backgroundColor: `${zone.color}20` }}>
              <div className="text-[10px] text-slate-500 dark:text-slate-400">{zone.label}</div>
              <div className="text-base font-bold text-slate-800 dark:text-slate-200">{zone.count}</div>
            </div>
          ))}
        </div>
      </div>
    );
  }

  return null;
}

export default EffortZoneChart;