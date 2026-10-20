// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Health goals: daily steps, weekly intensity minutes, daily floors — each
// Auto (your own last four weeks, inside a sensible band; see
// components/health/goals.js) or Manual. The phone has the same three.
import { useEffect, useMemo, useState } from "react";
import { api } from "../../api/client";
import { INPUT, Section, ModeToggle, InlineError, useSaveStatus } from "./primitives";
import { autoGoals, goalWindow } from "../health/goals";
import { todayIso } from "../../lib/today";

const GOALS = [
  { key: "step_goal", label: "Steps", unit: "a day", auto: "steps", min: 500, max: 100000 },
  { key: "intensity_goal", label: "Intensity minutes", unit: "a week", auto: "intensityWeekly", min: 10, max: 2000 },
  { key: "floors_goal", label: "Floors climbed", unit: "a day", auto: "floors", min: 1, max: 500 },
];

export default function GoalsSection({ settings, onSaved }) {
  const [values, setValues] = useState({});
  const [days, setDays] = useState([]);
  const [error, setError] = useState("");
  const { status, startSave, markSaved, markError } = useSaveStatus();

  useEffect(() => {
    const v = {};
    for (const g of GOALS) {
      v[`${g.key}_mode`] = settings?.[`${g.key}_mode`] ?? "auto";
      v[`${g.key}_manual`] = settings?.[`${g.key}_manual`] ?? "";
    }
    setValues(v);
  }, [settings]);

  useEffect(() => { api.getDailyMetrics().then((d) => setDays(d ?? [])).catch(() => {}); }, []);
  const auto = useMemo(() => autoGoals(goalWindow(days, todayIso())), [days]);

  async function save(payload) {
    startSave(); setError("");
    try { await api.updateSettings(payload); onSaved(); markSaved(); }
    catch (e) { setError(e.message ?? "Save failed."); markError(); }
  }

  return (
    <Section title="Health goals" status={status}>
      {GOALS.map((g) => {
        const mode = values[`${g.key}_mode`] ?? "auto";
        const manual = values[`${g.key}_manual`] ?? "";
        return (
          <div key={g.key}>
            <div className="flex items-center justify-between mb-2">
              <span className="text-sm font-medium text-slate-700 dark:text-slate-300">{g.label}</span>
              <ModeToggle value={mode} onChange={(v) => {
                setValues((s) => ({ ...s, [`${g.key}_mode`]: v }));
                save({ [`${g.key}_mode`]: v });
              }} />
            </div>
            {mode === "manual" ? (
              <input className={INPUT} type="number" min={g.min} max={g.max}
                placeholder={`e.g. ${auto[g.auto].toLocaleString()} ${g.unit}`}
                value={manual}
                onChange={(e) => setValues((s) => ({ ...s, [`${g.key}_manual`]: e.target.value }))}
                onBlur={() => { if (manual) save({ [`${g.key}_manual`]: parseInt(manual) }); }} />
            ) : (
              <p className="text-xs text-slate-400 dark:text-slate-500">
                Auto: <strong className="text-slate-600 dark:text-slate-300">
                  {auto[g.auto].toLocaleString()} {g.unit}
                </strong>
              </p>
            )}
          </div>
        );
      })}
      <InlineError msg={error} />
    </Section>
  );
}
