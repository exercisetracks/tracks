// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Route-builder control panel for the map page. Presentational UI for the
// useRouteBuilder hook: start/finish/cancel a route, toggle trail-snapping, show
// the running distance + elevation profile, pick a colour, and save the result
// as a custom track (with GPX export). All routing state lives in the hook —
// this component drives it through the on* callbacks passed in as props.
import { useState, useEffect } from "react";
import { formatGpx } from "../utils/gpx";
import { api } from "../../../api/client";
import { TRACK_COLORS, randomTrackColor } from "./CustomTrackDetail";
import ColorPicker from "../../../components/ColorPicker";

export default function RouteBuilder({
  building, waypoints, route, snapping, snapToTrails, error, elevation,
  onStart, onFinish, onCancel, onClear, onToggleSnap, onSaveTrack,
}) {
  const distanceKm = elevation?.distanceKm ?? _straightDistance(waypoints);
  const showToggle = building; // only while actively placing a route
  const [saving, setSaving] = useState(false);

  return (
    <div className="p-2.5 space-y-2">
      <div className="text-[10px] font-semibold uppercase tracking-wide text-slate-400 dark:text-slate-500">
        Route Builder
      </div>

      {/* Snap-to-trails toggle (auto-snaps as points are added) */}
      {showToggle && (
        <button
          onClick={onToggleSnap}
          className="w-full flex items-center justify-between px-2 py-1 rounded-lg bg-slate-100 dark:bg-slate-800 hover:bg-slate-200 dark:hover:bg-slate-700 transition-colors"
        >
          <span className="text-[11px] font-medium text-slate-600 dark:text-slate-300">
            Snap to trails?
          </span>
          <span
            className={`relative inline-flex h-4 w-7 items-center rounded-full transition-colors ${
              snapToTrails ? "bg-accent-600" : "bg-slate-300 dark:bg-slate-600"
            }`}
          >
            <span
              className={`inline-block h-3 w-3 transform rounded-full bg-white transition-transform ${
                snapToTrails ? "translate-x-3.5" : "translate-x-0.5"
              }`}
            />
          </span>
        </button>
      )}

      {!route && !building ? (
        <button
          onClick={onStart}
          className="btn btn-primary btn-sm w-full gap-2"
        >
          <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" d="M7 21a4 4 0 01-4-4V5a2 2 0 012-2h4a2 2 0 012 2v12a4 4 0 01-4 4zm0 0h12a2 2 0 002-2v-4a2 2 0 00-2-2h-2.343M11 7.343l1.657-1.657a2 2 0 012.828 0l2.829 2.829a2 2 0 010 2.828l-8.486 8.485M7 17h.01" />
          </svg>
          Create Route
        </button>
      ) : building ? (
        <div className="space-y-2">
          <p className="text-[11px] text-slate-500 dark:text-slate-400 leading-relaxed">
            Click the map to add points · drag a point to adjust
            {snapToTrails ? " · routes snap to trails automatically" : ""}.
          </p>
          <div className="flex items-center gap-2 text-[11px] text-slate-600 dark:text-slate-300">
            <span>{waypoints.length} point{waypoints.length !== 1 ? "s" : ""}</span>
            {distanceKm > 0 && <span>· {distanceKm.toFixed(1)} km</span>}
            {snapping && (
              <span className="flex items-center gap-1 text-accent-600 dark:text-accent-400">
                <span className="w-2.5 h-2.5 border-[1.5px] border-accent-500 border-t-transparent rounded-full animate-spin" />
                routing…
              </span>
            )}
          </div>
          {error && <p className="text-[10px] text-red-500">{error}</p>}
          <div className="flex gap-1.5">
            <button
              onClick={onFinish}
              disabled={waypoints.length < 2}
              className="btn btn-primary btn-sm flex-1"
            >
              Done
            </button>
            <button
              onClick={onCancel}
              className="btn btn-neutral btn-sm"
            >
              Cancel
            </button>
          </div>
        </div>
      ) : route ? (
        saving ? (
          <SaveTrackForm
            route={route} elevation={elevation}
            onCancel={() => setSaving(false)}
            onSave={async (meta) => { await onSaveTrack(meta); setSaving(false); }}
          />
        ) : (
          <div className="space-y-2">
            <div className="text-[11px] text-slate-600 dark:text-slate-300">
              {distanceKm.toFixed(1)} km
              {elevation?.gain != null && ` · ↑${elevation.gain} m`}
              {elevation?.loss != null && ` ↓${elevation.loss} m`}
            </div>
            <button
              onClick={() => setSaving(true)}
              className="btn btn-primary btn-sm w-full"
            >
              Save as Track
            </button>
            <div className="flex gap-1.5">
              <button
                onClick={() => {
                  const gpx = formatGpx(route, "Tracks Route");
                  _downloadFile(gpx, "route.gpx", "application/gpx+xml");
                }}
                className="btn btn-neutral btn-sm flex-1"
              >
                Download GPX
              </button>
              <button
                onClick={onStart}
                className="btn btn-neutral btn-sm"
              >
                New
              </button>
              <button
                onClick={onClear}
                className="btn btn-neutral btn-sm"
              >
                Clear
              </button>
            </div>
          </div>
        )
      ) : null}
    </div>
  );
}

