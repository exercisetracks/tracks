// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The library: every track and place, filed by collection — the phone's
// TrackLibrarySheet, as a panel floating over the map.
//
// Over the map rather than docked beside it, so opening it never moves the
// map; and non-modal, since the map is what the list is about — opening a row
// frames its item there. It stops above the zoom controls, and is sized by its
// edge and remembers its width.
//
// A row's switch-like button sets what the user *wants* on the watch; its
// caption says what the watch has confirmed. Those differ — the phone is not
// always near the watch when somebody decides they want tomorrow's route on it
// — and folding them into one indicator is what makes a sync feel like it lied.
//
// Each named collection is a collection of its own on the watch, on it whole
// or not at all by its switch; the default, "Tracks", has none — its items are
// each their own call.
import { useEffect, useMemo, useRef, useState } from "react";
import {
  DndContext, DragOverlay, PointerSensor, useSensor, useSensors,
  useDraggable, useDroppable, pointerWithin,
} from "@dnd-kit/core";
import { api } from "../../../api/client";
import Switch from "../../../components/ui/Switch";
import Tabs from "../../../components/ui/Tabs";
import { formatGpx } from "../utils/gpx";
import { classifyGpx } from "../utils/gpxImport";
import {
  DEFAULT_COLLECTION, SORTS, countLabel, downloadText, fileSafe, naturalCompare, placeDeviceLabel,
  placeGpx, placeOnWatch, sortItems, trackDeviceLabel, trackOnWatch,
} from "../utils/mapItems";
import { ItemMark } from "./ItemBits";
import EyeToggle from "./EyeToggle";

const WIDTH_KEY = "tracks_library_width";
const SORT_KEY = "tracks_library_sort";
const MIN_W = 300, MAX_W = 640, DEFAULT_W = 380;

const keyOf = (kind, id) => `${kind}-${id}`;

function readWidth() {
  try { const v = Number(localStorage.getItem(WIDTH_KEY)); return v >= MIN_W && v <= MAX_W ? v : DEFAULT_W; }
  catch { return DEFAULT_W; }
}

/** Rename committed on Enter or on leaving the field, never per keystroke. */
function EditableName({ name, onRename, className = "" }) {
  const [draft, setDraft] = useState(name);
  useEffect(() => setDraft(name), [name]);
  const commit = () => {
    const t = draft.trim();
    // A blank name is a rename nobody meant; the old one stands.
    if (t && t !== name) onRename(t); else setDraft(name);
  };
  return (
    <input
      value={draft} onChange={(e) => setDraft(e.target.value)} onBlur={commit}
      onClick={(e) => e.stopPropagation()}
      onKeyDown={(e) => { if (e.key === "Enter") e.currentTarget.blur(); if (e.key === "Escape") { setDraft(name); e.currentTarget.blur(); } }}
      className={`bg-transparent border-b border-dashed border-slate-300 dark:border-slate-600 focus:border-accent-500 outline-none min-w-0 w-full ${className}`}
    />
  );
}

