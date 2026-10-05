// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Heart-rate section: max HR and threshold HR (LTHR), each Auto (detected from
// history) or Manual. Shows derived HR zones and can re-run the auto detection.
// Keeps a local `liveSettings` copy so recalculate/save can update zone tables
// without waiting for the parent to refetch.
import { useEffect, useState } from "react";
import { api } from "../../api/client";
import { INPUT, Section, ModeToggle, InlineError, ZonesTable, useSaveStatus } from "./primitives";

export default function HRSection({ settings, onSaved }) {
  const [maxMode,   setMaxMode]   = useState(settings?.max_hr_mode       ?? "auto");
  const [maxManual, setMaxManual] = useState(settings?.max_hr_manual     ?? "");
  const [thrMode,   setThrMode]   = useState(settings?.threshold_hr_mode ?? "auto");
  const [thrManual, setThrManual] = useState(settings?.threshold_hr_manual ?? "");
  const [showZones, setShowZones] = useState(false);
  const [recalcing, setRecalcing] = useState(false);
  const [error,     setError]     = useState("");
  const [liveSettings, setLiveSettings] = useState(settings);
  const { status, startSave, markSaved, markError } = useSaveStatus();

  useEffect(() => {
    setMaxMode(settings?.max_hr_mode ?? "auto");
    setMaxManual(settings?.max_hr_manual ?? "");
    setThrMode(settings?.threshold_hr_mode ?? "auto");
    setThrManual(settings?.threshold_hr_manual ?? "");
    setLiveSettings(settings);
  }, [settings]);

  async function saveField(payload) {
    startSave();
    setError("");
    try {
      const s = await api.updateSettings(payload);
      setLiveSettings(s);
      onSaved();
      markSaved();
    } catch (e) {
      setError(e.message ?? "Save failed.");
      markError();
    }
  }

  async function recalculate() {
    setRecalcing(true); setError("");
    try {
      const s = await api.recalculate();
      setLiveSettings(s);
      setMaxManual(s.max_hr_auto ?? "");
      setThrManual(s.threshold_hr_auto ?? "");
      onSaved();
    } catch (e) {
      setError(e.message ?? "Recalculate failed.");
    } finally {
      setRecalcing(false);
    }
  }

  const effectiveThr = thrMode === "manual" ? (parseInt(thrManual) || null) : liveSettings?.threshold_hr_auto;

  return (
    <Section title="Heart Rate" status={status}>
      <div>
        <div className="flex items-center justify-between mb-2">
          <span className="text-sm font-medium text-slate-700 dark:text-slate-300">Max Heart Rate</span>
          <ModeToggle value={maxMode} onChange={v => { setMaxMode(v); saveField({ max_hr_mode: v }); }} />
        </div>
        {maxMode === "manual" ? (
          <input className={INPUT} type="number" min={100} max={250}
            placeholder="e.g. 185 bpm"
            value={maxManual}
            onChange={e => setMaxManual(e.target.value)}
            onBlur={() => { if (maxManual) saveField({ max_hr_manual: parseInt(maxManual) }); }} />
        ) : (
          <p className="text-xs text-slate-400 dark:text-slate-500">
            Auto-detected: <strong className="text-slate-600 dark:text-slate-300">
              {liveSettings?.max_hr_auto ? `${liveSettings.max_hr_auto} bpm` : "not enough data yet"}
            </strong>
          </p>
        )}
      </div>

      <div>
        <div className="flex items-center justify-between mb-2">
          <span className="text-sm font-medium text-slate-700 dark:text-slate-300">Threshold HR (LTHR)</span>
          <ModeToggle value={thrMode} onChange={v => { setThrMode(v); saveField({ threshold_hr_mode: v }); }} />
        </div>
        {thrMode === "manual" ? (
          <input className={INPUT} type="number" min={80} max={250}
            placeholder="e.g. 162 bpm"
            value={thrManual}
            onChange={e => setThrManual(e.target.value)}
            onBlur={() => { if (thrManual) saveField({ threshold_hr_manual: parseInt(thrManual) }); }} />
        ) : (
          <p className="text-xs text-slate-400 dark:text-slate-500">
            Auto-detected: <strong className="text-slate-600 dark:text-slate-300">
              {liveSettings?.threshold_hr_auto ? `${liveSettings.threshold_hr_auto} bpm` : "not enough data yet"}
            </strong>
          </p>
        )}
      </div>

      {effectiveThr && (
        <div>
          <button type="button"
            onClick={() => setShowZones(v => !v)}
            className="btn btn-neutral btn-sm">
            {showZones ? "Hide" : "Show"} HR zones (based on {effectiveThr} bpm LTHR)
          </button>
          {showZones && (
            <div className="mt-2 space-y-2">
              <p className="text-xs font-medium text-slate-500 dark:text-slate-400">Running zones</p>
              <ZonesTable zones={liveSettings?.hr_zones_running} unit="bpm" />
              <p className="text-xs font-medium text-slate-500 dark:text-slate-400 mt-2">Cycling zones</p>
              <ZonesTable zones={liveSettings?.hr_zones_cycling} unit="bpm" />
            </div>
          )}
        </div>
      )}

      <InlineError msg={error} />
      <button type="button" onClick={recalculate} disabled={recalcing}
        className="btn btn-tonal btn-sm">
        {recalcing ? "Recalculating…" : "↺ Recalculate from history"}
      </button>
    </Section>
  );
}
