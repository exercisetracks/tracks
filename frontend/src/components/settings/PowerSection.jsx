// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Power section: Functional Threshold Power (FTP), Auto (detected from cycling
// history) or Manual, with derived power zones and a recalculate-from-history
// action. Mirrors HRSection's local-`liveSettings` pattern.
import { useEffect, useState } from "react";
import { api } from "../../api/client";
import { INPUT, Section, ModeToggle, InlineError, ZonesTable, useSaveStatus } from "./primitives";

export default function PowerSection({ settings, onSaved }) {
  const [ftpMode,   setFtpMode]   = useState(settings?.ftp_mode   ?? "auto");
  const [ftpManual, setFtpManual] = useState(settings?.ftp_manual ?? "");
  const [showZones, setShowZones] = useState(false);
  const [recalcing, setRecalcing] = useState(false);
  const [error,     setError]     = useState("");
  const [liveSettings, setLiveSettings] = useState(settings);
  const { status, startSave, markSaved, markError } = useSaveStatus();

  useEffect(() => {
    setFtpMode(settings?.ftp_mode ?? "auto");
    setFtpManual(settings?.ftp_manual ?? "");
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
      setFtpManual(s.ftp_auto ?? "");
      onSaved();
    } catch (e) {
      setError(e.message ?? "Recalculate failed.");
    } finally {
      setRecalcing(false);
    }
  }

  const effectiveFtp = ftpMode === "manual" ? (parseInt(ftpManual) || null) : liveSettings?.ftp_auto;

  return (
    <Section title="Power (FTP)" status={status}>
      <div>
        <div className="flex items-center justify-between mb-2">
          <span className="text-sm font-medium text-slate-700 dark:text-slate-300">Functional Threshold Power</span>
          <ModeToggle value={ftpMode} onChange={v => { setFtpMode(v); saveField({ ftp_mode: v }); }} />
        </div>
        {ftpMode === "manual" ? (
          <input className={INPUT} type="number" min={1} max={2000}
            placeholder="e.g. 250 watts"
            value={ftpManual}
            onChange={e => setFtpManual(e.target.value)}
            onBlur={() => { if (ftpManual) saveField({ ftp_manual: parseInt(ftpManual) }); }} />
        ) : (
          <p className="text-xs text-slate-400 dark:text-slate-500">
            Auto-detected: <strong className="text-slate-600 dark:text-slate-300">
              {liveSettings?.ftp_auto ? `${liveSettings.ftp_auto} W` : "not enough cycling data yet"}
            </strong>
          </p>
        )}
      </div>

      {effectiveFtp && (
        <div>
          <button type="button"
            onClick={() => setShowZones(v => !v)}
            className="btn btn-neutral btn-sm">
            {showZones ? "Hide" : "Show"} power zones (based on {effectiveFtp} W FTP)
          </button>
          {showZones && <ZonesTable zones={liveSettings?.power_zones} unit="W" />}
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