/** Send or take off, edit, export, delete — one row under an open item. */
function Actions({ wanted, hasDevice, deleteQuestion, onSend, onRemove, onEdit, onExport, onDelete, extra }) {
  const [confirming, setConfirming] = useState(false);
  if (confirming) {
    return (
      <div className="flex items-center gap-2">
        <span className="flex-1 text-xs text-slate-600 dark:text-slate-300">{deleteQuestion}</span>
        <button type="button" className="btn btn-neutral btn-sm" onClick={() => setConfirming(false)}>Keep</button>
        <button type="button" className="btn btn-danger btn-sm" onClick={() => { setConfirming(false); onDelete(); }}>Delete</button>
      </div>
    );
  }
  return (
    <div className="flex items-center gap-1.5">
      {hasDevice && (wanted
        ? <button type="button" className="btn btn-neutral btn-sm flex-1" onClick={onRemove}>Off watch</button>
        : <button type="button" className="btn btn-tonal btn-sm flex-1" onClick={onSend}>To watch</button>)}
      <button type="button" className="btn btn-neutral btn-sm flex-1" onClick={onEdit}>Edit</button>
      {extra}
      <button type="button" className="icon-btn icon-btn-sm" title="Export as GPX" onClick={onExport}>
        <svg fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" d="M3 16.5v2.25A2.25 2.25 0 0 0 5.25 21h13.5A2.25 2.25 0 0 0 21 18.75V16.5M16.5 12 12 16.5m0 0L7.5 12m4.5 4.5V3" /></svg>
      </button>
      <button type="button" className="icon-btn icon-btn-sm icon-btn-danger" title="Delete" onClick={() => setConfirming(true)}>
        <svg fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" d="m14.74 9-.346 9m-4.788 0L9.26 9m9.968-3.21c.342.052.682.107 1.022.166m-1.022-.165L18.16 19.673a2.25 2.25 0 0 1-2.244 2.077H8.084a2.25 2.25 0 0 1-2.244-2.077L4.772 5.79m14.456 0a48.108 48.108 0 0 0-3.478-.397m-12 .562c.34-.059.68-.114 1.022-.165m0 0a48.11 48.11 0 0 1 3.478-.397m7.5 0v-.916c0-1.18-.91-2.164-2.09-2.201a51.964 51.964 0 0 0-3.32 0c-1.18.037-2.09 1.022-2.09 2.201v.916m7.5 0a48.667 48.667 0 0 0-7.5 0" /></svg>
      </button>
    </div>
  );
}

function Row({ item, kind, atlas, imperial, hasDevice, open, picking, picked, onRowClick, onPick, onRename, actions, dragRef, dragHandle, isDragging, preview }) {
  const caption = kind === "track"
    ? [
      item.distance_m != null ? (imperial ? `${(item.distance_m / 1609.34).toFixed(1)} mi` : `${(item.distance_m / 1000).toFixed(1)} km`) : null,
      item.ascent_m > 0 ? `↑${imperial ? Math.round(item.ascent_m * 3.28084) + " ft" : Math.round(item.ascent_m) + " m"}` : null,
      hasDevice ? trackDeviceLabel(item) : null,
    ].filter(Boolean).join(" · ")
    : (hasDevice ? placeDeviceLabel(item) : "");

  return (
    <div ref={dragRef}
      className={`group rounded-lg transition-colors ${
        preview ? "bg-white dark:bg-slate-800 shadow-xl ring-1 ring-accent-300 dark:ring-accent-700"
          : isDragging ? "opacity-40"
            : open ? "bg-slate-50 dark:bg-slate-800/70" : "hover:bg-slate-50 dark:hover:bg-slate-800/50"} ${
        kind === "track" && item.hidden && !preview ? "opacity-60" : ""}`}>
      <div className="flex items-center gap-2 px-2 py-1.5 cursor-pointer select-none" onClick={(e) => onRowClick?.(e)}
           // Not draggable while open: its name is a text field then, and
           // selecting text in it would pick the row up instead.
           {...(open ? {} : dragHandle || {})}>
        {!preview && (
          <input
            type="checkbox" checked={picked} onChange={() => {}}
            onClick={(e) => { e.stopPropagation(); onPick(e); }}
            title="Select" aria-label={`Select ${item.name}`}
            className={`shrink-0 ${picking || picked ? "" : "opacity-0 group-hover:opacity-100"}`}
          />
        )}
        <ItemMark kind={kind} color={item.color} icon={item.icon} atlas={atlas} />
        <div className="min-w-0 flex-1">
          {open && !picking
            ? <EditableName name={item.name} onRename={onRename} className="text-sm font-semibold text-slate-800 dark:text-slate-100" />
            : <div className="text-sm font-medium text-slate-800 dark:text-slate-100 truncate">{item.name}</div>}
          {caption && <div className="text-[11px] text-slate-500 dark:text-slate-400 truncate">{caption}</div>}
        </div>
        {!preview && (
          <svg className={`w-3.5 h-3.5 shrink-0 text-slate-400 transition-transform ${open ? "rotate-180" : ""}`} fill="none" stroke="currentColor" strokeWidth={2.5} viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" d="m19.5 8.25-7.5 7.5-7.5-7.5" />
          </svg>
        )}
      </div>
      {open && !picking && !preview && <div className="px-2 pb-2">{actions}</div>}
    </div>
  );
}

