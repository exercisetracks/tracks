// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Sports visibility section: toggle chips for every sport seen in the user's
// history. Hidden sports are excluded from all metrics/charts/training load.
import { useEffect, useState } from "react";
import { api } from "../../api/client";
import { Section, InlineError, useSaveStatus } from "./primitives";

export default function SportsSection({ settings, onSaved }) {
  const [allSports, setAllSports] = useState([]);
  const [hidden,    setHidden]    = useState(new Set(settings?.hidden_sports ?? []));
  const [error,     setError]     = useState("");
  const { status, startSave, markSaved, markError } = useSaveStatus();

  useEffect(() => {
    api.getSports().then(s => setAllSports(s.map(x => (typeof x === "string" ? x : x.sport ?? x)))).catch(() => {});
  }, []);

  useEffect(() => {
    setHidden(new Set(settings?.hidden_sports ?? []));
  }, [settings]);

  async function toggle(sport) {
    const next = new Set(hidden);
    next.has(sport) ? next.delete(sport) : next.add(sport);
    setHidden(next);
    startSave();
    setError("");
    try {
      await api.updateSettings({ hidden_sports: [...next] });
      onSaved();
      markSaved();
    } catch (e) {
      setError(e.message ?? "Save failed.");
      markError();
      setHidden(hidden); // revert
    }
  }

  return (
    <Section title="Sports Visibility" status={status}>
      <p className="text-xs text-slate-400 dark:text-slate-500">
        Hidden sports are excluded from all metrics, charts, and training-load calculations.
      </p>
      {allSports.length === 0 ? (
        <p className="text-sm text-slate-400 dark:text-slate-500 italic">No activities imported yet.</p>
      ) : (
        <div className="flex flex-wrap gap-2">
          {allSports.map(sport => {
            const isHidden = hidden.has(sport);
            return (
              <button key={sport} type="button" onClick={() => toggle(sport)}
                className={`px-2.5 py-1 rounded-full text-xs font-medium border transition-colors capitalize ${
                  isHidden
                    ? "border-red-300 dark:border-red-700 bg-red-50 dark:bg-red-900/30 text-red-600 dark:text-red-400"
                    : "border-accent-300 dark:border-accent-700 bg-accent-50 dark:bg-accent-900/30 text-accent-700 dark:text-accent-400"
                }`}>
                {isHidden ? "✕ " : "✓ "}{sport}
              </button>
            );
          })}
        </div>
      )}
      <InlineError msg={error} />
    </Section>
  );
}
