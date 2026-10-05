// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useMemo, useState } from "react";
import { PieChart, Pie, Cell, Sector, ResponsiveContainer } from "recharts";

const PALETTE = [
  "#06b6d4", "#22c55e", "#f97316", "#f59e0b",
  "#ef4444", "#a855f7", "#3b82f6", "#ec4899",
  "#14b8a6", "#84cc16", "#8b5cf6", "#f43f5e",
];

const PAGE_SIZE = 5;

function ActiveShape({ cx, cy, innerRadius, outerRadius, startAngle, endAngle, fill }) {
  return (
    <Sector
      cx={cx} cy={cy}
      innerRadius={innerRadius - 2}
      outerRadius={outerRadius + 10}
      startAngle={startAngle}
      endAngle={endAngle}
      fill={fill}
    />
  );
}

function OuterLabel({ cx, cy, midAngle, outerRadius, name, value, index }) {
  const RADIAN = Math.PI / 180;
  const r = outerRadius + 26;
  const x = cx + r * Math.cos(-midAngle * RADIAN);
  const y = cy + r * Math.sin(-midAngle * RADIAN);
  return (
    <text
      x={x} y={y}
      fill={PALETTE[index % PALETTE.length]}
      textAnchor={x > cx ? "start" : "end"}
      dominantBaseline="central"
      fontSize={10}
    >
      {`${name}: ${value}`}
    </text>
  );
}

export default function SportBreakdown({ data = [], selectedSport = "", onSportSelect = () => {} }) {
  const [page, setPage] = useState(0);
  const [hoveredIndex, setHoveredIndex] = useState(-1);

  const total       = useMemo(() => data.reduce((s, d) => s + d.activity_count, 0), [data]);
  const pages       = Math.ceil(data.length / PAGE_SIZE);
  const legend      = data.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE);
  const activeIndex = useMemo(
    () => selectedSport ? data.findIndex(d => d.sport === selectedSport) : -1,
    [selectedSport, data],
  );

  const effectiveActiveIndices = useMemo(() => {
    const indices = [];
    if (activeIndex >= 0) indices.push(activeIndex);
    if (hoveredIndex >= 0 && hoveredIndex !== activeIndex) indices.push(hoveredIndex);
    return indices.length > 0 ? indices : undefined;
  }, [activeIndex, hoveredIndex]);

  // Scroll legend page to show the active sport
  useEffect(() => {
    if (activeIndex >= 0) setPage(Math.floor(activeIndex / PAGE_SIZE));
  }, [activeIndex]);

  function handleClick(entry) {
    onSportSelect(selectedSport === entry.sport ? "" : entry.sport);
  }

  if (!data.length) {
    return (
      <div className="flex items-center justify-center h-48 text-sm text-slate-400 dark:text-slate-500">
        No activity data
      </div>
    );
  }

  return (
    <div className="space-y-2">
      <p className="text-xs font-semibold text-slate-400 dark:text-slate-500 uppercase tracking-wider">
        Activity Types
      </p>

      <div className="relative">
        <ResponsiveContainer width="100%" height={260}>
          <PieChart margin={{ top: 20, right: 20, bottom: 20, left: 20 }}>
            <Pie
              data={data}
              dataKey="activity_count"
              nameKey="sport"
              cx="50%"
              cy="50%"
              innerRadius={65}
              outerRadius={100}
              paddingAngle={2}
              label={OuterLabel}
              labelLine={{ stroke: "#64748b", strokeWidth: 1 }}
              isAnimationActive={false}
              activeIndex={effectiveActiveIndices}
              activeShape={ActiveShape}
              onClick={(entry) => handleClick(entry)}
              onMouseEnter={(_, index) => setHoveredIndex(index)}
              onMouseLeave={() => setHoveredIndex(-1)}
              style={{ cursor: "pointer" }}
            >
              {data.map((entry, i) => (
                <Cell
                  key={entry.sport}
                  fill={PALETTE[i % PALETTE.length]}
                  opacity={activeIndex >= 0 && activeIndex !== i ? 0.3 : 1}
                />

              ))}
            </Pie>
          </PieChart>
        </ResponsiveContainer>

        {/* Center: total count or clear button */}
        <div className="absolute inset-0 flex flex-col items-center justify-center pointer-events-none">
          {selectedSport ? (
            <button
              className="pointer-events-auto w-12 h-12 rounded-full bg-slate-700 hover:bg-red-500 flex items-center justify-center transition-colors"
              onClick={() => onSportSelect("")}
            >
              <span className="text-slate-300 text-sm leading-none">✕</span>
            </button>
          ) : (
            <>
              <span className="text-2xl font-bold text-slate-800 dark:text-white">{total}</span>
              <span className="text-xs text-slate-400 dark:text-slate-500">Activities</span>
            </>
          )}
        </div>
      </div>

      {/* Paginated legend */}
      <div className="flex items-center gap-2">
        <div className="flex flex-wrap gap-x-3 gap-y-1 flex-1">
          {legend.map((d, i) => {
            const globalIndex = page * PAGE_SIZE + i;
            return (
              <div
                key={d.sport}
                className="flex items-center gap-1.5 text-xs text-slate-600 dark:text-slate-400"
              >
                <span
                  className="inline-block w-3 h-3 rounded-sm shrink-0"
                  style={{ background: PALETTE[globalIndex % PALETTE.length] }}
                />
                {d.sport}
              </div>
            );
          })}
        </div>
        {pages > 1 && (
          <div className="flex items-center gap-1 shrink-0">
            <button
              onClick={() => setPage(p => Math.max(0, p - 1))}
              disabled={page === 0}
              className="text-slate-400 hover:text-slate-600 disabled:opacity-30 text-xs px-1"
            >◄</button>
            <button
              onClick={() => setPage(p => Math.min(pages - 1, p + 1))}
              disabled={page === pages - 1}
              className="text-slate-400 hover:text-slate-600 disabled:opacity-30 text-xs px-1"
            >►</button>
          </div>
        )}
      </div>
    </div>
  );
}
