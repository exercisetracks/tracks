// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// AGPS configuration fields (source / custom URL / watch path / refresh
// interval), expanded inline under the AGPS row of Privacy & Connectivity.
// The enable toggle lives in that row; these fields only matter while it's on.
import { useEffect, useState } from "react";
import { api } from "../../api/client";
import { SaveStatusText, useSaveStatus } from "./primitives";

const AGPS_SOURCES = [
  { value: "garmin", label: "Garmin EPO server" },
  { value: "custom", label: "Custom URL" },
];

export default function AgpsConfigPanel({ settings, onSaved }) {
  const [source,    setSource]    = useState(settings?.agps_source        ?? "garmin");
  const [customUrl, setCustomUrl] = useState(settings?.agps_custom_url    ?? "");
  const [epoPath,   setEpoPath]   = useState(settings?.agps_epo_path      ?? "GARMIN/REMOTESW/CPE.bin");
  const [maxAge,    setMaxAge]    = useState(settings?.agps_max_age_hours ?? 24);
  const { status, startSave, markSaved, markError } = useSaveStatus();

  useEffect(() => {
    setSource(settings?.agps_source        ?? "garmin");
    setCustomUrl(settings?.agps_custom_url ?? "");
    setEpoPath(settings?.agps_epo_path     ?? "GARMIN/REMOTESW/CPE.bin");
    setMaxAge(settings?.agps_max_age_hours ?? 24);
  }, [settings]);

  async function save(patch) {
    startSave();
    try {
      await api.updateSettings(patch);
      await onSaved();
      markSaved();
    } catch {
      markError();
    }
  }

  function lastSyncedLabel() {
    if (!settings?.agps_last_synced_at) return "Never synced";
    const diff = Date.now() - new Date(settings.agps_last_synced_at).getTime();
    const h = Math.floor(diff / 3600000);
    if (h < 1) return "Less than 1 hour ago";
    if (h === 1) return "1 hour ago";
    if (h < 24) return `${h} hours ago`;
    const d = Math.floor(h / 24);
    return d === 1 ? "1 day ago" : `${d} days ago`;
  }

  const inputCls = "field";

  return (
    <div className="px-2.5 py-2.5 space-y-3 bg-slate-50/70 dark:bg-slate-800/30">
      <div>
        <label className="field-label">
          Data source
        </label>
        <select
          value={source}
          onChange={e => { setSource(e.target.value); save({ agps_source: e.target.value }); }}
          className={inputCls}
        >
          {AGPS_SOURCES.map(s => (
            <option key={s.value} value={s.value}>{s.label}</option>
          ))}
        </select>
        {source === "garmin" && (
          <p className="text-xs text-slate-400 dark:text-slate-500 mt-1">
            Downloads from Garmin's EPO server (Sony chipset, covers GPS/GLONASS/Galileo/QZSS, 7-day window).
          </p>
        )}
      </div>

      {source === "custom" && (
        <div>
          <label className="field-label">
            Custom download URL
          </label>
          <input
            type="url"
            value={customUrl}
            onChange={e => setCustomUrl(e.target.value)}
            onBlur={() => save({ agps_custom_url: customUrl || null })}
            placeholder="https://…"
            className={inputCls}
          />
        </div>
      )}

      <div>
        <label className="field-label">
          Watch file path
        </label>
        <input
          type="text"
          value={epoPath}
          onChange={e => setEpoPath(e.target.value)}
          onBlur={() => save({ agps_epo_path: epoPath })}
          className={inputCls + " font-mono"}
        />
        <p className="text-xs text-slate-400 dark:text-slate-500 mt-1">
          Default works for most modern Garmin watches. Change only if your model uses a different path.
        </p>
      </div>

      <div>
        <label className="field-label">
          Refresh interval (hours)
        </label>
        <input
          type="number"
          min={1}
          max={168}
          value={maxAge}
          onChange={e => setMaxAge(Number(e.target.value))}
          onBlur={() => save({ agps_max_age_hours: maxAge })}
          className={inputCls + " w-32"}
        />
        <p className="text-xs text-slate-400 dark:text-slate-500 mt-1">
          CPE data is valid for ~7 days. 24 h is a good default.
        </p>
      </div>

      <div className="flex items-center justify-between text-xs text-slate-500 dark:text-slate-400">
        <span>Last synced: <span className="font-medium">{lastSyncedLabel()}</span></span>
        <SaveStatusText status={status} />
      </div>
    </div>
  );
}
