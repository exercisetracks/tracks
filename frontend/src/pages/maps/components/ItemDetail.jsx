// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The bottom sheet for a track or place selected on the map — the phone's
// SelectionSheet. Everything you can do to the item, the same actions the
// library's open row offers: two places offering different subsets of the same
// actions is how a person learns to distrust both. The line, colour and symbol
// are changed in the editor (Edit), one place for everything about the item —
// its collection too, which is in the editor only, as on the phone; the
// library moves several at once.
//
// A track carries its height profile, coloured by grade; the pointer on it
// marks the same spot on the map.
import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../../../api/client";
import Switch from "../../../components/ui/Switch";
import { formatGpx } from "../utils/gpx";
import {
  downloadText, fileSafe, placeDeviceLabel, placeGpx, pointAlong, trackDeviceLabel,
} from "../utils/mapItems";
import ElevationProfileCard from "./ElevationProfileCard";
import { ItemMark } from "./ItemBits";

function Name({ name, onRename }) {
  const [draft, setDraft] = useState(name);
  useEffect(() => setDraft(name), [name]);
  const commit = () => { const t = draft.trim(); if (t && t !== name) onRename(t); else setDraft(name); };
  return (
    <input
      value={draft} onChange={(e) => setDraft(e.target.value)} onBlur={commit} title="Rename"
      onKeyDown={(e) => { if (e.key === "Enter") e.currentTarget.blur(); if (e.key === "Escape") { setDraft(name); e.currentTarget.blur(); } }}
      className="font-semibold text-slate-800 dark:text-slate-100 bg-transparent border-b border-dashed border-transparent hover:border-slate-300 focus:border-accent-500 outline-none min-w-0 flex-1"
    />
  );
}

function TurnCheck({ track }) {
  const [check, setCheck] = useState(null);   // null | "loading" | {compatible, reason}
  useEffect(() => {
    if (!track.turn_by_turn) { setCheck(null); return undefined; }
    let live = true;
    setCheck("loading");
    api.checkCourseTurns({ track_id: track.id, sport: track.sport })
      .then((r) => { if (live) setCheck(r); })
      .catch(() => { if (live) setCheck(null); });
    return () => { live = false; };
  }, [track.id, track.turn_by_turn, track.sport]);
  if (!check) return null;
  if (check === "loading") return <span className="w-3 h-3 border-[1.5px] border-slate-400 border-t-transparent rounded-full animate-spin" />;
  return <span title={check.reason || ""} className={check.compatible ? "text-emerald-600 dark:text-emerald-400" : "text-amber-500"}>{check.compatible ? "✓" : "!"}</span>;
}

