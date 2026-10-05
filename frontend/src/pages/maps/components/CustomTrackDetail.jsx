// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useState, useCallback, useRef } from "react";
import { Link } from "react-router-dom";
import { api } from "../../../api/client";
import { formatGpx } from "../utils/gpx";
import {
  computeImportance, areaForPercent, simplifyByImportance, coordsToProfile, trackLengthKm,
} from "../utils/simplify";
import ElevationProfileCard from "./ElevationProfileCard";
import ConfirmDialog from "../../../components/ConfirmDialog";
import ColorPicker from "../../../components/ColorPicker";
import EyeToggle from "./EyeToggle";

// Bottom detail sheet for a custom track, opened by selecting the track on the map
// (useCourseSelection). Carries the elevation profile, load-to-device + turn-by-turn
// toggles, recolour, rename, a live Visvalingam simplify slider, GPX export, a link
// back to the source activity (for activity-built tracks), and delete. The panel
// spans the bottom between the left control bar and the right zoom controls.

export const TRACK_COLORS = [
  "#2563eb", "#e11d48", "#16a34a", "#d97706", "#7c3aed",
  "#0891b2", "#db2777", "#ca8a04", "#475569", "#ea580c",
];

// Random preset colour for a freshly-created track.
export const randomTrackColor = () => TRACK_COLORS[Math.floor(Math.random() * TRACK_COLORS.length)];

// Cap a profile to ~800 points so redrawing it live (while dragging the simplify
// slider) stays cheap on long tracks.
function capProfile(profile) {
  const pts = profile?.points || [];
  if (pts.length <= 800) return profile;
  const stride = Math.ceil(pts.length / 800);
  const out = pts.filter((_, i) => i % stride === 0);
  if (out[out.length - 1] !== pts[pts.length - 1]) out.push(pts[pts.length - 1]);
  return { ...profile, points: out };
}

function Toggle({ on, onClick, disabled }) {
  return (
    <button onClick={onClick} disabled={disabled}
      className={`relative inline-flex h-4 w-7 items-center rounded-full transition-colors disabled:opacity-50 ${
        on ? "bg-accent-600" : "bg-slate-300 dark:bg-slate-600"}`}>
      <span className={`inline-block h-3 w-3 transform rounded-full bg-white transition-transform ${
        on ? "translate-x-3.5" : "translate-x-0.5"}`} />
    </button>
  );
}