function DraggableRow(props) {
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({ id: keyOf(props.kind, props.item.id) });
  return <Row {...props} dragRef={setNodeRef} dragHandle={{ ...listeners, ...attributes }} isDragging={isDragging} />;
}

function CollectionDrop({ id, children }) {
  const { setNodeRef, isOver } = useDroppable({ id });
  return (
    <div ref={setNodeRef} className={`rounded-lg transition-colors ${isOver ? "ring-2 ring-accent-400 bg-accent-50/60 dark:bg-accent-900/20" : ""}`}>
      {children}
    </div>
  );
}

function CollectionHeader({ folder, tracks, places, hasDevice, onWatch, onRename, onDelete }) {
  const [confirming, setConfirming] = useState(false);
  return (
    <div className="flex items-center gap-2 px-2 pt-2 pb-1">
      <div className="min-w-0 flex-1">
        {folder
          ? <EditableName name={folder.name} onRename={onRename} className="text-sm font-semibold text-slate-700 dark:text-slate-200" />
          : <div className="text-sm font-semibold text-slate-700 dark:text-slate-200">{DEFAULT_COLLECTION}</div>}
        <div className="text-[11px] text-slate-400">{countLabel(tracks, places)}</div>
      </div>
      {confirming ? (
        <>
          <button type="button" className="btn btn-neutral btn-sm" onClick={() => setConfirming(false)}>Keep</button>
          <button type="button" className="btn btn-danger btn-sm" onClick={() => { setConfirming(false); onDelete(); }}>Delete</button>
        </>
      ) : folder && (
        <>
          {hasDevice && (
            <label className="flex items-center gap-1.5 text-[11px] text-slate-500 dark:text-slate-400"
                   title="Keep everything in this collection on the watch">
              Watch
              <Switch small checked={folder.load_to_device} onChange={onWatch} label={`${folder.name} on the watch`} />
            </label>
          )}
          <button type="button" className="icon-btn icon-btn-sm icon-btn-danger"
                  title={`Delete collection; its tracks and places move to ${DEFAULT_COLLECTION}`}
                  onClick={() => setConfirming(true)}>×</button>
        </>
      )}
    </div>
  );
}

