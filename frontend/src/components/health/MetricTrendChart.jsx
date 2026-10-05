// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The shared trend chart behind the Health page's metric selector. Three modes,
// chosen by what's selected:
//   • Sleep alone        → stacked stage bars (deep/REM/light) + sleep-score line.
//   • one other metric   → a single value line with its own Y axis.
//   • several metrics     → overlaid lines, each auto-scaled to its own hidden
//     axis so their *shapes* can be compared over time (values live in the
//     tooltip). This is how you eyeball how one metric tracks another.
// `windowed` is the merged/ranged row set built by TrendPanel; each row carries
// every metric's field plus a numeric `dateMs`.

import {
  Line, XAxis, YAxis, CartesianGrid, Tooltip, ReferenceLine,
  ResponsiveContainer, ComposedChart, LineChart, Bar,
} from "recharts";
import { fmt1, sleepChartData, todayTs } from "./helpers";
import { SLEEP_COLORS } from "./constants";

const fmtTick = d => new Date(d).toLocaleDateString(undefined, { month: "short", day: "numeric" });
const fmtFullDate = d => new Date(d).toLocaleDateString(undefined, { month: "long", day: "numeric", year: "numeric" });

// Display value for a metric on a given row (honours imperial transform).
function valueOf(row, metric, imperial) {
  const raw = row[metric.field ?? metric.key];
  if (raw == null) return null;
  return metric.transform && imperial ? metric.transform(raw) : raw;
}
function fmtValue(v, metric) {
  if (v == null) return "—";
  return metric.digits === 0 ? Math.round(v).toLocaleString() : Number(v).toFixed(1);
}
function unitOf(metric, imperial) {
  return typeof metric.unit === "function" ? metric.unit(imperial) : metric.unit;
}

// Theme-aware tooltip matching the dashboard's chart tooltips.
function TrendTooltip({ active, payload, selected, imperial }) {
  if (!active || !payload?.length) return null;
  const dateMs = payload[0]?.payload?.dateMs;
  const rows = selected
    .map(m => ({ m, v: payload.find(p => p.dataKey === m.key)?.value }))
    .filter(r => r.v != null);
  if (!rows.length) return null;
  return (
    <div className="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-lg px-2.5 py-1.5 shadow-lg text-xs pointer-events-none">
      <p className="font-semibold text-slate-700 dark:text-slate-200 mb-1">{fmtFullDate(dateMs)}</p>
      <div className="space-y-0.5">
        {rows.map(({ m, v }) => (
          <p key={m.key} style={{ color: m.color }}>
            {m.label} <strong>{fmtValue(v, m)}</strong>
            {unitOf(m, imperial) && <span className="opacity-70"> {unitOf(m, imperial)}</span>}
          </p>
        ))}
      </div>
    </div>
  );
}

// Theme-aware tooltip for the stacked sleep-stage view.
function SleepTooltip({ active, payload, label }) {
  if (!active || !payload?.length) return null;
  const d = Object.fromEntries(payload.map(p => [p.dataKey, p.value]));
  const total = (d.deep ?? 0) + (d.rem ?? 0) + (d.light ?? 0) + (d.other ?? 0);
  const fmtH = v => (v > 0 ? `${Number(v).toFixed(1)}h` : null);
  return (
    <div className="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-lg px-2.5 py-1.5 shadow-lg text-xs pointer-events-none">
      <p className="font-semibold text-slate-700 dark:text-slate-200 mb-1">
        {new Date(label).toLocaleDateString(undefined, { month: "short", day: "numeric" })}
      </p>
      <p className="text-slate-500 dark:text-slate-400">Total <strong className="text-slate-800 dark:text-slate-100">{fmt1(total)}h</strong></p>
      {d.deep  > 0 && <p style={{ color: SLEEP_COLORS.deep  }}>Deep  <strong>{fmtH(d.deep)}</strong></p>}
      {d.rem   > 0 && <p style={{ color: SLEEP_COLORS.rem   }}>REM   <strong>{fmtH(d.rem)}</strong></p>}
      {d.light > 0 && <p style={{ color: SLEEP_COLORS.light }}>Light <strong>{fmtH(d.light)}</strong></p>}
      {d.score != null && <p className="text-slate-400 pt-0.5">Score {Math.round(d.score)}</p>}
    </div>
  );
}

export default function MetricTrendChart({ selected, windowed, imperial }) {
  const sleepOnly = selected.length === 1 && selected[0].sleep;
  if (sleepOnly) return <SleepTrend windowed={windowed} />;
  return <LineTrend selected={selected} windowed={windowed} imperial={imperial} />;
}