export default function ItemDetail({
  kind, id, places = [], atlas, imperial, hasDevice, onChanged, onClose, onEdit, onScrub,
}) {
  const [track, setTrack] = useState(null);
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState(null);
  const place = kind === "place" ? places.find((p) => p.id === id) : null;

  useEffect(() => {
    setConfirming(false); setError(null);
    if (kind !== "track" || id == null) { setTrack(null); return undefined; }
    let live = true;
    api.getCourse(id).then((d) => { if (live) setTrack(d); }).catch(() => { if (live) setTrack(null); });
    return () => { live = false; };
  }, [kind, id]);
  useEffect(() => () => onScrub?.(null), [id]); // eslint-disable-line react-hooks/exhaustive-deps

  const item = kind === "track" ? track : place;
  if (id == null || !item) return null;

  const patch = async (body) => {
    setError(null);
    try {
      const updated = kind === "track" ? await api.updateCourse(id, body) : await api.updateWaypoint(id, body);
      if (kind === "track") setTrack((t) => ({ ...t, ...updated }));
      await onChanged?.();
    } catch (e) { setError(e.message || "That did not work"); }
  };
  const remove = async () => {
    try {
      if (kind === "track") await api.deleteCourse(id); else await api.deleteWaypoint(id);
      await onChanged?.();
      onClose?.();
    } catch (e) { setError(e.message || "Could not delete"); }
  };
  const exportGpx = () => {
    if (kind === "place") { downloadText(placeGpx(place), fileSafe(place.name)); return; }
    const gj = { type: "FeatureCollection", features: [{ type: "Feature", properties: {}, geometry: { type: "LineString", coordinates: track.geometry || [] } }] };
    downloadText(formatGpx(gj, track.name), fileSafe(track.name));
  };

  const km = (m) => (m == null ? "—" : imperial ? `${(m / 1609.34).toFixed(1)} mi` : `${(m / 1000).toFixed(1)} km`);
  const ht = (m) => (m == null ? "—" : imperial ? `${Math.round(m * 3.28084).toLocaleString()} ft` : `${Math.round(m).toLocaleString()} m`);
  const caption = kind === "track" ? trackDeviceLabel(item) : placeDeviceLabel(item);

  return (
    <div className="absolute bottom-0 left-2 right-2 sm:left-[11rem] sm:right-[5rem] z-30 pointer-events-none">
      <div className="pointer-events-auto w-full max-w-[52rem] mx-auto px-3.5 py-2
                      bg-white/85 dark:bg-slate-900/85 backdrop-blur-md
                      border border-b-0 border-slate-200/70 dark:border-slate-700/70 rounded-t-xl
                      shadow-[0_-4px_20px_rgba(0,0,0,0.10)]">
        <div className="flex items-center gap-2 mb-1.5">
          <ItemMark kind={kind} color={item.color} icon={item.icon} atlas={atlas} />
          <Name name={item.name} onRename={(name) => patch({ name })} />
          <div className="flex items-center gap-3 shrink-0 text-[12px] text-slate-600 dark:text-slate-300">
            {kind === "track" ? <>
              <span title="distance">{km(item.distance_m)}</span>
              <span className="text-emerald-600 dark:text-emerald-400" title="elevation gain">↑{ht(item.ascent_m)}</span>
              <span className="text-rose-500 dark:text-rose-400" title="elevation loss">↓{ht(item.descent_m)}</span>
            </> : <>
              <span className="tabular-nums">{Math.abs(item.lat).toFixed(5)}°{item.lat >= 0 ? "N" : "S"}, {Math.abs(item.lng).toFixed(5)}°{item.lng >= 0 ? "E" : "W"}</span>
              {item.ele_m != null && <span>{ht(item.ele_m)}</span>}
            </>}
            <button type="button" onClick={onClose} className="icon-btn" title="Close">×</button>
          </div>
        </div>

        {kind === "track" && (
          <ElevationProfileCard
            profile={item.profile} imperial={imperial} grade height={130}
            onScrub={(at) => onScrub?.(at == null ? null : pointAlong(item.geometry, at))}
          />
        )}

        {error && <p className="text-xs text-rose-600 dark:text-rose-400 mb-1">{error}</p>}

        <div className="mt-1.5 flex items-center justify-center gap-x-3 gap-y-2 flex-wrap text-[11px]">
          {confirming ? (
            <span className="flex items-center gap-2 text-xs text-slate-600 dark:text-slate-300">
              {item.load_to_device ? "Delete from Tracks and the watch?" : "Delete for good?"}
              <button type="button" className="btn btn-neutral btn-sm" onClick={() => setConfirming(false)}>Keep</button>
              <button type="button" className="btn btn-danger btn-sm" onClick={remove}>Delete</button>
            </span>
          ) : <>
            {hasDevice && (
              <span className="flex items-center gap-1.5">
                {item.load_to_device
                  ? <button type="button" className="btn btn-neutral btn-sm" onClick={() => patch({ load_to_device: false })}>Off watch</button>
                  : <button type="button" className="btn btn-tonal btn-sm" onClick={() => patch({ load_to_device: true })}>To watch</button>}
                <span className="text-slate-400">{caption}</span>
              </span>
            )}
            {kind === "track" && (
              <label className="flex items-center gap-1.5 text-slate-600 dark:text-slate-300" title="Road-style turn prompts on the watch">
                <Switch small checked={item.turn_by_turn} onChange={(v) => patch({ turn_by_turn: v })} label="Turn-by-turn" />
                Turn-by-turn <TurnCheck track={item} />
              </label>
            )}
            <button type="button" className="btn btn-neutral btn-sm" onClick={() => onEdit(item)}>Edit</button>
            {kind === "track" && item.activity_id != null && (
              <Link to={`/activities/${item.activity_id}`} className="btn btn-neutral btn-sm">View activity</Link>
            )}
            <button type="button" className="btn btn-neutral btn-sm" onClick={exportGpx}>GPX</button>
            <button type="button" className="btn btn-danger btn-sm" onClick={() => setConfirming(true)}>Delete</button>
          </>}
        </div>
      </div>
    </div>
  );
}
