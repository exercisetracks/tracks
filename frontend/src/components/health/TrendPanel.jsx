// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Unified "Trends" block: a multi-select metric picker + a range toggle driving
// one shared chart. Selecting several metrics overlays their lines so you can
// see how they move together over time. Sleep on its own renders the rich
// stacked-stage view; combined with others it plots as nightly hours. Form
// (TSB) is merged in from the training-load series so its effect on recovery is
// visible right next to the health metrics.

import { useMemo, useState } from "react";
import { Section, Card } from "./ui";
import { dateToTs, todayTs } from "./helpers";
import MetricTrendChart from "./MetricTrendChart";
import Tabs from "../ui/Tabs";

// Metric catalogue. `sleep` flags the stacked-stage view; `field` overrides the
// row key when it differs from `key`; `transform`/`unit` (may be a fn of
// imperial) handle unit conversion; `digits` sets tooltip precision.
const METRICS = [
  { key: "sleep_hours", label: "Sleep",      color: "#6366f1", sleep: true, unit: "h",   digits: 1 },
  { key: "hrv",         label: "HRV",        color: "#10b981", unit: "ms",   digits: 1 },
  { key: "resting_hr",  label: "Resting HR", color: "#ef4444", unit: "bpm",  digits: 0 },
  { key: "spo2",        label: "SpO₂",       color: "#0ea5e9", unit: "%",    digits: 0 },
  { key: "weight",      label: "Weight",     color: "#8b5cf6", field: "weight_kg", digits: 1,
    unit: imp => (imp ? "lbs" : "kg"), transform: v => v * 2.20462 },
  { key: "hydration_ml", label: "Hydration", color: "#06b6d4", unit: "ml",   digits: 0 },
  { key: "calories_in",  label: "Calories",  color: "#f59e0b", unit: "kcal", digits: 0 },
  { key: "tsb",          label: "Form",      color: "#ec4899", unit: "",     digits: 1 },
];

const RANGES = [
  { key: "7d",    label: "7d",       days: 7 },
  { key: "month", label: "Month",    days: 30 },
  { key: "year",  label: "Year",     days: 365 },
  { key: "life",  label: "Lifetime", days: null },
];

export default function TrendPanel({ metrics, form, imperial }) {
  const [selectedKeys, setSelectedKeys] = useState(["sleep_hours"]);
  const [rangeKey, setRangeKey] = useState("month");

  // Merge daily health rows with the Form (TSB) series by date, keyed for charting.
  const merged = useMemo(() => {
    const byDate = new Map();
    for (const m of metrics) byDate.set(m.date, { ...m, dateMs: dateToTs(m.date) });
    for (const f of form || []) {
      const e = byDate.get(f.date) || { date: f.date, dateMs: dateToTs(f.date) };
      e.tsb = f.tsb;
      byDate.set(f.date, e);
    }
    return [...byDate.values()].sort((a, b) => a.dateMs - b.dateMs);
  }, [metrics, form]);

  // Only offer metrics that actually have data.
  const available = METRICS.filter(m => merged.some(r => r[m.field ?? m.key] != null));

  const range = RANGES.find(r => r.key === rangeKey) ?? RANGES[1];
  const cutoff = range.days == null ? -Infinity : todayTs() - range.days * 86_400_000;
  const windowed = merged.filter(r => r.dateMs >= cutoff);

  // Resolve selection against availability; always keep at least one metric.
  let selected = selectedKeys.map(k => available.find(m => m.key === k)).filter(Boolean);
  if (!selected.length && available.length) selected = [available[0]];

  function toggle(key) {
    setSelectedKeys(prev => {
      if (prev.includes(key)) {
        const next = prev.filter(k => k !== key);
        return next.length ? next : prev; // never empty
      }
      return [...prev, key];
    });
  }

  if (!available.length) {
    return (
      <Section title="Trends">
        <Card>
          <p className="text-sm text-slate-400 dark:text-slate-500 text-center py-11">
            No health data yet — sync your device or log an entry to see trends.
          </p>
        </Card>
      </Section>
    );
  }

  const selectedKeySet = new Set(selected.map(m => m.key));

  return (
    <Section title="Trends">
      <Card>
        {/* Controls: multi-select metric chips + range toggle */}
        <div className="flex items-start justify-between gap-3 flex-wrap mb-4">
          <Tabs
            multi
            tabs={available.map(m => ({ key: m.key, label: m.label, dot: m.color }))}
            value={selected.map(m => m.key)}
            onChange={toggle}
            size="sm"
          />
          <Tabs
            tabs={RANGES}
            value={rangeKey}
            onChange={setRangeKey}
            size="sm"
            className="shrink-0"
          />
        </div>

        <MetricTrendChart selected={selected} windowed={windowed} imperial={imperial} />
      </Card>
    </Section>
  );
}