// ── Sleep: stacked stages + score line ───────────────────────────────────────
function SleepTrend({ windowed }) {
  const data = sleepChartData(windowed, windowed.length);
  const nights = data.filter(d => d.total > 0);
  const avg = nights.length ? nights.reduce((s, d) => s + d.total, 0) / nights.length : null;

  if (data.length < 2) return <NotEnough />;

  return (
    <>
      {avg != null && <AvgCaption>{`Avg ${fmt1(avg)}h per night`}</AvgCaption>}
      <ResponsiveContainer width="100%" height={260}>
        <ComposedChart data={data} margin={{ top: 4, right: 40, left: 0, bottom: 0 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" strokeOpacity={0.5} vertical={false} />
          <XAxis dataKey="date" type="number" scale="time" domain={["dataMin", todayTs()]}
            tickFormatter={fmtTick} tick={{ fontSize: 10, fill: "#94a3b8" }} axisLine={false} tickLine={false} interval="preserveStartEnd" />
          <YAxis yAxisId="h" domain={[0, d => Math.max(d, 8) + 0.5]} tick={{ fontSize: 10, fill: "#94a3b8" }}
            axisLine={false} tickLine={false} tickFormatter={v => `${v}h`} width={30} />
          <YAxis yAxisId="score" orientation="right" domain={[0, 100]} tick={{ fontSize: 10, fill: "#94a3b8" }}
            axisLine={false} tickLine={false} width={28} />
          <ReferenceLine yAxisId="h" y={8} stroke="#94a3b880" strokeDasharray="3 3" />
          <Tooltip content={<SleepTooltip />} />
          <Bar yAxisId="h" dataKey="deep"  stackId="s" fill={SLEEP_COLORS.deep}  radius={0} isAnimationActive={false} />
          <Bar yAxisId="h" dataKey="rem"   stackId="s" fill={SLEEP_COLORS.rem}   radius={0} isAnimationActive={false} />
          <Bar yAxisId="h" dataKey="light" stackId="s" fill={SLEEP_COLORS.light} radius={[3, 3, 0, 0]} isAnimationActive={false} />
          <Bar yAxisId="h" dataKey="other" stackId="s" fill={SLEEP_COLORS.other} radius={[3, 3, 0, 0]} isAnimationActive={false} />
          <Line yAxisId="score" dataKey="score" stroke="#f59e0b" strokeWidth={1.5} dot={false} activeDot={{ r: 3 }} isAnimationActive={false} connectNulls />
        </ComposedChart>
      </ResponsiveContainer>
      <div className="flex gap-4 mt-2 justify-center flex-wrap">
        {[["Deep", SLEEP_COLORS.deep], ["REM", SLEEP_COLORS.rem], ["Light", SLEEP_COLORS.light]].map(([label, color]) => (
          <LegendChip key={label} color={color}>{label}</LegendChip>
        ))}
        <div className="flex items-center gap-1 text-xs text-slate-500 dark:text-slate-400">
          <span className="inline-block w-4 h-0.5 rounded shrink-0" style={{ background: "#f59e0b" }} />
          Sleep score
        </div>
        <span className="text-xs text-slate-300 dark:text-slate-600">— 8h goal</span>
      </div>
    </>
  );
}

// ── One or more value lines ──────────────────────────────────────────────────
function LineTrend({ selected, windowed, imperial }) {
  const single = selected.length === 1;
  const rows = windowed.map(r => {
    const o = { dateMs: r.dateMs };
    for (const m of selected) o[m.key] = valueOf(r, m, imperial);
    return o;
  });
  // Need at least two points for at least one selected metric.
  const enough = selected.some(m => rows.filter(r => r[m.key] != null).length >= 2);
  if (!enough) return <NotEnough />;

  const soloAvgLine = () => {
    const m = selected[0];
    const vals = rows.map(r => r[m.key]).filter(v => v != null);
    if (!vals.length) return null;
    const avg = vals.reduce((s, v) => s + v, 0) / vals.length;
    return <AvgCaption>{`Avg ${fmtValue(avg, m)} ${unitOf(m, imperial)}`}</AvgCaption>;
  };

  return (
    <>
      {single && soloAvgLine()}
      <ResponsiveContainer width="100%" height={260}>
        <LineChart data={rows} margin={{ top: 4, right: 16, left: 0, bottom: 0 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" strokeOpacity={0.5} />
          <XAxis dataKey="dateMs" type="number" scale="time" domain={["dataMin", "dataMax"]}
            tickFormatter={fmtTick} tick={{ fontSize: 11, fill: "#94a3b8" }} axisLine={false} tickLine={false} interval="preserveStartEnd" />
          {single ? (
            <YAxis tick={{ fontSize: 11, fill: "#94a3b8" }} axisLine={false} tickLine={false} width={40} domain={["auto", "auto"]} />
          ) : (
            selected.map(m => <YAxis key={m.key} yAxisId={m.key} hide domain={["auto", "auto"]} />)
          )}
          <Tooltip content={<TrendTooltip selected={selected} imperial={imperial} />} />
          {selected.map(m => (
            <Line key={m.key} type="monotone" dataKey={m.key} yAxisId={single ? undefined : m.key}
              stroke={m.color} strokeWidth={2} dot={false} activeDot={{ r: 4, fill: m.color }}
              connectNulls isAnimationActive={false} />
          ))}
        </LineChart>
      </ResponsiveContainer>
      {!single && (
        <div className="flex gap-4 mt-2 justify-center flex-wrap">
          {selected.map(m => <LegendChip key={m.key} color={m.color}>{m.label}</LegendChip>)}
        </div>
      )}
    </>
  );
}

function NotEnough() {
  return <p className="text-sm text-slate-400 dark:text-slate-500 text-center py-16">Not enough data for this range</p>;
}
function AvgCaption({ children }) {
  return <p className="text-xs text-slate-400 dark:text-slate-500 mb-3">{children}</p>;
}
function LegendChip({ color, children }) {
  return (
    <div className="flex items-center gap-1 text-xs text-slate-500 dark:text-slate-400">
      <span className="w-2 h-2 rounded-sm shrink-0" style={{ background: color }} />
      {children}
    </div>
  );
}