export default function CustomTrackDetail({ trackId, folders = [], imperial, onClose, onChanged, onPreviewGeometry }) {
  const [t, setT] = useState(null);
  const [loading, setLoading] = useState(false);
  const [editingName, setEditingName] = useState(false);
  const [nameDraft, setNameDraft] = useState("");
  const [turnCheck, setTurnCheck] = useState(null);   // {compatible, turn_count, reason} | "loading"
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [newFolder, setNewFolder] = useState(null);   // "" while typing | null

  // Simplify state: importances precomputed once on open; the slider then just
  // thresholds them, so dragging stays smooth on long tracks.
  const [simplifyOpen, setSimplifyOpen] = useState(false);
  const [pct, setPct] = useState(0);                  // 0 = original, 100 = max simplification
  const [imp, setImp] = useState(null);               // {weights, sorted}
  const [live, setLive] = useState(null);             // {coords, profile}
  const [applying, setApplying] = useState(false);
  const rafRef = useRef(0);

  useEffect(() => {
    if (trackId == null) return;
    let cancelled = false;
    setLoading(true); setTurnCheck(null); setSimplifyOpen(false); setImp(null); setLive(null);
    api.getCourse(trackId)
      .then((d) => { if (!cancelled) { setT(d); setLoading(false); } })
      .catch(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [trackId]);

  const patch = useCallback(async (body) => {
    if (!t) return;
    const updated = await api.updateCourse(t.id, body);
    setT(updated);
    onChanged?.();
  }, [t, onChanged]);

  const createFolderAndAssign = useCallback(async (name) => {
    const f = await api.createCourseFolder({ name: (name || "Folder").trim() || "Folder" });
    setNewFolder(null);
    await patch({ folder_id: f.id });   // assign + refresh folders via onChanged
  }, [patch]);

  const openSimplify = () => {
    if (!t?.geometry || t.geometry.length < 3) return;
    setImp(computeImportance(t.geometry));
    setPct(0); setLive(null); setSimplifyOpen(true);
  };
  const cancelSimplify = () => { setSimplifyOpen(false); setImp(null); setLive(null); onPreviewGeometry?.(null); };

  // Throttle the heavy work (filter + profile + map feed) to one pass per animation
  // frame so rapid slider drags don't queue up and lag.
  useEffect(() => {
    if (!simplifyOpen || !imp || !t?.geometry) return undefined;
    cancelAnimationFrame(rafRef.current);
    rafRef.current = requestAnimationFrame(() => {
      const coords = pct <= 0
        ? t.geometry
        : simplifyByImportance(t.geometry, imp.weights, areaForPercent(imp.sorted, pct));
      setLive({ coords, profile: coordsToProfile(coords) });
      onPreviewGeometry?.(pct <= 0 ? null : coords);
    });
    return () => cancelAnimationFrame(rafRef.current);
  }, [pct, simplifyOpen, imp, t?.geometry, onPreviewGeometry]);

  // Clear the map preview when the panel closes or the sheet unmounts.
  useEffect(() => () => onPreviewGeometry?.(null), [trackId]); // eslint-disable-line react-hooks/exhaustive-deps

  const reducedCount = live?.coords?.length ?? t?.geometry?.length ?? 0;
  const canApply = simplifyOpen && pct > 0 && live && reducedCount >= 2 && reducedCount < (t?.geometry?.length || 0);
  const applySimplify = async () => {
    if (!canApply) return;
    setApplying(true);
    try { await patch({ coords: live.coords }); cancelSimplify(); }
    finally { setApplying(false); }
  };

  // Live turn-by-turn check when the track is (or becomes) turn-by-turn.
  useEffect(() => {
    if (!t || !t.turn_by_turn) { setTurnCheck(null); return undefined; }
    let cancelled = false;
    setTurnCheck("loading");
    api.checkCourseTurns({ track_id: t.id, sport: t.sport })
      .then((r) => { if (!cancelled) setTurnCheck(r); })
      .catch(() => { if (!cancelled) setTurnCheck(null); });
    return () => { cancelled = true; };
  }, [t?.id, t?.turn_by_turn]);

  if (trackId == null) return null;

  const km = (m) => m == null ? "—" : (imperial ? `${(m / 1609.34).toFixed(1)} mi` : `${(m / 1000).toFixed(1)} km`);
  const ft = (m) => m == null ? "—" : (imperial ? `${Math.round(m * 3.28084).toLocaleString()} ft` : `${Math.round(m).toLocaleString()} m`);

  const downloadGpx = () => {
    const geojson = { type: "FeatureCollection", features: [{ type: "Feature", properties: {},
      geometry: { type: "LineString", coordinates: t.geometry || [] } }] };
    const blob = new Blob([formatGpx(geojson, t.name)], { type: "application/gpx+xml" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url; a.download = `${t.name.replace(/[^a-z0-9]+/gi, "_")}.gpx`; a.click();
    URL.revokeObjectURL(url);
  };

  const remove = async () => {
    await api.deleteCourse(t.id);
    setConfirmDelete(false);
    onChanged?.();
    onClose?.();
  };

  const deviceLabel = {
    on_device: "On watch", pending_upload: "Will load next sync",
    pending_remove: "Will remove next sync", external: "On watch (external)", off: "Not on watch",
  }[t?.device_status] || "";

  const profileShown = simplifyOpen && pct > 0 && live ? capProfile(live.profile) : t?.profile;

  return (
    <div className="absolute bottom-0 left-2 right-2 sm:left-[11rem] sm:right-[5rem] z-30 pointer-events-none">
      <div className="pointer-events-auto w-full px-3.5 py-2
                      bg-white/70 dark:bg-slate-900/70 backdrop-blur-md
                      border border-b-0 border-slate-200/70 dark:border-slate-700/70 rounded-t-xl
                      shadow-[0_-4px_20px_rgba(0,0,0,0.10)]">
        {/* Header */}
        <div className="flex items-center justify-between gap-3 mb-1.5">
          <div className="flex items-center gap-2 min-w-0">
            <span className="w-3.5 h-3.5 rounded-full shrink-0 ring-1 ring-black/10"
                  style={{ backgroundColor: t?.color || "#666" }} />
            {editingName ? (
              <input autoFocus value={nameDraft} onChange={(e) => setNameDraft(e.target.value)}
                onBlur={() => { setEditingName(false); if (nameDraft.trim() && nameDraft !== t.name) patch({ name: nameDraft.trim() }); }}
                onKeyDown={(e) => { if (e.key === "Enter") e.currentTarget.blur(); if (e.key === "Escape") setEditingName(false); }}
                className="font-semibold text-slate-800 dark:text-slate-100 bg-transparent border-b border-accent-400 outline-none min-w-0" />
            ) : (
              <button onClick={() => { setNameDraft(t?.name || ""); setEditingName(true); }}
                className="font-semibold text-slate-800 dark:text-slate-100 truncate hover:text-accent-600" title="Rename">
                {t?.name || (loading ? "Loading…" : "Track")}
              </button>
            )}
            {t?.is_external && (
              <span className="px-1 py-0.5 rounded text-[9px] font-bold bg-slate-200 dark:bg-slate-700 text-slate-500 dark:text-slate-300 shrink-0">EXTERNAL</span>
            )}
            {t && !t.is_external && (
              <EyeToggle hidden={t.hidden} size="w-4 h-4" onClick={() => patch({ hidden: !t.hidden })} />
            )}
          </div>
          <div className="flex items-center gap-3 shrink-0 text-[12px] text-slate-600 dark:text-slate-300">
            {t && <>
              <span title="distance">{km(t.distance_m)}</span>
              <span className="text-emerald-600 dark:text-emerald-400" title="elevation gain">↑{ft(t.ascent_m)}</span>
              <span className="text-rose-500 dark:text-rose-400" title="elevation loss">↓{ft(t.descent_m)}</span>
            </>}
            <button onClick={onClose} className="text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 text-lg leading-none px-1">×</button>
          </div>
        </div>

        <ElevationProfileCard profile={profileShown} imperial={imperial} />

        {/* Simplify panel — live Visvalingam preview (map + profile) + Apply/Cancel */}
        {simplifyOpen && t && (
          <div className="mt-1.5 p-1.5 rounded-lg bg-slate-50/80 dark:bg-slate-800/60 border border-slate-200 dark:border-slate-700">
            <div className="flex items-center justify-between text-[11px] mb-1.5">
              <span className="font-semibold text-slate-700 dark:text-slate-200">Simplify track</span>
              <span className="text-slate-500 dark:text-slate-400">
                {(t.geometry?.length || 0).toLocaleString()} → <span className="font-semibold text-accent-600 dark:text-accent-400">{reducedCount.toLocaleString()}</span> pts
                {live?.coords && <span> · {km(trackLengthKm(live.coords) * 1000)}</span>}
              </span>
            </div>
            <div className="flex items-center gap-2">
              <span className="text-[10px] text-slate-400 w-14">{pct === 0 ? "original" : `${pct}%`}</span>
              <input type="range" min="0" max="100" step="1" value={pct}
                onChange={(e) => setPct(Number(e.target.value))}
                className="flex-1 accent-accent-600" />
            </div>
            <div className="flex gap-1.5 mt-2">
              <button onClick={applySimplify} disabled={applying || !canApply}
                className="btn btn-primary btn-sm flex-1">
                {applying ? "Applying…" : "Apply"}
              </button>
              <button onClick={cancelSimplify}
                className="btn btn-neutral btn-sm">
                Cancel
              </button>
            </div>
          </div>
        )}

        {/* Controls (single row at normal widths) */}
        {t && (
          <div className="mt-1.5 flex items-center justify-center gap-x-3 gap-y-2 flex-wrap text-[11px]">
            {/* Colour swatches + custom picker */}
            <div className="flex items-center gap-1">
              {TRACK_COLORS.map((c) => (
                <button key={c} onClick={() => patch({ color: c })}
                  className={`w-4 h-4 rounded-full ring-1 transition-transform ${t.color === c ? "ring-2 ring-offset-1 ring-accent-500 scale-110" : "ring-black/10"}`}
                  style={{ backgroundColor: c }} title={c} />
              ))}
              <ColorPicker value={t.color} onChange={(hex) => patch({ color: hex })} size="w-4 h-4" />
            </div>

            {/* Folder: assign to a folder or create a new one inline */}
            {!t.is_external && (
              newFolder == null ? (
                <div className="flex items-center gap-1 text-slate-600 dark:text-slate-300" title="Organize into a folder">
                  <svg className="w-3.5 h-3.5 text-slate-400" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" d="M2.25 12.75V12A2.25 2.25 0 0 1 4.5 9.75h15A2.25 2.25 0 0 1 21.75 12v.75m-8.69-6.44-2.12-2.12a1.5 1.5 0 0 0-1.061-.44H4.5A2.25 2.25 0 0 0 2.25 6v12a2.25 2.25 0 0 0 2.25 2.25h15A2.25 2.25 0 0 0 21.75 18V9a2.25 2.25 0 0 0-2.25-2.25h-5.379a1.5 1.5 0 0 1-1.06-.44Z" />
                  </svg>
                  <select value={t.folder_id ?? ""}
                    onChange={(e) => patch(e.target.value ? { folder_id: Number(e.target.value) } : { clear_folder: true })}
                    className="max-w-[110px] rounded border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-[11px] text-slate-600 dark:text-slate-300 px-1 py-0.5 outline-none">
                    <option value="">No folder</option>
                    {folders.map((f) => <option key={f.id} value={f.id}>{f.name}</option>)}
                  </select>
                  <button onClick={() => setNewFolder("")} title="New folder"
                    className="px-1 py-0.5 rounded bg-slate-100 dark:bg-slate-800 text-slate-500 dark:text-slate-300 hover:bg-slate-200 font-semibold">+</button>
                </div>
              ) : (
                <div className="flex items-center gap-1">
                  <input autoFocus value={newFolder} onChange={(e) => setNewFolder(e.target.value)}
                    onKeyDown={(e) => { if (e.key === "Enter") createFolderAndAssign(newFolder); if (e.key === "Escape") setNewFolder(null); }}
                    placeholder="Folder name"
                    className="w-28 px-1 py-0.5 rounded border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-[11px] text-slate-700 dark:text-slate-200 outline-none focus:border-accent-400" />
                  <button onClick={() => createFolderAndAssign(newFolder)}
                    className="btn btn-primary btn-sm">Add</button>
                  <button onClick={() => setNewFolder(null)} className="text-slate-400 hover:text-slate-600 px-0.5">×</button>
                </div>
              )
            )}

            {t.is_external ? (
              <>
                <button onClick={async () => { await api.saveExternalCourse(t.id); onChanged?.(); }}
                  className="btn btn-primary btn-sm">Save to my tracks</button>
                <button onClick={remove}
                  className="btn btn-neutral btn-sm">
                  {t.device_status === "pending_remove" ? "Removing…" : "Remove from watch"}</button>
              </>
            ) : (
              <>
                <label className="flex items-center gap-1.5 text-slate-600 dark:text-slate-300" title="Upload this track to your Garmin watch on the next sync">
                  <Toggle on={t.load_to_device} onClick={() => patch({ load_to_device: !t.load_to_device })} />
                  Sync to watch
                  {deviceLabel && <span className="text-slate-400">· {deviceLabel}</span>}
                </label>

                <label className="flex items-center gap-1.5 text-slate-600 dark:text-slate-300">
                  <Toggle on={t.turn_by_turn} onClick={() => patch({ turn_by_turn: !t.turn_by_turn })} />
                  Turn-by-turn
                  {t.turn_by_turn && (
                    <span title={turnCheck === "loading" ? "Checking…" : (turnCheck?.reason || "")} className="inline-flex">
                      {turnCheck === "loading" ? (
                        <span className="w-3 h-3 border-[1.5px] border-slate-400 border-t-transparent rounded-full animate-spin" />
                      ) : turnCheck?.compatible ? (
                        <svg className="w-4 h-4 text-emerald-600 dark:text-emerald-400" fill="none" stroke="currentColor" strokeWidth={2.5} viewBox="0 0 24 24">
                          <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                        </svg>
                      ) : turnCheck ? (
                        <svg className="w-4 h-4 text-amber-500" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
                          <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L4.082 16.5c-.77.833.192 2.5 1.732 2.5z" />
                        </svg>
                      ) : null}
                    </span>
                  )}
                </label>

                <button onClick={openSimplify} disabled={simplifyOpen}
                  className="btn btn-neutral btn-sm">Simplify</button>
                {t.activity_id != null && (
                  <Link to={`/activities/${t.activity_id}`}
                    className="px-1.5 py-1 rounded bg-slate-200 dark:bg-slate-700 text-slate-600 dark:text-slate-300 hover:bg-slate-300 font-medium">View activity</Link>
                )}
                <button onClick={downloadGpx} className="btn btn-neutral btn-sm">Download GPX</button>
                <button onClick={() => setConfirmDelete(true)} className="btn btn-danger btn-sm">Delete</button>
              </>
            )}
          </div>
        )}
      </div>

      {confirmDelete && (
        <div className="pointer-events-auto">
          <ConfirmDialog
            title={`Delete "${t.name}"?`}
            message={t.device_status === "on_device"
              ? "This track will be removed from your watch on the next sync."
              : "This track will be permanently removed."}
            confirmLabel="Delete" danger
            onConfirm={remove} onCancel={() => setConfirmDelete(false)}
          />
        </div>
      )}
    </div>
  );
}