export default function TrackLibrary({
  open, onClose, tracks, places, folders, atlas, imperial, hasDevice,
  openKey, onRefresh, onShowTrack, onShowPlace, onEditTrack, onEditPlace, onCombine, onCreateFolder,
  onWidth,
}) {
  const [width, setWidth] = useState(readWidth);
  const [scope, setScope] = useState("all");
  const [sort, setSort] = useState(() => { try { return localStorage.getItem(SORT_KEY) || "name"; } catch { return "name"; } });
  const [openRow, setOpenRow] = useState(openKey || null);
  const [picked, setPicked] = useState(() => new Set());
  const [confirmBulk, setConfirmBulk] = useState(false);
  const [naming, setNaming] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [dragging, setDragging] = useState(null);
  const fileRef = useRef(null);
  const anchorRef = useRef(null);   // the last row picked, where a Shift-click range starts
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 6 } }));

  useEffect(() => { if (openKey) setOpenRow(openKey); }, [openKey]);
  // The map's bottom panels keep clear of this one; they need its width.
  useEffect(() => { onWidth?.(open ? width : 0); }, [open, width, onWidth]);
  useEffect(() => { if (!open) { setPicked(new Set()); setConfirmBulk(false); setError(null); } }, [open]);

  // Reopened on an item just saved: bring its row into view.
  useEffect(() => {
    if (!open || !openKey) return;
    requestAnimationFrame(() => document.getElementById(`lib-${openKey}`)?.scrollIntoView({ block: "nearest" }));
  }, [open, openKey]);

  const known = useMemo(() => new Set(folders.map((f) => f.id)), [folders]);
  // An item filed under a collection this list does not have shows in the
  // default, which is where it will land.
  const groupOf = (fid) => (fid != null && known.has(fid) ? fid : null);

  const inScope = (kind, it) => scope === "all" || (kind === "track"
    // "On the watch" is what the watch has confirmed, less what is on its way off.
    ? trackOnWatch(it) && it.load_to_device : placeOnWatch(it) && it.load_to_device);
  const shownTracks = sortItems(tracks.filter((t) => inScope("track", t)), sort);
  const shownPlaces = sortItems(places.filter((p) => inScope("place", p)), sort);

  if (!open) return null;

  const run = (fn) => async (...a) => {
    setError(null);
    try { await fn(...a); await onRefresh(); } catch (e) { setError(e.message || "That did not work"); }
  };
  const patchTrack = run((t, body) => api.updateCourse(t.id, body));
  const patchPlace = run((p, body) => api.updateWaypoint(p.id, body));
  const deleteTrack = run((t) => api.deleteCourse(t.id));
  const deletePlace = run((p) => api.deleteWaypoint(p.id));
  const toFolder = (fid) => (fid != null ? { folder_id: fid } : { clear_folder: true });

  const exportTrack = async (t) => {
    try {
      const d = await api.getCourse(t.id);
      const gj = { type: "FeatureCollection", features: [{ type: "Feature", properties: {}, geometry: { type: "LineString", coordinates: d.geometry || [] } }] };
      downloadText(formatGpx(gj, d.name), fileSafe(d.name));
    } catch (e) { setError(e.message || "Export failed"); }
  };

  const pickedTracks = tracks.filter((t) => picked.has(keyOf("track", t.id)));
  const pickedPlaces = places.filter((p) => picked.has(keyOf("place", p.id)));
  const picking = picked.size > 0;
  const togglePick = (k) => {
    anchorRef.current = k;
    setPicked((s) => { const n = new Set(s); n.has(k) ? n.delete(k) : n.add(k); return n; });
  };
  // Shift-click: everything between the last row picked and this one, in the
  // order the list shows them, as in any desktop file list.
  const pickRange = (k) => {
    const from = order.indexOf(anchorRef.current);
    const to = order.indexOf(k);
    if (from < 0 || to < 0) { togglePick(k); return; }
    const [a, b] = from < to ? [from, to] : [to, from];
    setPicked((s) => new Set([...s, ...order.slice(a, b + 1)]));
  };

  const bulk = run(async (fn) => { await fn(); setPicked(new Set()); });
  const moveTo = (fid) => bulk(() => Promise.all([
    ...pickedTracks.map((t) => api.updateCourse(t.id, toFolder(fid))),
    ...pickedPlaces.map((p) => api.updateWaypoint(p.id, toFolder(fid))),
  ]));
  const setWatch = (on) => bulk(() => Promise.all([
    ...pickedTracks.filter((t) => !!t.load_to_device !== on).map((t) => api.updateCourse(t.id, { load_to_device: on })),
    ...pickedPlaces.filter((p) => !!p.load_to_device !== on).map((p) => api.updateWaypoint(p.id, { load_to_device: on })),
  ]));
  const deletePicked = () => bulk(() => Promise.all([
    ...pickedTracks.map((t) => api.deleteCourse(t.id)),
    ...pickedPlaces.map((p) => api.deleteWaypoint(p.id)),
  ]));

  const createFolder = async (name) => {
    try { const f = await onCreateFolder(name); return f; } catch (e) { setError(e.message); return null; }
  };

  // Any number of files at once. Each is read first and filed as what it
  // is (utils/gpxImport): a line is a track, a point is a place.
  const onImportFile = async (e) => {
    const files = [...(e.target.files || [])];
    e.target.value = "";
    if (!files.length) return;
    setBusy(true); setError(null);
    const failed = [];
    let last = null;
    for (const file of files) {
      try {
        const isGpx = /\.gpx$/i.test(file.name);
        const read = isGpx ? classifyGpx(await file.text(), file.name) : { kind: "track" };
        if (read.kind === "places") {
          for (const p of read.places) {
            const w = await api.createWaypoint({ name: p.name.slice(0, 80), lat: p.lat, lng: p.lng, ele_m: p.ele_m });
            last = { kind: "place", item: w };
          }
        } else if (read.kind === "track") {
          last = { kind: "track", item: await api.importCourse(file) };
        } else {
          failed.push(`${file.name}: no points in it`);
        }
      } catch (err) {
        failed.push(`${file.name}: ${err.message || "import failed"}`);
      }
    }
    await onRefresh();
    if (last) {
      setOpenRow(keyOf(last.kind, last.item.id));
      (last.kind === "track" ? onShowTrack : onShowPlace)(last.item);
    }
    if (failed.length) setError(failed.join(" · "));
    setBusy(false);
  };

  const onRowClick = (kind, it) => (e) => {
    const k = keyOf(kind, it.id);
    // Ctrl/⌘ picks one, Shift a run of them, as in any desktop list.
    if (e.shiftKey && anchorRef.current) { pickRange(k); return; }
    if (picking || e.ctrlKey || e.metaKey || e.shiftKey) { togglePick(k); return; }
    if (openRow === k) { setOpenRow(null); return; }
    setOpenRow(k);
    (kind === "track" ? onShowTrack : onShowPlace)(it);
  };

  const onDragEnd = (e) => {
    setDragging(null);
    const over = e.over?.id;
    if (!over) return;
    const fid = over === "section-default" ? null : Number(String(over).replace("section-", ""));
    const [kind, idStr] = String(e.active.id).split("-");
    const id = Number(idStr);
    if (kind === "track") {
      const t = tracks.find((x) => x.id === id);
      if (t && groupOf(t.folder_id) !== fid) patchTrack(t, toFolder(fid));
    } else {
      const p = places.find((x) => x.id === id);
      if (p && groupOf(p.folder_id) !== fid) patchPlace(p, toFolder(fid));
    }
  };

  const startResize = (e) => {
    e.preventDefault();
    const x0 = e.clientX, w0 = width;
    let w = w0;
    const move = (ev) => { w = Math.max(MIN_W, Math.min(MAX_W, w0 + (x0 - ev.clientX))); setWidth(w); };
    const up = () => {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", up);
      try { localStorage.setItem(WIDTH_KEY, String(w)); } catch { /* not kept */ }
    };
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", up);
  };

  const sections = [null, ...[...folders].sort((a, b) => naturalCompare(a.name, b.name))];
  // Every shown row's key, top to bottom, for Shift-click ranges.
  const order = sections.flatMap((f) => {
    const id = f?.id ?? null;
    return [
      ...shownTracks.filter((t) => groupOf(t.folder_id) === id).map((t) => keyOf("track", t.id)),
      ...shownPlaces.filter((p) => groupOf(p.folder_id) === id).map((p) => keyOf("place", p.id)),
    ];
  });
  const summary = `${countLabel(tracks.filter((t) => inScope("track", t)).length, places.filter((p) => inScope("place", p)).length)}${
    scope === "watch" ? " on the watch" : ""}${picking ? ` · ${picked.size} selected` : ""}`;

  const rowFor = (kind, it) => {
    const k = keyOf(kind, it.id);
    const isTrack = kind === "track";
    const props = {
      item: it, kind, atlas, imperial, hasDevice,
      open: openRow === k, picking, picked: picked.has(k),
      onRowClick: onRowClick(kind, it),
      onPick: (e) => (e?.shiftKey && anchorRef.current ? pickRange(k) : togglePick(k)),
      onRename: (name) => (isTrack ? patchTrack(it, { name }) : patchPlace(it, { name })),
      actions: (
        <Actions
          wanted={!!it.load_to_device} hasDevice={hasDevice}
          deleteQuestion={it.load_to_device ? "Delete from Tracks and the watch?" : "Delete for good?"}
          onSend={() => (isTrack ? patchTrack(it, { load_to_device: true }) : patchPlace(it, { load_to_device: true }))}
          onRemove={() => (isTrack ? patchTrack(it, { load_to_device: false }) : patchPlace(it, { load_to_device: false }))}
          onEdit={() => (isTrack ? onEditTrack(it) : onEditPlace(it))}
          onExport={() => (isTrack ? exportTrack(it) : downloadText(placeGpx(it), fileSafe(it.name)))}
          onDelete={() => (isTrack ? deleteTrack(it) : deletePlace(it))}
          extra={isTrack ? <EyeToggle hidden={it.hidden} size="w-4 h-4" onClick={() => patchTrack(it, { hidden: !it.hidden })} /> : null}
        />
      ),
    };
    return <div key={k} id={`lib-${k}`}><DraggableRow {...props} /></div>;
  };

  return (
    <div className="absolute top-3 right-3 bottom-40 z-30 flex" style={{ width }}>
      {/* The panel's edge sizes it. */}
      <div onPointerDown={startResize} title="Drag to resize"
           className="w-1.5 -mr-1.5 z-10 cursor-col-resize rounded-l hover:bg-accent-400/40 active:bg-accent-500/50 transition-colors" />
      <div className="flex-1 min-w-0 flex flex-col bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 rounded-xl shadow-xl overflow-hidden">
        {/* One row: the view, the order, import and close. A title row above
            it said "My Tracks" over a panel opened from a button saying the
            same, and cost the list its height. */}
        <div className="flex items-center gap-2 px-3 pt-2.5 pb-1.5">
          {hasDevice
            ? <Tabs size="sm" className="shrink-0 flex-nowrap whitespace-nowrap" value={scope} onChange={setScope} tabs={[{ key: "all", label: "Everything" }, { key: "watch", label: "On the watch" }]} />
            : null}
          <span className="flex-1" />
          <button
            type="button" className="btn btn-neutral btn-sm" title="Change the order"
            onClick={() => {
              const next = SORTS[(SORTS.findIndex((x) => x.key === sort) + 1) % SORTS.length].key;
              setSort(next);
              try { localStorage.setItem(SORT_KEY, next); } catch { /* not kept */ }
            }}
          >{SORTS.find((x) => x.key === sort)?.label}</button>
          <button type="button" className="btn btn-neutral btn-sm" disabled={busy} onClick={() => fileRef.current?.click()}
                  title="Import GPX or FIT files — a line becomes a track, a point a place">+GPX</button>
          <input ref={fileRef} type="file" accept=".gpx,.fit" multiple className="hidden" onChange={onImportFile} />
          <button type="button" className="icon-btn icon-btn-sm" title="Close" onClick={onClose}>×</button>
        </div>

        {/* While picking, what to do with all of them. */}
        {picking && (
          <div className="flex items-center gap-1.5 px-3 pb-1.5 flex-wrap">
            {confirmBulk ? (
              <>
                <span className="flex-1 text-xs text-slate-600 dark:text-slate-300">Delete {picked.size} for good?</span>
                <button type="button" className="btn btn-neutral btn-sm" onClick={() => setConfirmBulk(false)}>Keep</button>
                <button type="button" className="btn btn-danger btn-sm" onClick={() => { setConfirmBulk(false); deletePicked(); }}>Delete</button>
              </>
            ) : (
              <>
                {pickedTracks.length >= 2 && (
                  // In list order: what the person sees is the order they meant.
                  <button type="button" className="btn btn-neutral btn-sm"
                          onClick={() => { onCombine(shownTracks.filter((t) => picked.has(keyOf("track", t.id)))); setPicked(new Set()); }}>
                    Combine
                  </button>
                )}
                <select className="field field-sm w-auto" value="" title="Move to a collection"
                        onChange={(e) => {
                          const v = e.target.value;
                          if (v === "__new") setNaming("");
                          else if (v !== "") moveTo(v === "default" ? null : Number(v));
                        }}>
                  <option value="">Move to…</option>
                  <option value="default">{DEFAULT_COLLECTION}</option>
                  {[...folders].sort((a, b) => naturalCompare(a.name, b.name)).map((f) => <option key={f.id} value={f.id}>{f.name}</option>)}
                  <option value="__new">+ New collection…</option>
                </select>
                {hasDevice && <>
                  <button type="button" className="btn btn-tonal btn-sm" onClick={() => setWatch(true)}>To watch</button>
                  <button type="button" className="btn btn-neutral btn-sm" onClick={() => setWatch(false)}>Off watch</button>
                </>}
                <span className="flex-1" />
                <button type="button" className="icon-btn icon-btn-sm icon-btn-danger" title="Delete selected" onClick={() => setConfirmBulk(true)}>
                  <svg fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" d="m14.74 9-.346 9m-4.788 0L9.26 9m9.968-3.21c.342.052.682.107 1.022.166m-1.022-.165L18.16 19.673a2.25 2.25 0 0 1-2.244 2.077H8.084a2.25 2.25 0 0 1-2.244-2.077L4.772 5.79m14.456 0a48.108 48.108 0 0 0-3.478-.397m-12 .562c.34-.059.68-.114 1.022-.165m0 0a48.11 48.11 0 0 1 3.478-.397m7.5 0v-.916c0-1.18-.91-2.164-2.09-2.201a51.964 51.964 0 0 0-3.32 0c-1.18.037-2.09 1.022-2.09 2.201v.916m7.5 0a48.667 48.667 0 0 0-7.5 0" /></svg>
                </button>
                <button type="button" className="icon-btn icon-btn-sm" title="Stop selecting" onClick={() => setPicked(new Set())}>×</button>
              </>
            )}
          </div>
        )}

        {naming != null && (
          <div className="flex items-center gap-1.5 px-3 pb-1.5">
            <input autoFocus className="field field-sm flex-1" placeholder="Collection name" value={naming}
                   onChange={(e) => setNaming(e.target.value || "")}
                   onKeyDown={async (e) => {
                     if (e.key === "Escape") setNaming(null);
                     if (e.key === "Enter" && e.currentTarget.value.trim()) {
                       const moving = picking;
                       const f = await createFolder(e.currentTarget.value.trim());
                       setNaming(null);
                       if (f && moving) moveTo(f.id); else onRefresh();
                     }
                   }} />
            <button type="button" className="icon-btn icon-btn-sm" title="Cancel" onClick={() => setNaming(null)}>×</button>
          </div>
        )}

        <p className="px-3 pb-1 text-[11px] text-slate-500 dark:text-slate-400">{summary}</p>
        {error && <p className="mx-3 mb-1 px-2 py-1 rounded text-xs text-rose-600 bg-rose-50 dark:bg-rose-900/20">{error}</p>}

        <DndContext sensors={sensors} collisionDetection={pointerWithin}
                    onDragStart={(e) => setDragging(String(e.active.id))} onDragEnd={onDragEnd} onDragCancel={() => setDragging(null)}>
          <div className="flex-1 overflow-y-auto px-1.5 pb-3">
            {shownTracks.length === 0 && shownPlaces.length === 0 && (
              <p className="text-xs text-slate-400 p-4 text-center">
                {scope === "watch" ? "Nothing on the watch."
                  : "Nothing yet — Draw one, import a GPX, or right-click the map to start a track or place there."}
              </p>
            )}
            {sections.map((f) => {
              const id = f?.id ?? null;
              const sTracks = shownTracks.filter((t) => groupOf(t.folder_id) === id);
              const sPlaces = shownPlaces.filter((p) => groupOf(p.folder_id) === id);
              // A collection with nothing in this view shows only under
              // "Everything", where a new, empty one has to be seen to be filled.
              if (!sTracks.length && !sPlaces.length && (scope !== "all" || (f == null && !dragging))) return null;
              return (
                <CollectionDrop key={id ?? "default"} id={`section-${id ?? "default"}`}>
                  <CollectionHeader
                    folder={f} tracks={sTracks.length} places={sPlaces.length} hasDevice={hasDevice}
                    onWatch={(on) => run(() => api.updateCourseFolder(f.id, { load_to_device: on }))()}
                    onRename={(name) => run(() => api.updateCourseFolder(f.id, { name }))()}
                    onDelete={() => run(() => api.deleteCourseFolder(f.id))()}
                  />
                  {sTracks.map((t) => rowFor("track", t))}
                  {sPlaces.map((p) => rowFor("place", p))}
                  {!sTracks.length && !sPlaces.length && (
                    <p className="text-[11px] text-slate-400 px-2 pb-1">Empty — drag tracks and places here.</p>
                  )}
                  <div className="border-b border-slate-100 dark:border-slate-800 mx-2 my-1" />
                </CollectionDrop>
              );
            })}
            {naming == null && (
              <button type="button" className="btn btn-neutral btn-sm m-2" onClick={() => setNaming("")}>+ New collection</button>
            )}
          </div>
          <DragOverlay>
            {dragging ? (() => {
              const [kind, idStr] = dragging.split("-");
              const it = (kind === "track" ? tracks : places).find((x) => x.id === Number(idStr));
              return it ? <Row item={it} kind={kind} atlas={atlas} imperial={imperial} hasDevice={hasDevice} preview /> : null;
            })() : null}
          </DragOverlay>
        </DndContext>
      </div>
    </div>
  );
}
