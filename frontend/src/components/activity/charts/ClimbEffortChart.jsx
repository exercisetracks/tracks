// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import React from "react";
import { ComposedChart, Bar, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Cell } from "recharts";
import { hrColor } from "../../../utils/formatUtils";
import { ChartCard, InfoTooltip } from "../utils/chartHelpers.jsx";
import { CHART_GRID } from "../../../design/chartGrid";

/**
 * ClimbEffortChart - Displays effort/grade per climb with optional heart rate overlay
 */
export function ClimbEffortChart({ activeSplits, imperial }) {
  if (!activeSplits?.length) return null;

  const hasScore    = activeSplits.some(c => (c.difficulty_score ?? 0) > 0);
  const hasAscent   = activeSplits.some(c => (c.total_ascent ?? 0) > 0);
  const hasGrades   = activeSplits.some(c => c.grade_level != null);
  const sessionMaxHR = Math.max(0, ...activeSplits.map(c => c.max_heart_rate ?? 0));

  if (hasScore) {
    const ascentLabel = imperial ? "ft" : "m";
    const data = activeSplits.map((c, i) => ({
      idx:    i + 1,
      score:  c.difficulty_score ?? 0,
      ascent: imperial ? Math.round((c.total_ascent ?? 0) * 3.28084) : Math.round(c.total_ascent ?? 0),
      hr:     c.max_heart_rate,
    }));

    return (
      <ChartCard title="Effort Score per Route" action={
        <InfoTooltip>
          <p>Garmin's per-route effort score (0–100). Combines time on wall, vertical speed, and heart rate intensity.</p>
        </InfoTooltip>
      }>
        <ResponsiveContainer width="100%" height={200}>
          <ComposedChart data={data} margin={{ top: 4, right: 40, bottom: 0, left: 0 }}>
            <CartesianGrid {...CHART_GRID} />
            <XAxis dataKey="idx" tick={{ fontSize: 10, fill: "#94a3b8" }} axisLine={false} tickLine={false}
              label={{ value: "Climb #", position: "insideBottom", fill: "#94a3b8", fontSize: 9, dy: 6 }} />
            <YAxis tick={{ fontSize: 10, fill: "#94a3b8" }} axisLine={false} tickLine={false} width={42}
              domain={[0, 100]}
              label={{ value: "Effort", angle: -90, position: "insideLeft", fill: "#94a3b8", fontSize: 9, dy: 20 }} />
            {hasAscent && (
              <YAxis orientation="right" tick={{ fontSize: 10, fill: "#f97316" }} axisLine={false} tickLine={false} width={36}
                label={{ value: ascentLabel, position: "insideRight", fill: "#f97316", fontSize: 9 }} />
            )}
            <Tooltip
              contentStyle={{ background: "#0f172a", border: "none", borderRadius: 8, fontSize: 11 }}
              labelStyle={{ color: "#94a3b8" }}
              itemStyle={{ color: "#f1f5f9" }}
              labelFormatter={i => `Route ${i}`}
              formatter={(v, name) => {
                if (name === "score")  return [`${v} pts`, "Effort Score"];
                if (name === "ascent") return [`${v} ${ascentLabel}`, "Ascent"];
                return [v, name];
              }}
            />
            <Bar dataKey="score" radius={[3, 3, 0, 0]} name="score">
              {data.map((d, i) => <Cell key={i} fill={d.hr ? hrColor(Math.min(1, d.hr / 200)) : "#10b981"} />)}
            </Bar>
            {hasAscent && (
              <Line type="monotone" dataKey="ascent" stroke="#f97316" strokeWidth={2}
                dot={{ fill: "#f97316", r: 3 }} name="ascent" />
            )}
          </ComposedChart>
        </ResponsiveContainer>
      </ChartCard>
    );
  }

  if (hasGrades) {
    const data = activeSplits.map((c, i) => ({
      idx:    i + 1,
      grade:  c.grade_level ?? 0,
      hr:     c.max_heart_rate ?? 0,
      label:  `V${c.grade_level ?? 0}`,
      result: c.climb_result,
    }));
    const maxGrade = Math.max(...data.map(d => d.grade));

    return (
      <ChartCard title="Grade per Problem">
        <ResponsiveContainer width="100%" height={200}>
          <ComposedChart data={data} margin={{ top: 4, right: 40, bottom: 16, left: 0 }}>
            <CartesianGrid {...CHART_GRID} />
            <XAxis dataKey="idx" tick={{ fontSize: 10, fill: "#94a3b8" }} axisLine={false} tickLine={false}
              label={{ value: "Problem #", position: "insideBottom", fill: "#94a3b8", fontSize: 9, dy: 6 }} />
            <YAxis tick={{ fontSize: 10, fill: "#94a3b8" }} axisLine={false} tickLine={false} width={42}
              domain={[0, maxGrade + 1]}
              tickFormatter={v => `V${v}`}
              label={{ value: "Grade", angle: -90, position: "insideLeft", fill: "#94a3b8", fontSize: 9, dy: 20 }} />
            <YAxis orientation="right" tick={{ fontSize: 10, fill: "#ef4444" }} axisLine={false} tickLine={false} width={36}
              domain={[Math.max(0, Math.min(...data.map(d => d.hr)) - 10), sessionMaxHR + 5]}
              label={{ value: "bpm", position: "insideRight", fill: "#ef4444", fontSize: 9 }} />
            <Tooltip
              contentStyle={{ background: "#0f172a", border: "none", borderRadius: 8, fontSize: 11 }}
              labelStyle={{ color: "#94a3b8" }}
              itemStyle={{ color: "#f1f5f9" }}
              labelFormatter={i => {
                const d = data[i - 1];
                return d ? `Problem ${i} · ${d.label} · ${d.result === 3 ? "Send" : d.result === 2 ? "Attempt" : ""}` : `Problem ${i}`;
              }}
              formatter={(v, name) => {
                if (name === "grade") return [`V${v}`, "Grade"];
                if (name === "hr")    return [`${v} bpm`, "Peak HR"];
                return [v, name];
              }}
            />
            <Bar dataKey="grade" radius={[3, 3, 0, 0]} name="grade">
              {data.map((d, i) => (
                <Cell key={i} fill={
                  d.result === 2
                    ? "rgba(148,163,184,0.4)"
                    : d.hr ? hrColor(Math.min(1, d.hr / (sessionMaxHR || 200))) : "#10b981"
                } />
              ))}
            </Bar>
            <Line type="monotone" dataKey="hr" stroke="#ef4444" strokeWidth={2}
              dot={{ fill: "#ef4444", r: 3 }} name="hr" />
          </ComposedChart>
        </ResponsiveContainer>
        <div className="flex items-center justify-center gap-4 mt-1 text-[10px] text-slate-500 dark:text-slate-400">
          <span className="flex items-center gap-1">
            <span className="inline-block w-2.5 h-2.5 rounded-sm bg-accent-500/40 border border-accent-500" />Send
          </span>
          <span className="flex items-center gap-1">
            <span className="inline-block w-2.5 h-2.5 rounded-sm bg-slate-300/60 border border-slate-400" />Attempt
          </span>
          <span className="flex items-center gap-1">
            <span className="inline-block w-3 h-0.5 bg-red-400" />Peak HR
          </span>
        </div>
      </ChartCard>
    );
  }

  const data = activeSplits.map((c, i) => ({
    idx: i + 1,
    dur: Math.round(c.duration_seconds ?? 0),
    hr:  c.max_heart_rate ?? 0,
  }));

  return (
    <ChartCard title="Difficulty Proxy per Problem" action={
      <InfoTooltip>
        <p><strong className="text-slate-800 dark:text-slate-100">Duration</strong> — time on each problem. Harder problems typically take longer.</p>
        <p><strong className="text-slate-800 dark:text-slate-100">Peak HR</strong> — max heart rate reached.</p>
        <p className="text-slate-400 dark:text-slate-500">Grade data not found in this recording.</p>
      </InfoTooltip>
    }>
      <ResponsiveContainer width="100%" height={200}>
        <ComposedChart data={data} margin={{ top: 4, right: 40, bottom: 0, left: 0 }}>
          <CartesianGrid {...CHART_GRID} />
          <XAxis dataKey="idx" tick={{ fontSize: 10, fill: "#94a3b8" }} axisLine={false} tickLine={false}
            label={{ value: "Problem #", position: "insideBottom", fill: "#94a3b8", fontSize: 9, dy: 6 }} />
          <YAxis tick={{ fontSize: 10, fill: "#94a3b8" }} axisLine={false} tickLine={false} width={42}
            label={{ value: "sec", angle: -90, position: "insideLeft", fill: "#94a3b8", fontSize: 10, dy: 12 }} />
          <YAxis orientation="right" tick={{ fontSize: 10, fill: "#ef4444" }} axisLine={false} tickLine={false} width={36}
            domain={[Math.max(0, Math.min(...data.map(d => d.hr)) - 10), sessionMaxHR + 5]}
            label={{ value: "bpm", position: "insideRight", fill: "#ef4444", fontSize: 9 }} />
          <Tooltip
            contentStyle={{ background: "#0f172a", border: "none", borderRadius: 8, fontSize: 11 }}
            labelStyle={{ color: "#94a3b8" }}
            itemStyle={{ color: "#f1f5f9" }}
            labelFormatter={i => `Problem ${i}`}
            formatter={(v, name) => {
              if (name === "dur") return [fmtRestDur(v), "Duration"];
              if (name === "hr")  return [`${v} bpm`, "Peak HR"];
              return [v, name];
            }}
          />
          <Bar dataKey="dur" radius={[3, 3, 0, 0]} name="dur">
            {data.map((d, i) => (
              <Cell key={i} fill={d.hr ? hrColor(Math.min(1, d.hr / (sessionMaxHR || 200))) : "#10b981"} />
            ))}
          </Bar>
          <Line type="monotone" dataKey="hr" stroke="#ef4444" strokeWidth={2}
            dot={{ fill: "#ef4444", r: 3 }} name="hr" />
        </ComposedChart>
      </ResponsiveContainer>
    </ChartCard>
  );
}

function fmtRestDur(sec) {
  if (!sec) return "—";
  if (sec >= 60) return `${Math.floor(sec / 60)}m ${Math.round(sec % 60)}s`;
  return `${Math.round(sec)}s`;
}