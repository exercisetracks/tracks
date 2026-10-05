// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useState, useRef, useEffect } from "react";
import {
  DndContext, DragOverlay, PointerSensor, useSensor, useSensors,
  useDraggable, useDroppable, pointerWithin,
} from "@dnd-kit/core";
import { api } from "../../../api/client";
import EyeToggle from "./EyeToggle";

// The "large popup" track manager. Lists the user's saved courses grouped into
// folders (+ an Ungrouped section and any device-discovered external ones), with
// create entry points and per-folder watch sync. Tracks can be dragged into
// folders (animated via @dnd-kit — the same library used in WorkoutsTab) or moved
// with the per-row dropdown. Clicking a track selects it on the map.

const STATUS_BADGE = {
  on_device:      ["On watch",         "bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300"],
  pending_upload: ["Loading next sync", "bg-sky-100 text-sky-700 dark:bg-sky-900/40 dark:text-sky-300"],
  pending_remove: ["Removing next sync","bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300"],
  external:       ["External",         "bg-slate-200 text-slate-600 dark:bg-slate-700 dark:text-slate-300"],
};

function fmtDist(m, imperial) {
  if (m == null) return "—";
  return imperial ? `${(m / 1609.34).toFixed(1)} mi` : `${(m / 1000).toFixed(1)} km`;
}
function fmtEle(m, imperial) {
  if (m == null) return "—";
  return imperial ? `${Math.round(m * 3.28084).toLocaleString()} ft` : `${Math.round(m)} m`;
}

function WatchToggle({ on, onClick, title }) {
  return (
    <span onClick={onClick} className="shrink-0 flex items-center gap-1.5" title={title}>
      <svg className={`w-3.5 h-3.5 ${on ? "text-accent-600 dark:text-accent-400" : "text-slate-400"}`}
           fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
        <rect x="7" y="7" width="10" height="10" rx="2" />
        <path strokeLinecap="round" d="M9 7V4.5A1.5 1.5 0 0 1 10.5 3h3A1.5 1.5 0 0 1 15 4.5V7M9 17v2.5A1.5 1.5 0 0 0 10.5 21h3a1.5 1.5 0 0 0 1.5-1.5V17" />
      </svg>
      <span className={`relative inline-flex h-4 w-7 items-center rounded-full transition-colors ${
        on ? "bg-accent-600" : "bg-slate-300 dark:bg-slate-600"}`}>
        <span className={`inline-block h-3 w-3 transform rounded-full bg-white transition-transform ${
          on ? "translate-x-3.5" : "translate-x-0.5"}`} />
      </span>
    </span>
  );
}

function TrackRow({ t, folders, imperial, onSelect, onToggleLoad, onToggleHidden, onMove, dragRef, dragHandle, isDragging, preview }) {
  const badge = STATUS_BADGE[t.device_status];
  return (
    <div ref={dragRef}
      className={`flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg transition-colors ${
        preview ? "bg-white dark:bg-slate-800 shadow-xl ring-1 ring-accent-300 dark:ring-accent-700"
        : isDragging ? "opacity-40" : "hover:bg-slate-50 dark:hover:bg-slate-800"} ${
        t.hidden && !preview ? "opacity-55" : ""}`}>
      {!t.is_external && dragHandle && !preview && (
        <button {...dragHandle} title="Drag into a folder"
          className="shrink-0 text-slate-300 dark:text-slate-600 hover:text-slate-500 cursor-grab active:cursor-grabbing touch-none p-0.5">
          <svg className="w-3 h-3" fill="currentColor" viewBox="0 0 24 24">
            <circle cx="8" cy="6" r="1.6" /><circle cx="8" cy="12" r="1.6" /><circle cx="8" cy="18" r="1.6" />
            <circle cx="15" cy="6" r="1.6" /><circle cx="15" cy="12" r="1.6" /><circle cx="15" cy="18" r="1.6" />
          </svg>
        </button>
      )}
      <button onClick={() => onSelect?.(t)} className="flex items-center gap-2 min-w-0 flex-1 text-left">
        <span className="w-3 h-3 rounded-full shrink-0 ring-1 ring-black/10" style={{ backgroundColor: t.color }} />
        <div className="min-w-0 flex-1">
          <div className="text-sm font-medium text-slate-800 dark:text-slate-100 truncate">{t.name}</div>
          <div className="text-[11px] text-slate-500 dark:text-slate-400">
            {fmtDist(t.distance_m, imperial)} · ↑{fmtEle(t.ascent_m, imperial)}
            {t.turn_by_turn && <span className="ml-1.5 text-accent-600 dark:text-accent-400">· turn-by-turn</span>}
          </div>
        </div>
      </button>
      {badge && <span className={`shrink-0 px-1 py-0.5 rounded text-[9px] font-semibold ${badge[1]}`}>{badge[0]}</span>}
      {!preview && !t.is_external && folders.length > 0 && (
        <select
          value={t.folder_id ?? ""}
          onClick={(e) => e.stopPropagation()}
          onChange={(e) => { e.stopPropagation(); onMove(t, e.target.value ? Number(e.target.value) : null); }}
          title="Move to folder"
          className="shrink-0 max-w-[78px] text-[10px] rounded border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-500 dark:text-slate-300 px-1 py-0.5 outline-none"
        >
          <option value="">No folder</option>
          {folders.map((f) => <option key={f.id} value={f.id}>{f.name}</option>)}
        </select>
      )}
      {!preview && !t.is_external && onToggleHidden && (
        <EyeToggle hidden={t.hidden} size="w-3.5 h-3.5"
          onClick={(e) => { e.stopPropagation(); onToggleHidden(t); }} />
      )}
      {!preview && !t.is_external && (
        <WatchToggle on={t.load_to_device} onClick={(e) => { e.stopPropagation(); onToggleLoad(t); }}
          title="Sync this track to your Garmin watch on the next sync" />
      )}
    </div>
  );
}

