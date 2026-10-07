// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Chart resolution section: how many points the server returns for activity
// graphs. Lower = faster but may miss peaks; only affects graphs, not summaries.
import { useEffect, useState } from "react";
import { api } from "../../api/client";
import { SELECT, Section, useSaveStatus } from "./primitives";
import InfoTooltip from "../ui/InfoTooltip";

const RESOLUTION_OPTIONS = [
  { value: "low",    label: "Low",    hint: "~500 pts — fastest, best for slow hardware" },
  { value: "medium", label: "Medium", hint: "~1 500 pts — good balance" },
  { value: "high",   label: "High",   hint: "~3 000 pts — recommended for most devices" },
  { value: "raw",    label: "Raw",    hint: "All points — may be slow for 8 hr+ activities" },
];

export default function ChartResolutionSection({ settings, onSaved }) {
  const [resolution, setResolution] = useState(settings?.chart_resolution ?? "high");
  const { status, startSave, markSaved, markError } = useSaveStatus();

  useEffect(() => {
    setResolution(settings?.chart_resolution ?? "high");
  }, [settings]);

  async function save(v) {
    setResolution(v);
    startSave();
    try {
      await api.updateSettings({ chart_resolution: v });
      onSaved();
      markSaved();
    } catch { markError(); }
  }

  return (
    <Section title="Chart Resolution" status={status}>
      <div>
        <label className="field-label flex items-center gap-1.5">
          Activity graphs
          <InfoTooltip label="What chart detail changes">
            How many points an activity's graphs draw. Lower is faster to load but
            smooths over short peaks — a sprint, a surge on a climb. Raw draws every
            sample the watch recorded and can be slow on very long activities. Only
            the graphs change: summary numbers are always computed from every sample.
          </InfoTooltip>
        </label>
        <select className={SELECT} value={resolution} onChange={e => save(e.target.value)}>
          {RESOLUTION_OPTIONS.map(o => (
            <option key={o.value} value={o.value}>{o.label} — {o.hint}</option>
          ))}
        </select>
      </div>
    </Section>
  );
}
