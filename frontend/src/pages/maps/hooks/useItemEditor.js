// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// useItemEditor — drawing and editing tracks and places on the map, the way
// the phone's editor does it (MapToolsViewModel's RouteDraft).
//
// One editor for everything about an item: its line (a track) or its spot
// (a place), and its name, colour, collection and symbol, all committed by one
// Save — for a new drawing as for an edit, so naming is not a second step
// after saving. A fresh drawing of one handle is a place; two or more, a
// track. Clicking the map adds a handle (or, for a place, moves it there);
// handles drag; clicking one offers to delete it.
//
// A track opened for editing gets back the handles it was drawn with when this
// browser drew it, or handles simplified out of its line otherwise — and then
// opens with snapping off, so nothing moves until someone moves it: between two
// handles still where they were, the original line is kept exactly, detail and
// elevations included (utils/mapItems.stitchLine).
import { useState, useCallback, useRef, useEffect, useMemo } from "react";
import maplibregl from "../../../lib/maplibre";
import { api } from "../../../api/client";
import {
  DEFAULT_PLACE_COLOR, SIMPLIFY_MAX_M, handleIndices, handlesFromLine, haversineKm,
  loadHandles, randomTrackColor, reduceLine, saveHandles, stitchLine,
} from "../utils/mapItems";
import { isPin, placeDataUrl, placeImageId } from "../utils/placeIcons";

const LINE_SRC = "editor_line";
const LINE_LAYER = "editor_line";
const LINE_CASING = "editor_line_casing";
const CURSOR_SRC = "editor_cursor";
const CURSOR_LAYER = "editor_cursor";

const EMPTY = { type: "FeatureCollection", features: [] };
const lineFeature = (coords) => ({
  type: "FeatureCollection",
  features: coords?.length > 1
    ? [{ type: "Feature", properties: {}, geometry: { type: "LineString", coordinates: coords } }]
    : [],
});

// The theme accent as rgb(): MapLibre's colour parser does not read CSS var().
function accent(level = 600) {
  try {
    const v = getComputedStyle(document.documentElement).getPropertyValue(`--accent-${level}`).trim();
    if (v) return `rgb(${v})`;
  } catch { /* default below */ }
  return "#2563eb";
}

// Points every `stepM` metres along a straight join, so an un-snapped line's
// height profile has ground under it to sample.
function densify(a, b, stepM = 90, maxPts = 600) {
  const n = Math.min(maxPts, Math.floor((haversineKm(a, b) * 1000) / stepM));
  const out = [];
  for (let k = 1; k < n; k++) {
    const t = k / n;
    out.push([a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t]);
  }
  return out;
}

const CLOSED = {
  open: false, kind: null, id: null, handles: [], snap: true,
  name: "", color: null, icon: "marker", folderId: null, activityId: null,
  baseLine: null, baseHandles: [], originalLine: null, simplify: 0,
  // Whether the line or spot has been touched. An edit that only renames a
  // track must not send its line back: the server takes a new line as a new
  // course and puts it on the watch again.
  dirty: false,
};

