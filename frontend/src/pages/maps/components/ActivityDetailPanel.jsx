// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useState, useCallback } from "react";
import { Link } from "react-router-dom";
import { api } from "../../../api/client";
import { coordsToProfile } from "../utils/simplify";
import ElevationProfileCard from "./ElevationProfileCard";

// Bottom detail sheet for a recorded ACTIVITY selected on the map (the activity
// variant of CustomTrackDetail). Lets you rename the activity, see its elevation
// profile, turn it into a custom track, or jump to the full activity page. Shares
// the wide/translucent bottom layout. The amber map highlight is driven by MapView.
export default function ActivityDetailPanel({ activityId, imperial, onClose, onCreateTrack, creating, onRenamed }) {
  const [act, setAct] = useState(null);
  const [profile, setProfile] = useState(null);
  const [loading, setLoading] = useState(false);
  const [editingName, setEditingName] = useState(false);
  const [nameDraft, setNameDraft] = useState("");

  useEffect(() => {
    if (activityId == null) return undefined;
    let cancelled = false;
    setLoading(true); setProfile(null);
    Promise.all([api.getActivity(activityId), api.getTrack(activityId)])
      .then(([meta, track]) => {
        if (cancelled) return;
        setAct(meta);
        const coords = (track || [])
          .filter((p) => p.lat != null && p.lng != null)
          .map((p) => [p.lng, p.lat, p.altitude]);
        setProfile(coords.length >= 2 ? coordsToProfile(coords) : null);
        setLoading(false);
      })
      .catch(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [activityId]);

  const saveName = useCallback(async () => {
    setEditingName(false);
    const name = nameDraft.trim();
    if (!act || !name || name === act.name) return;
    try {
      const updated = await api.patchActivity(act.id, { name });
      setAct(updated);
      onRenamed?.(act.id, name);
    } catch { /* ignore */ }
  }, [act, nameDraft, onRenamed]);

  if (activityId == null) return null;

  const km = (m) => m == null ? "—" : (imperial ? `${(m / 1609.34).toFixed(1)} mi` : `${(m / 1000).toFixed(1)} km`);
  const ft = (m) => m == null ? "—" : (imperial ? `${Math.round(m * 3.28084).toLocaleString()} ft` : `${Math.round(m).toLocaleString()} m`);
  const date = act?.started_at ? new Date(act.started_at).toLocaleDateString() : null;

  return (
    <div className="absolute bottom-0 left-2 right-2 sm:left-[11rem] sm:right-[5rem] z-30 pointer-events-none">
      <div className="pointer-events-auto w-full px-3.5 py-2
                      bg-white/70 dark:bg-slate-900/70 backdrop-blur-md
                      border border-b-0 border-slate-200/70 dark:border-slate-700/70 rounded-t-xl
                      shadow-[0_-4px_20px_rgba(0,0,0,0.10)]">
        {/* Header */}
        <div className="flex items-center justify-between gap-3 mb-1.5">
          <div className="flex items-center gap-2 min-w-0">
            <span className="w-3.5 h-3.5 rounded-full shrink-0 ring-1 ring-black/10 bg-amber-500" />
            {editingName ? (
              <input autoFocus value={nameDraft} onChange={(e) => setNameDraft(e.target.value)}
                onBlur={saveName}
                onKeyDown={(e) => { if (e.key === "Enter") e.currentTarget.blur(); if (e.key === "Escape") setEditingName(false); }}
                className="font-semibold text-slate-800 dark:text-slate-100 bg-transparent border-b border-accent-400 outline-none min-w-0" />
            ) : (
              <button onClick={() => { setNameDraft(act?.name || ""); setEditingName(true); }}
                className="font-semibold text-slate-800 dark:text-slate-100 truncate hover:text-accent-600" title="Rename activity">
                {act?.name || (loading ? "Loading…" : "Activity")}
              </button>
            )}
            <span className="px-1 py-0.5 rounded text-[9px] font-bold bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300 shrink-0">ACTIVITY</span>
            {act?.sport && <span className="text-[11px] text-slate-400 capitalize shrink-0">{act.sport}{date ? ` · ${date}` : ""}</span>}
          </div>
          <div className="flex items-center gap-3 shrink-0 text-[12px] text-slate-600 dark:text-slate-300">
            {act && <>
              <span title="distance">{km(act.distance_meters)}</span>
              <span className="text-emerald-600 dark:text-emerald-400" title="elevation gain">↑{ft(act.total_ascent)}</span>
              <span className="text-rose-500 dark:text-rose-400" title="elevation loss">↓{ft(act.total_descent)}</span>
            </>}
            <button onClick={onClose} className="icon-btn">×</button>
          </div>
        </div>

        <ElevationProfileCard profile={profile} imperial={imperial} />

        {/* Controls */}
        <div className="mt-1.5 flex items-center justify-center gap-x-3 gap-y-2 flex-wrap text-[11px]">
          <button onClick={() => onCreateTrack(activityId)} disabled={creating}
            className="btn btn-primary btn-sm">
            {creating ? "Creating…" : "Create track from this activity"}
          </button>
          <Link to={`/activities/${activityId}`}
            className="px-2.5 py-1 rounded bg-slate-200 dark:bg-slate-700 text-slate-600 dark:text-slate-300 hover:bg-slate-300 font-medium">
            View activity
          </Link>
          <span className="text-[10px] text-slate-400">Ctrl/⌘-click another track to merge.</span>
        </div>
      </div>
    </div>
  );
}