function DraggableTrackRow({ t, rowProps }) {
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({ id: t.id });
  return <TrackRow t={t} {...rowProps} dragRef={setNodeRef} dragHandle={{ ...listeners, ...attributes }} isDragging={isDragging} />;
}

function Droppable({ id, className = "", activeClass = "", children }) {
  const { setNodeRef, isOver } = useDroppable({ id });
  return <div ref={setNodeRef} className={`${className} ${isOver ? activeClass : ""}`}>{children}</div>;
}

const DROP_ACTIVE = "ring-2 ring-accent-400 bg-accent-50/60 dark:bg-accent-900/20";

export default function CustomTrackManager({ open, onClose, tracks, folders = [], imperial, onRefresh, onSelect, onStartBuilder }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [newFolder, setNewFolder] = useState(null);  // "" while typing | null
  const [collapsed, setCollapsed] = useState(() => new Set());
  const [activeTrack, setActiveTrack] = useState(null);
  const fileRef = useRef(null);
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 6 } }));

  useEffect(() => { if (!open) { setError(null); setNewFolder(null); } }, [open]);

  if (!open) return null;

  const mine = tracks.filter((t) => !t.is_external);
  const external = tracks.filter((t) => t.is_external);
  const ungrouped = mine.filter((t) => !t.folder_id);

  const wrap = (fn) => async (...a) => { try { await fn(...a); onRefresh(); } catch (e) { setError(e.message); } };
  const toggleLoad   = wrap((t) => api.updateCourse(t.id, { load_to_device: !t.load_to_device }));
  const toggleHidden = wrap((t) => api.updateCourse(t.id, { hidden: !t.hidden }));
  const moveTrack    = wrap((t, fid) => api.updateCourse(t.id, fid ? { folder_id: fid } : { clear_folder: true }));
  const toggleFolder = wrap((f) => api.updateCourseFolder(f.id, { load_to_device: !f.load_to_device }));
  const removeFolder = wrap((f) => api.deleteCourseFolder(f.id));
  const createFolder = wrap(async (name) => { await api.createCourseFolder({ name: name || "Folder" }); setNewFolder(null); });

  const toggleCollapse = (id) => setCollapsed((s) => { const n = new Set(s); n.has(id) ? n.delete(id) : n.add(id); return n; });

  const onDragStart = (e) => setActiveTrack(tracks.find((t) => t.id === e.active.id) || null);
  const onDragEnd = (e) => {
    setActiveTrack(null);
    const over = e.over;
    const tk = tracks.find((t) => t.id === e.active.id);
    if (!over || !tk) return;
    const target = over.id === "ungrouped" ? null
      : String(over.id).startsWith("folder-") ? Number(String(over.id).slice(7)) : undefined;
    if (target !== undefined && tk.folder_id !== target) moveTrack(tk, target);
  };

  const onImportFile = async (e) => {
    const file = e.target.files?.[0];
    e.target.value = "";
    if (!file) return;
    setBusy(true); setError(null);
    try { const t = await api.importCourse(file); onRefresh(); onSelect(t); }
    catch (err) { setError(err.message || "Import failed"); }
    finally { setBusy(false); }
  };

  const rowProps = { folders, imperial, onSelect, onToggleLoad: toggleLoad, onToggleHidden: toggleHidden, onMove: moveTrack };

  return (
    <div className="absolute inset-0 z-40 flex items-center justify-center p-3.5" onClick={onClose}>
      <div className="absolute inset-0 bg-black/30 backdrop-blur-[2px]" />
      <div onClick={(e) => e.stopPropagation()}
        className="relative w-full max-w-lg max-h-[80vh] flex flex-col bg-white dark:bg-slate-900 rounded-2xl shadow-2xl border border-slate-200 dark:border-slate-700 overflow-hidden">
        {/* Header */}
        <div className="flex items-center justify-between px-3.5 py-2.5 border-b border-slate-100 dark:border-slate-800">
          <h2 className="text-sm font-semibold text-slate-800 dark:text-slate-100">My Tracks</h2>
          <button onClick={onClose}
            className="text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 text-xl leading-none">×</button>
        </div>

        {error && <div className="px-3.5 py-1.5 text-xs text-rose-600 bg-rose-50 dark:bg-rose-900/20">{error}</div>}

        {(
          <>
            {/* Create entry points (build an activity-based track by clicking the
                activity on the map instead — no picker here). */}
            <div className="grid grid-cols-2 gap-2 px-2.5 pt-2.5 pb-1.5">
              <button onClick={() => { onStartBuilder(); onClose(); }}
                className="btn btn-tonal gap-1">
                <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" d="M9.53 16.122a3 3 0 00-5.78 1.128 2.25 2.25 0 01-2.4 2.245 4.5 4.5 0 008.4-2.245c0-.399-.078-.78-.22-1.128zm0 0a15.998 15.998 0 003.388-1.62m-5.043-.025a15.994 15.994 0 011.622-3.395m3.42 3.42a15.995 15.995 0 004.764-4.648l3.876-5.814a1.151 1.151 0 00-1.597-1.597L14.146 6.32a15.996 15.996 0 00-4.649 4.763m3.42 3.42a6.776 6.776 0 00-3.42-3.42" /></svg>
                <span className="text-[10px] font-medium">Draw</span>
              </button>
              <button onClick={() => fileRef.current?.click()} disabled={busy}
                className="btn btn-neutral gap-1">
                <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" d="M3 16.5v2.25A2.25 2.25 0 005.25 21h13.5A2.25 2.25 0 0021 18.75V16.5M16.5 12L12 16.5m0 0L7.5 12m4.5 4.5V3" /></svg>
                <span className="text-[10px] font-medium">Import</span>
              </button>
              <input ref={fileRef} type="file" accept=".gpx" className="hidden" onChange={onImportFile} />
            </div>

            {/* New-folder control */}
            <div className="px-2.5 pb-1.5 border-b border-slate-100 dark:border-slate-800">
              {newFolder == null ? (
                <button onClick={() => setNewFolder("")}
                  className="btn btn-tonal btn-sm gap-1.5">
                  <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" d="M12 10.5v6m3-3H9m4.06-7.19-2.12-2.12a1.5 1.5 0 0 0-1.061-.44H4.5A2.25 2.25 0 0 0 2.25 6v12a2.25 2.25 0 0 0 2.25 2.25h15A2.25 2.25 0 0 0 21.75 18V9a2.25 2.25 0 0 0-2.25-2.25h-5.379a1.5 1.5 0 0 1-1.06-.44Z" /></svg>
                  New folder
                </button>
              ) : (
                <div className="flex items-center gap-1.5">
                  <input autoFocus value={newFolder} onChange={(e) => setNewFolder(e.target.value)}
                    onKeyDown={(e) => { if (e.key === "Enter") createFolder(newFolder.trim()); if (e.key === "Escape") setNewFolder(null); }}
                    placeholder="Folder name"
                    className="flex-1 px-1.5 py-1 rounded border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-xs text-slate-700 dark:text-slate-200 outline-none focus:border-accent-400" />
                  <button onClick={() => createFolder(newFolder.trim())}
                    className="btn btn-primary btn-sm">Add</button>
                  <button onClick={() => setNewFolder(null)} className="text-slate-400 hover:text-slate-600 px-1">×</button>
                </div>
              )}
            </div>

            {/* List */}
            <DndContext sensors={sensors} collisionDetection={pointerWithin} onDragStart={onDragStart} onDragEnd={onDragEnd}>
              <div className="overflow-y-auto p-1.5 flex-1">
                {tracks.length > 0 && (
                  <p className="flex items-center gap-1.5 text-[10px] text-slate-400 dark:text-slate-500 px-2.5 pb-1">
                    <svg className="w-3 h-3" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
                      <rect x="7" y="7" width="10" height="10" rx="2" />
                      <path strokeLinecap="round" d="M9 7V4.5A1.5 1.5 0 0 1 10.5 3h3A1.5 1.5 0 0 1 15 4.5V7M9 17v2.5A1.5 1.5 0 0 0 10.5 21h3a1.5 1.5 0 0 0 1.5-1.5V17" />
                    </svg>
                    Drag tracks into folders. Toggle the watch icon to sync a track or folder to your Garmin.
                  </p>
                )}
                {tracks.length === 0 && (
                  <p className="text-xs text-slate-400 p-5 text-center">
                    No tracks yet. Draw a route, import a GPX, or build one from an activity.
                  </p>
                )}

                {/* Folders — each is a drop target */}
                {folders.map((f) => {
                  const items = mine.filter((t) => t.folder_id === f.id);
                  const isCollapsed = collapsed.has(f.id);
                  return (
                    <Droppable key={f.id} id={`folder-${f.id}`} className="mb-0.5 rounded-lg transition-colors" activeClass={DROP_ACTIVE}>
                      <div className="flex items-center gap-1.5 px-1.5 py-1 rounded-lg hover:bg-slate-50 dark:hover:bg-slate-800/60">
                        <button onClick={() => toggleCollapse(f.id)} className="flex items-center gap-1.5 min-w-0 flex-1 text-left">
                          <svg className={`w-3 h-3 shrink-0 text-slate-400 transition-transform ${isCollapsed ? "" : "rotate-90"}`} fill="none" stroke="currentColor" strokeWidth={2.5} viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" d="M9 5l7 7-7 7" /></svg>
                          <span className="w-2.5 h-2.5 rounded-full shrink-0 ring-1 ring-black/10" style={{ backgroundColor: f.color }} />
                          <span className="text-xs font-semibold text-slate-700 dark:text-slate-200 truncate">{f.name}</span>
                          <span className="text-[10px] text-slate-400">{items.length}</span>
                        </button>
                        <WatchToggle on={f.load_to_device} onClick={() => toggleFolder(f)} title="Sync this whole folder to the watch" />
                        <button onClick={() => removeFolder(f)} title="Delete folder (tracks are kept)"
                          className="shrink-0 text-slate-300 dark:text-slate-600 hover:text-rose-500">
                          <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" /></svg>
                        </button>
                      </div>
                      {!isCollapsed && (
                        <div className="pl-2.5">
                          {items.length === 0
                            ? <p className="text-[11px] text-slate-400 px-2.5 py-1">Empty — drag tracks here or use the dropdown.</p>
                            : items.map((t) => <DraggableTrackRow key={t.id} t={t} rowProps={rowProps} />)}
                        </div>
                      )}
                    </Droppable>
                  );
                })}

                {/* Ungrouped — drop here to remove from a folder */}
                {(ungrouped.length > 0 || (activeTrack && folders.length > 0)) && (
                  <Droppable id="ungrouped" className="rounded-lg transition-colors" activeClass={DROP_ACTIVE}>
                    {folders.length > 0 && <div className="text-[10px] font-semibold uppercase tracking-wide text-slate-400 px-2.5 mt-2 mb-1">Ungrouped</div>}
                    {ungrouped.map((t) => <DraggableTrackRow key={t.id} t={t} rowProps={rowProps} />)}
                    {ungrouped.length === 0 && activeTrack && (
                      <p className="text-[11px] text-slate-400 px-2.5 py-1.5">Drop here to remove from its folder.</p>
                    )}
                  </Droppable>
                )}

                {/* External (on the watch, not made here) */}
                {external.length > 0 && (
                  <>
                    <div className="text-[10px] font-semibold uppercase tracking-wide text-slate-400 px-2.5 mt-3 mb-1">On the watch (external)</div>
                    {external.map((t) => <TrackRow key={t.id} t={t} {...rowProps} />)}
                  </>
                )}
              </div>

              <DragOverlay>
                {activeTrack ? <TrackRow t={activeTrack} {...rowProps} preview /> : null}
              </DragOverlay>
            </DndContext>
          </>
        )}
      </div>
    </div>
  );
}
