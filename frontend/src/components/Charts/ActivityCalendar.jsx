// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// GitHub-style activity calendar heatmap. Renders a year of daily activity as a
// grid of week columns, each day shaded by intensity level (theme-aware colour
// ramps in LEVELS_DARK/LIGHT), plus a compact month strip. Pure presentational —
// takes the per-day values as props and lays out the SVG/grid with the sizing
// constants below.
import { useMemo } from "react";
import { useTheme } from "../../context/ThemeContext";

const GAP     = 3;
const MCELL_H = 20;
const MGAP    = 4;

const MAX_CELL = 28;

const LEVELS_DARK = ["#1e293b", "#1e3a6e", "#1d4ed8", "#3b82f6", "#93c5fd"];
const LEVELS_LIGHT = ["#ebedf0", "#9be9a8", "#40c463", "#30a14e", "#216e39"];

const MONTH_NAMES = ["Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"];
const DAY_LABELS  = ["Mon","Tue","Wed","Thu","Fri","Sat","Sun"];

function toStr(d) {
  return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,"0")}-${String(d.getDate()).padStart(2,"0")}`;
}

function levelFor(count, maxCount) {
  if (!count || !maxCount) return 0;
  const r = count / maxCount;
  if (r < 0.2)  return 1;
  if (r < 0.45) return 2;
  if (r < 0.75) return 3;
  return 4;
}

// ── Weekly grid ───────────────────────────────────────────────────────────────

function buildWeeks(data, days) {
  const map = {};
  for (const { date, count } of data) map[date] = count;

  const today = new Date();
  const todayStr = toStr(today);
  const weeksToShow = Math.max(4, Math.ceil(days / 7) + 1);

  const start = new Date(today);
  start.setDate(start.getDate() - (weeksToShow - 1) * 7);
  start.setDate(start.getDate() - ((start.getDay() + 6) % 7));

  const weeks = [];
  const cur = new Date(start);
  while (toStr(cur) <= todayStr) {
    const week = [];
    for (let d = 0; d < 7; d++) {
      const str = toStr(cur);
      week.push({ date: str, count: map[str] ?? 0 });
      cur.setDate(cur.getDate() + 1);
    }
    weeks.push(week);
  }
  const maxCount = Math.max(1, ...weeks.flat().map(d => d.count));
  return { weeks, maxCount };
}

function WeekGrid({ data, days, levels }) {
  const { weeks, maxCount } = useMemo(() => buildWeeks(data, days), [data, days]);

  // Build a map of week-index → month label, placed at the middle week of each month
  const weekLabels = useMemo(() => {
    const spans = [];
    weeks.forEach((week, wi) => {
      const dt   = new Date(week[0].date + "T00:00:00");
      const prev = wi > 0 ? new Date(weeks[wi - 1][0].date + "T00:00:00") : null;
      if (!prev || dt.getMonth() !== prev.getMonth()) {
        if (spans.length > 0) spans[spans.length - 1].end = wi - 1;
        spans.push({ start: wi, end: weeks.length - 1, dt });
      }
    });
    const map = {};
    for (const span of spans) {
      map[Math.floor((span.start + span.end) / 2)] =
        span.dt.toLocaleDateString(undefined, { month: "short" });
    }
    return map;
  }, [weeks]);

  const colStyle = {
    flex: 1, minWidth: 0, maxWidth: MAX_CELL,
    display: "flex", flexDirection: "column", gap: GAP,
  };

  return (
    <div style={{ width: "100%", display: "flex", flexDirection: "column", flex: 1 }}>
      {/* Month label row */}
      <div style={{ display: "flex", gap: GAP, marginBottom: 8 }}>
        <div style={{ width: 26, flexShrink: 0 }} />
        {weeks.map((week, wi) => {
          const dt      = new Date(week[0].date + "T00:00:00");
          const prev    = wi > 0 ? new Date(weeks[wi - 1][0].date + "T00:00:00") : null;
          const newMonth = wi > 0 && dt.getMonth() !== prev.getMonth();
          return (
            <div key={wi} style={{ flex: 1, minWidth: 0, maxWidth: MAX_CELL, height: 14, overflow: "visible", textAlign: "center", marginLeft: newMonth ? 5 : 0 }}>
              {weekLabels[wi] && (
                <span style={{ fontSize: 10, color: "#94a3b8", whiteSpace: "nowrap" }}>
                  {weekLabels[wi]}
                </span>
              )}
            </div>
          );
        })}
      </div>

      {/* Grid: day-label column + week columns — flex:1 fills available height */}
      <div style={{ display: "flex", gap: GAP, flex: 1 }}>
        {/* Day labels — stretch to fill column height */}
        <div style={{ width: 26, flexShrink: 0, display: "flex", flexDirection: "column", gap: GAP }}>
          {DAY_LABELS.map((label, i) => (
            <div
              key={i}
              style={{
                flex: 1,
                fontSize: 9, color: "#94a3b8",
                textAlign: "right", paddingRight: 4,
                display: "flex", alignItems: "center", justifyContent: "flex-end",
              }}
            >
              {label}
            </div>
          ))}
        </div>

        {/* Week columns — cells grow to fill height */}
        {weeks.map((week, wi) => {
          const dt      = new Date(week[0].date + "T00:00:00");
          const prev    = wi > 0 ? new Date(weeks[wi - 1][0].date + "T00:00:00") : null;
          const newMonth = wi > 0 && dt.getMonth() !== prev.getMonth();
          return (
          <div key={wi} style={{ ...colStyle, marginLeft: newMonth ? 5 : 0 }}>
            {week.map((day, di) => {
              const label = new Date(day.date + "T00:00:00").toLocaleDateString(undefined, {
                weekday: "short", month: "short", day: "numeric",
              });
              return (
                <div
                  key={di}
                  title={day.count ? `${label} — ${day.count} ${day.count === 1 ? "activity" : "activities"}` : label}
                  style={{
                    width: "100%",
                    flex: 1,
                    minHeight: 8,
                    borderRadius: 3,
                    backgroundColor: levels[levelFor(day.count, maxCount)],
                  }}
                />
              );
            })}
          </div>
          );
        })}
      </div>
    </div>
  );
}

// ── Year × month matrix (lifetime view) ───────────────────────────────────────

function buildYearMatrix(data) {
  const countMap = {};
  for (const { date, count } of data) {
    const key = date.slice(0, 7);
    countMap[key] = (countMap[key] ?? 0) + count;
  }

  const keys = Object.keys(countMap).sort();
  if (!keys.length) return { years: [], maxCount: 1 };

  const minYear = parseInt(keys[0].slice(0, 4));
  const maxYear = parseInt(keys[keys.length - 1].slice(0, 4));
  const maxCount = Math.max(1, ...Object.values(countMap));

  const years = [];
  for (let y = minYear; y <= maxYear; y++) {
    const months = Array.from({ length: 12 }, (_, m) => {
      const key = `${y}-${String(m + 1).padStart(2, "0")}`;
      return { key, year: y, month: m, count: countMap[key] ?? 0 };
    });
    years.push({ year: y, months });
  }

  return { years, maxCount };
}

function YearMatrix({ data, levels }) {
  const { years, maxCount } = useMemo(() => buildYearMatrix(data), [data]);
  if (!years.length) return null;

  return (
    <div style={{ width: "100%", display: "flex", flexDirection: "column", flex: 1 }}>
      {/* Month headers */}
      <div style={{ display: "flex", gap: MGAP, marginBottom: 4 }}>
        <div style={{ width: 36, flexShrink: 0 }} />
        {MONTH_NAMES.map(m => (
          <div key={m} style={{ flex: 1, minWidth: 0, fontSize: 9, color: "#94a3b8", textAlign: "center" }}>
            {m}
          </div>
        ))}
      </div>

      {/* Year rows — flex: 1 so rows grow to fill available height */}
      <div style={{ display: "flex", flexDirection: "column", flex: 1, gap: MGAP }}>
        {years.map(({ year, months }) => (
          <div key={year} style={{ display: "flex", alignItems: "stretch", gap: MGAP, flex: 1, minHeight: MCELL_H }}>
            <div style={{ width: 36, flexShrink: 0, fontSize: 10, color: "#94a3b8", textAlign: "right", paddingRight: 4, display: "flex", alignItems: "center", justifyContent: "flex-end" }}>
              {year}
            </div>
            {months.map(({ key, month, count }) => (
              <div
                key={key}
                title={`${MONTH_NAMES[month]} ${year}: ${count} ${count === 1 ? "activity" : "activities"}`}
                style={{
                  flex: 1, minWidth: 0,
                  borderRadius: 4,
                  backgroundColor: levels[levelFor(count, maxCount)],
                }}
              />
            ))}
          </div>
        ))}
      </div>
    </div>
  );
}

// ── Main component ────────────────────────────────────────────────────────────

export default function ActivityCalendar({ data = [], days = null }) {
  const { colorScheme } = useTheme();
  const systemDark = window.matchMedia("(prefers-color-scheme: dark)").matches;
  const isDark = colorScheme === "dark" || (colorScheme === "system" && systemDark);
  const levels = isDark ? LEVELS_DARK : LEVELS_LIGHT;
  const isLifetime = days === null;

  const dateRange = useMemo(() => {
    if (!data.length) return null;
    const sorted = [...data].sort((a, b) => a.date.localeCompare(b.date));
    if (isLifetime) {
      const y0 = sorted[0].date.slice(0, 4);
      const y1 = sorted[sorted.length - 1].date.slice(0, 4);
      return y0 === y1 ? y0 : `${y0} – ${y1}`;
    }
    const fmt = (str) =>
      new Date(str + "T00:00:00").toLocaleDateString(undefined, {
        day: "numeric", month: "short", year: "numeric",
      });
    return `${fmt(sorted[0].date)} – ${fmt(sorted[sorted.length - 1].date)}`;
  }, [data, isLifetime]);

  return (
    <div style={{ width: "100%", display: "flex", flexDirection: "column", flex: 1, gap: 12 }}>
      <div className="flex items-center justify-between gap-4">
        <p className="text-xs font-semibold text-slate-400 dark:text-slate-500 uppercase tracking-wider shrink-0">
          Activity Contributions
        </p>
        {dateRange && (
          <span className="text-xs text-slate-400 dark:text-slate-500">{dateRange}</span>
        )}
      </div>

      {isLifetime ? <YearMatrix data={data} levels={levels} /> : <WeekGrid data={data} days={days} levels={levels} />}

      {/* Legend */}
      <div style={{ display: "flex", alignItems: "center", gap: 6, fontSize: 10, color: "#94a3b8" }}>
        <span>Less</span>
        {levels.map((c, i) => (
          <div
            key={i}
            style={{
              width: 14, height: isLifetime ? MCELL_H : 14,
              borderRadius: isLifetime ? 4 : 3,
              backgroundColor: c,
            }}
          />
        ))}
        <span>More</span>
      </div>
    </div>
  );
}
