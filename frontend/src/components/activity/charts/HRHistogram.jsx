// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import React, { useMemo } from "react";
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Cell, ReferenceLine } from "recharts";
import { yAxisProps, gridProps } from "../utils/chartHelpers.jsx";
import { hrColor } from "../../../utils/formatUtils";
import { HR_MODELS } from "../../../spec/zones";

// The five-zone %HRmax *display* model from the shared spec (spec/zones.yaml).
// Deliberately not the Friel LTHR zones the backend uses for training load:
// this one needs only max HR, so it works for every activity, and it answers
// "how hard was this" at a glance rather than feeding a calculation. The spec
// documents why both models exist.
//
// `pct` here is each zone's upper bound, which is what the binning below wants;
// the spec stores min/max per zone, so the last zone's null max becomes 1.00 to
// keep the old shape (the top bin is open-ended in practice — nothing above
// max HR is expected).
const HR_ZONES = HR_MODELS.display_maxhr.zones.map((z) => ({
  label: `${z.name} ${z.description}`,
  pct: z.max_pct ?? 1.0,
  color: z.color,
}));

function getZoneColor(hr, maxHR) {
  const pct = hr / maxHR;
  const zone = HR_MODELS.display_maxhr.zones.find(
    (z) => z.max_pct === null || pct < z.max_pct,
  );
  return zone.color;
}

export const HRHistogram = React.memo(function HRHistogram({ data, maxHR = 200 }) {
  if (!data?.length) return null;

  // Compute time in each zone from histogram data
  const zoneSeconds = useMemo(() => {
    return HR_ZONES.map((zone, i) => {
      const lo = i === 0 ? 0 : HR_ZONES[i - 1].pct * maxHR;
      const hi = zone.pct * maxHR;
      const secs = data
        .filter(bin => bin.hr >= lo && bin.hr < hi)
        .reduce((s, bin) => s + bin.count, 0);
      return { ...zone, seconds: secs };
    });
  }, [data, maxHR]);

  const totalSecs = zoneSeconds.reduce((s, z) => s + z.seconds, 0);

  function fmtZoneTime(sec) {
    if (!sec) return "—";
    const m = Math.round(sec / 60);
    if (m >= 60) return `${Math.floor(m / 60)}h ${m % 60}m`;
    return `${m}m`;
  }

  // Zone boundary reference lines — align to nearest 2-bpm bin start
  const zoneBoundaries = HR_ZONES.slice(0, -1).map(z => ({
    binStart: Math.floor((z.pct * maxHR) / 2) * 2,
    color: z.color,
  }));

  return (
    <div className="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 p-3.5">
      <div className="flex items-center gap-1.5 mb-3">
        <p className="text-xs font-semibold text-slate-600 dark:text-slate-300 uppercase tracking-wider">
          HR Distribution
        </p>
      </div>

      <ResponsiveContainer width="100%" height={160}>
        <BarChart data={data} margin={{ top: 4, right: 8, bottom: 0, left: 0 }}>
          <CartesianGrid {...gridProps} vertical={false} />
          <XAxis
            dataKey="range"
            tick={{ fontSize: 8, fill: "#94a3b8" }}
            axisLine={false}
            tickLine={false}
            interval={3}
          />
          <YAxis
            {...yAxisProps}
            label={{ value: "sec", angle: -90, position: "insideLeft", fill: "#94a3b8", fontSize: 10, dy: 12 }}
          />
          {/* Zone boundary lines */}
          {zoneBoundaries.map(({ binStart, color }, i) => {
            const rangeLabel = `${binStart}–${binStart + 2}`;
            // Only render ReferenceLine if the data contains this range
            const hasRange = data.some(bin => bin.range === rangeLabel);
            return hasRange ? (
              <ReferenceLine
                key={i}
                x={rangeLabel}
                stroke={color}
                strokeWidth={1.5}
                strokeDasharray="4 3"
              />
            ) : null;
          })}
          <Tooltip
            contentStyle={{ background: "#0f172a", border: "none", borderRadius: 8, fontSize: 11, color: "#f1f5f9" }}
            itemStyle={{ color: "#f1f5f9" }}
            formatter={(v, _, props) => {
              const hr = props?.payload?.hr;
              const zone = HR_ZONES.find((z, i) => {
                const lo = i === 0 ? 0 : HR_ZONES[i - 1].pct * maxHR;
                return hr >= lo && hr < z.pct * maxHR;
              });
              return [`${v} sec`, zone ? zone.label : "HR"];
            }}
          />
          <Bar dataKey="count" radius={[2, 2, 0, 0]} isAnimationActive={false}>
            {data.map((entry, i) => (
              <Cell key={i} fill={getZoneColor(entry.hr, maxHR)} />
            ))}
          </Bar>
        </BarChart>
      </ResponsiveContainer>

      {/* Zone legend with time-in-zone */}
      <div className="mt-3 space-y-1">
        {zoneSeconds.map((zone, i) => {
          const pct = totalSecs > 0 ? ((zone.seconds / totalSecs) * 100).toFixed(0) : 0;
          return (
            <div key={i} className="flex items-center gap-2">
              <span className="w-2.5 h-2.5 rounded-sm shrink-0" style={{ backgroundColor: zone.color }} />
              <span className="text-[10px] text-slate-500 dark:text-slate-400 flex-1">{zone.label}</span>
              <span className="text-[10px] text-slate-400 dark:text-slate-500 tabular-nums">{fmtZoneTime(zone.seconds)}</span>
              <div className="w-16 h-1.5 bg-slate-100 dark:bg-slate-800 rounded-full overflow-hidden">
                <div
                  className="h-full rounded-full"
                  style={{ width: `${pct}%`, backgroundColor: zone.color }}
                />
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
});

export default HRHistogram;