const SPORTS = ["running", "hiking", "cycling", "walking"];

function SaveTrackForm({ route, elevation, onCancel, onSave }) {
  const [name, setName] = useState("");
  const [color, setColor] = useState(randomTrackColor);
  const [sport, setSport] = useState("hiking");
  const [tbt, setTbt] = useState(false);
  const [check, setCheck] = useState(null);   // null | "loading" | {compatible, reason}
  const [busy, setBusy] = useState(false);

  const coords = route?.features?.[0]?.geometry?.coordinates || [];

  // Live turn-by-turn compatibility check when the toggle is on.
  useEffect(() => {
    if (!tbt) { setCheck(null); return; }
    let cancelled = false;
    setCheck("loading");
    api.checkCourseTurns({ coords, sport })
      .then((r) => { if (!cancelled) setCheck(r); })
      .catch(() => { if (!cancelled) setCheck(null); });
    return () => { cancelled = true; };
  }, [tbt, sport]); // eslint-disable-line react-hooks/exhaustive-deps

  const save = async () => {
    setBusy(true);
    try {
      await onSave({ name: name.trim() || "Custom Track", color, sport,
                     turn_by_turn: tbt, geojson: route, source: "builder" });
    } finally { setBusy(false); }
  };

  return (
    <div className="space-y-2">
      <input
        autoFocus value={name} onChange={(e) => setName(e.target.value)}
        placeholder="Track name"
        className="w-full px-1.5 py-1 rounded border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-xs text-slate-700 dark:text-slate-200 outline-none focus:border-accent-400"
      />
      <div className="flex items-center gap-1">
        {TRACK_COLORS.slice(0, 8).map((c) => (
          <button key={c} onClick={() => setColor(c)}
            className={`w-4 h-4 rounded-full ring-1 transition-transform ${color === c ? "ring-2 ring-offset-1 ring-accent-500 scale-110" : "ring-black/10"}`}
            style={{ backgroundColor: c }} />
        ))}
        <ColorPicker value={color} onChange={setColor} size="w-4 h-4" />
      </div>
      <select value={sport} onChange={(e) => setSport(e.target.value)}
        className="w-full px-1.5 py-1 rounded border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-[11px] text-slate-600 dark:text-slate-300 outline-none">
        {SPORTS.map((s) => <option key={s} value={s}>{s}</option>)}
      </select>

      <label className="flex items-center gap-2 text-[11px] text-slate-600 dark:text-slate-300">
        <span onClick={() => setTbt((v) => !v)}
          className={`relative inline-flex h-4 w-7 items-center rounded-full cursor-pointer transition-colors ${tbt ? "bg-accent-600" : "bg-slate-300 dark:bg-slate-600"}`}>
          <span className={`inline-block h-3 w-3 transform rounded-full bg-white transition-transform ${tbt ? "translate-x-3.5" : "translate-x-0.5"}`} />
        </span>
        Turn-by-turn (road)
      </label>
      {tbt && (
        <p className={`text-[10px] ${check === "loading" ? "text-slate-400" : check?.compatible ? "text-emerald-600" : "text-amber-600"}`}>
          {check === "loading" ? "Checking turns…" : (check?.reason || "")}
        </p>
      )}

      <div className="flex gap-1.5">
        <button onClick={save} disabled={busy}
          className="btn btn-primary btn-sm flex-1">
          {busy ? "Saving…" : "Save"}
        </button>
        <button onClick={onCancel}
          className="btn btn-neutral btn-sm">
          Cancel
        </button>
      </div>
    </div>
  );
}

function _straightDistance(waypoints) {
  let total = 0;
  for (let i = 1; i < waypoints.length; i++) total += _haversine(waypoints[i - 1], waypoints[i]);
  return total;
}

function _haversine(a, b) {
  const R = 6371;
  const dLat = ((b[1] - a[1]) * Math.PI) / 180;
  const dLon = ((b[0] - a[0]) * Math.PI) / 180;
  const la1 = (a[1] * Math.PI) / 180;
  const la2 = (b[1] * Math.PI) / 180;
  const sd = Math.sin(dLat / 2) ** 2 + Math.cos(la1) * Math.cos(la2) * Math.sin(dLon / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(sd), Math.sqrt(1 - sd));
}

function _downloadFile(content, filename, mime) {
  const blob = new Blob([content], { type: mime });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}