export function useItemEditor(map, { atlas, onSaved } = {}) {
  const [draft, setDraft] = useState(CLOSED);
  const [line, setLine] = useState(null);          // [[lng, lat, ele?], …] for a track
  const [routing, setRouting] = useState(false);
  const [error, setError] = useState(null);
  const [saving, setSaving] = useState(false);
  const [menu, setMenu] = useState(null);           // handle index whose menu is open

  const markers = useRef([]);
  const draftRef = useRef(draft);
  draftRef.current = draft;
  const reqId = useRef(0);
  const timer = useRef(null);

  const isPlace = draft.kind === "place" || (draft.kind === "new" && draft.handles.length === 1);

  // ── Map layers ─────────────────────────────────────────────────────────────
  const ensureLayers = useCallback(() => {
    if (!map) return;
    try {
      if (!map.getSource(LINE_SRC)) map.addSource(LINE_SRC, { type: "geojson", data: EMPTY });
      if (!map.getLayer(LINE_CASING)) {
        map.addLayer({
          id: LINE_CASING, type: "line", source: LINE_SRC,
          layout: { "line-cap": "round", "line-join": "round" },
          paint: { "line-color": "#ffffff", "line-width": 7, "line-opacity": 0.75 },
        });
      }
      if (!map.getLayer(LINE_LAYER)) {
        map.addLayer({
          id: LINE_LAYER, type: "line", source: LINE_SRC,
          layout: { "line-cap": "round", "line-join": "round" },
          paint: { "line-color": accent(), "line-width": 4, "line-opacity": 0.95 },
        });
      }
      if (!map.getSource(CURSOR_SRC)) map.addSource(CURSOR_SRC, { type: "geojson", data: EMPTY });
      if (!map.getLayer(CURSOR_LAYER)) {
        map.addLayer({
          id: CURSOR_LAYER, type: "circle", source: CURSOR_SRC,
          paint: {
            "circle-radius": 6, "circle-color": "#ffffff",
            "circle-stroke-color": "#0f172a", "circle-stroke-width": 2.5,
          },
        });
      }
    } catch { /* style mid-reload; styledata re-adds */ }
  }, [map]);

  const removeLayers = useCallback(() => {
    if (!map) return;
    for (const id of [LINE_LAYER, LINE_CASING, CURSOR_LAYER]) { try { map.removeLayer(id); } catch { /* gone */ } }
    for (const id of [LINE_SRC, CURSOR_SRC]) { try { map.removeSource(id); } catch { /* gone */ } }
  }, [map]);

  // Draw the line in the item's own colour, dashed while it is a straight
  // guess rather than a routed path.
  useEffect(() => {
    if (!map || !draft.open) return;
    ensureLayers();
    try {
      map.getSource(LINE_SRC)?.setData(isPlace ? EMPTY : lineFeature(line));
      map.setPaintProperty(LINE_LAYER, "line-color", draft.color || accent());
    } catch { /* not ready */ }
  }, [map, draft.open, draft.color, line, isPlace, ensureLayers]);

  // A style reload (theme switch, a region landing) takes added layers with it.
  useEffect(() => {
    if (!map || !draft.open) return undefined;
    const again = () => {
      ensureLayers();
      try { map.getSource(LINE_SRC)?.setData(isPlace ? EMPTY : lineFeature(line)); } catch { /* not ready */ }
    };
    map.on("styledata", again);
    return () => map.off("styledata", again);
  }, [map, draft.open, line, isPlace, ensureLayers]);

  // ── Handles ────────────────────────────────────────────────────────────────
  const clearMarkers = () => { markers.current.forEach((m) => m.remove()); markers.current = []; };

  const update = useCallback((patch) => setDraft((d) => ({ ...d, ...(typeof patch === "function" ? patch(d) : patch) })), []);

  // Rebuilt whenever the handles or the look of the place change. Cheap: a
  // track has at most a few dozen.
  useEffect(() => {
    if (!map || !draft.open) return undefined;
    clearMarkers();
    const asPlace = isPlace;
    draft.handles.forEach((h, i) => {
      let el;
      let anchor = "center";
      if (asPlace) {
        // A place being edited moves as itself, not as a handle beside the
        // spot it used to be.
        const id = placeImageId(draft.icon, draft.color || DEFAULT_PLACE_COLOR, atlas);
        el = document.createElement("img");
        el.src = placeDataUrl(atlas, draft.icon, draft.color || DEFAULT_PLACE_COLOR);
        Object.assign(el.style, { width: isPin(id) ? "22px" : "25px", cursor: "grab" });
        anchor = isPin(id) ? "bottom" : "center";
      } else {
        el = document.createElement("div");
        el.textContent = String(i + 1);
        Object.assign(el.style, {
          width: "18px", height: "18px", borderRadius: "9999px",
          background: draft.color || "rgb(var(--accent-600))", color: "#fff",
          font: "700 10px/18px ui-sans-serif, system-ui, sans-serif",
          textAlign: "center", border: "2px solid #fff",
          boxShadow: "0 1px 3px rgba(0,0,0,0.45)", cursor: "grab",
        });
      }
      el.title = asPlace ? "Drag to move" : "Drag to move · click for options";
      const marker = new maplibregl.Marker({ element: el, draggable: true, anchor }).setLngLat(h).addTo(map);
      // A drag ends in a click on the element; that one is not a request for
      // the handle's options.
      let dragged = false;
      marker.on("dragstart", () => { dragged = true; });
      marker.on("dragend", () => {
        const ll = marker.getLngLat();
        update((d) => ({ dirty: true, handles: d.handles.map((p, k) => (k === i ? [ll.lng, ll.lat] : p)) }));
      });
      if (!asPlace) {
        el.addEventListener("click", (e) => {
          e.stopPropagation();
          if (dragged) { dragged = false; return; }
          setMenu(i);
        });
      }
      markers.current.push(marker);
    });
    return clearMarkers;
  }, [map, draft.open, draft.handles, draft.icon, draft.color, isPlace, atlas, update]);

  // Clicking the map adds a handle — or, for a place being edited, moves it.
  useEffect(() => {
    if (!map || !draft.open) return undefined;
    const onClick = (e) => {
      setMenu(null);
      const ll = [e.lngLat.lng, e.lngLat.lat];
      update((d) => (d.kind === "place" ? { handles: [ll], dirty: true } : { handles: [...d.handles, ll], dirty: true }));
    };
    map.getCanvas().style.cursor = "crosshair";
    map.on("click", onClick);
    return () => {
      map.off("click", onClick);
      map.getCanvas().style.cursor = "";
    };
  }, [map, draft.open, update]);

  // ── The line ───────────────────────────────────────────────────────────────
  // Un-snapped: the original between unmoved handles, straight joins
  // elsewhere, with ground sampled under the joins.
  const straight = useCallback(async (handles, d) => {
    const stitched = stitchLine(handles, d.baseLine, d.baseHandles);
    const out = [];
    const need = [];
    stitched.forEach((p, i) => {
      if (i > 0 && p[2] == null && stitched[i - 1]) {
        for (const q of densify(stitched[i - 1], p)) { need.push(out.length); out.push(q); }
      }
      if (p[2] == null) need.push(out.length);
      out.push(p.length > 2 ? [...p] : [p[0], p[1]]);
    });
    if (need.length) {
      try {
        const r = await api.routeElevation({ coordinates: need.map((k) => [out[k][0], out[k][1]]) });
        (r.elevations || []).forEach((e, j) => { if (e != null) out[need[j]] = [out[need[j]][0], out[need[j]][1], e]; });
      } catch { /* elevation is best-effort */ }
    }
    return out;
  }, []);

  useEffect(() => {
    if (!draft.open || isPlace) { setLine(null); return undefined; }
    const handles = draft.handles;
    if (handles.length < 2) { setLine(null); setError(null); return undefined; }
    const id = ++reqId.current;
    clearTimeout(timer.current);
    timer.current = setTimeout(async () => {
      setRouting(true);
      setError(null);
      try {
        let coords;
        if (draft.snap) {
          try {
            const gj = await api.snapRoute({ coordinates: handles.map((h) => [h[0], h[1]]) });
            coords = gj?.features?.[0]?.geometry?.coordinates;
          } catch {
            coords = null;
          }
          if (!coords && id === reqId.current) {
            // A point far from any trail: a direct line, so there is always
            // a preview and a profile.
            setError("Couldn't follow trails for part of this — showing a direct line.");
            coords = await straight(handles, draft);
          }
        } else {
          coords = await straight(handles, draft);
        }
        if (id === reqId.current) setLine(coords);
      } finally {
        if (id === reqId.current) setRouting(false);
      }
    }, 280);
    return () => clearTimeout(timer.current);
  }, [draft.open, draft.handles, draft.snap, draft.baseLine, draft.baseHandles, isPlace, straight]); // eslint-disable-line react-hooks/exhaustive-deps

  const stats = useMemo(() => {
    if (!line || line.length < 2) return null;
    let km = 0, gain = 0, loss = 0;
    for (let i = 1; i < line.length; i++) {
      km += haversineKm(line[i - 1], line[i]);
      const a = line[i - 1][2], b = line[i][2];
      if (a != null && b != null) { if (b > a) gain += b - a; else loss += a - b; }
    }
    return { km, gain: Math.round(gain), loss: Math.round(loss) };
  }, [line]);

  // ── Opening and closing ────────────────────────────────────────────────────
  const close = useCallback(() => {
    clearMarkers();
    removeLayers();
    setDraft(CLOSED);
    setLine(null);
    setError(null);
    setMenu(null);
    setSaving(false);
  }, [removeLayers]);

  /** A new drawing, optionally starting with a handle (a right-click's spot) and a name. */
  const startNew = useCallback((at = null, { name = "", folderId = null } = {}) => {
    clearMarkers();
    setLine(null); setError(null); setMenu(null);
    setDraft({ ...CLOSED, open: true, kind: "new", handles: at ? [at] : [], name, color: randomTrackColor(), folderId });
  }, []);

  /** A new track along a line that already exists — an activity, or tracks combined. */
  const startFromLine = useCallback((coords, { name = "", color, activityId = null, folderId = null } = {}) => {
    const lineIn = (coords || []).filter((c) => c && c[0] != null && c[1] != null);
    const handles = handlesFromLine(lineIn);
    if (handles.length < 2) return false;
    clearMarkers();
    setError(null); setMenu(null);
    setDraft({
      ...CLOSED, open: true, kind: "new", handles, snap: false,
      name, color: color || randomTrackColor(), activityId, folderId,
      baseLine: lineIn, baseHandles: handleIndices(handles, lineIn), originalLine: lineIn,
    });
    return true;
  }, []);

  const editTrack = useCallback(async (track) => {
    let detail = track;
    if (!detail?.geometry) {
      try { detail = await api.getCourse(track.id); } catch { return false; }
    }
    const stored = (detail.geometry || []).filter((c) => c && c[0] != null && c[1] != null);
    const saved = loadHandles(detail.id);
    const handles = saved?.handles || handlesFromLine(stored);
    if (handles.length < 2) return false;
    clearMarkers();
    setError(null); setMenu(null);
    setDraft({
      ...CLOSED, open: true, kind: "track", id: detail.id, handles,
      // Re-routing simplified points would move the whole line.
      snap: saved ? !!saved.snapToTrails : false,
      name: detail.name, color: detail.color, folderId: detail.folder_id ?? null,
      // Kept only when the handles came from the line itself; a line re-routed
      // between stored handles is rebuilt.
      baseLine: saved ? null : stored,
      baseHandles: saved ? [] : handleIndices(handles, stored),
      originalLine: saved ? null : stored,
    });
    return true;
  }, []);

  const editPlace = useCallback((place) => {
    clearMarkers();
    setLine(null); setError(null); setMenu(null);
    setDraft({
      ...CLOSED, open: true, kind: "place", id: place.id, handles: [[place.lng, place.lat]], snap: false,
      name: place.name, color: place.color, icon: place.icon || "marker", folderId: place.folder_id ?? null,
    });
  }, []);

  // ── Editing ────────────────────────────────────────────────────────────────
  const deleteHandle = useCallback((i) => {
    setMenu(null);
    update((d) => ({ dirty: true, handles: d.handles.filter((_, k) => k !== i) }));
  }, [update]);

  const undo = useCallback(() => { setMenu(null); update((d) => ({ dirty: true, handles: d.handles.slice(0, -1) })); }, [update]);

  /** 0 is the line as recorded, 100 the coarsest; previewed at once, written on Save. */
  const setSimplify = useCallback((pct) => {
    update((d) => {
      if (!d.originalLine) return {};
      const reduced = pct <= 0 ? d.originalLine : reduceLine(d.originalLine, (SIMPLIFY_MAX_M * pct) / 100);
      const handles = handlesFromLine(reduced);
      return { simplify: pct, baseLine: reduced, baseHandles: handleIndices(handles, reduced), handles, dirty: true };
    });
  }, [update]);

  /** Show where along the line the pointer is on the profile; null hides it. */
  const scrub = useCallback((point) => {
    try {
      map?.getSource(CURSOR_SRC)?.setData(point
        ? { type: "Feature", properties: {}, geometry: { type: "Point", coordinates: point } }
        : EMPTY);
    } catch { /* not ready */ }
  }, [map]);

  const canSave = !saving && (
    draft.kind === "place" ? draft.handles.length === 1
      : draft.kind === "track" ? draft.handles.length >= 2 && !!line
        : draft.handles.length === 1 || (draft.handles.length >= 2 && !!line));

  const save = useCallback(async () => {
    const d = draftRef.current;
    if (!canSave) return;
    setSaving(true);
    setError(null);
    const folder = (body) => (d.folderId != null ? { ...body, folder_id: d.folderId } : body);
    try {
      let saved;
      if (d.kind === "place" || (d.kind === "new" && d.handles.length === 1)) {
        const [lng, lat] = d.handles[0];
        const color = d.color || DEFAULT_PLACE_COLOR;
        const body = { name: d.name.trim() || "Place", color, icon: d.icon || "marker",
          // As with a track's line: an unmoved place keeps its position, so
          // a rename does not count as a move.
          ...(d.kind !== "place" || d.dirty ? { lat, lng } : {}) };
        if (d.kind === "place") {
          saved = await api.updateWaypoint(d.id, d.folderId != null ? { ...body, folder_id: d.folderId } : { ...body, clear_folder: true });
        } else {
          saved = await api.createWaypoint(folder(body));
        }
        onSaved?.({ kind: "place", id: saved.id });
      } else {
        const coords = line;
        if (d.kind === "track") {
          const body = { name: d.name.trim() || "Track", color: d.color, ...(d.dirty ? { coords } : {}) };
          saved = await api.updateCourse(d.id, d.folderId != null ? { ...body, folder_id: d.folderId } : { ...body, clear_folder: true });
        } else {
          saved = await api.createCourse(folder({
            name: d.name.trim() || "Track", color: d.color, coords,
            source: d.activityId != null ? "activity" : "builder",
            ...(d.activityId != null ? { activity_id: d.activityId } : {}),
          }));
        }
        if (d.kind !== "track" || d.dirty) saveHandles(saved.id, d.handles, d.snap);
        onSaved?.({ kind: "track", id: saved.id });
      }
      close();
    } catch (e) {
      setError(e?.message || "Could not save");
      setSaving(false);
    }
  }, [canSave, line, onSaved, close]);

  return {
    ...draft, isPlace, line, stats, routing, error, saving, canSave, menu,
    setMenu, deleteHandle, undo, setSimplify, scrub,
    setName: (name) => update({ name }),
    setColor: (color) => update({ color }),
    setIcon: (icon) => update({ icon }),
    setFolder: (folderId) => update({ folderId }),
    setSnap: (snap) => update({ snap, dirty: true }),
    startNew, startFromLine, editTrack, editPlace, save, close,
    handleAt: (i) => draft.handles[i],
  };
}
