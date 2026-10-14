// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useState, useEffect, useRef, useCallback } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { api } from "../../api/client";
import { useMapInit } from "./hooks/useMapInit";
import { useWheelZoom } from "./hooks/useWheelZoom";
import { useMapSettings } from "./hooks/useMapSettings";
import { useDownloadStatus } from "./hooks/useDownloadStatus";
import { useLayerToggles } from "./hooks/useLayerToggles";
import { useRegionDownload } from "./hooks/useRegionDownload";
import { usePoiSearch } from "./hooks/usePoiSearch";
import { usePoiFeatures } from "./hooks/usePoiFeatures";
import { usePublicLandLabelRank } from "./hooks/usePublicLandLabelRank";
import { useTilePrefetch } from "./hooks/useTilePrefetch";
import { useGridOverlay } from "./hooks/useGridOverlay";
import { use3D } from "./hooks/use3D";
import { usePointInfo } from "./hooks/usePointInfo";
import { useRouteHover } from "./hooks/useRouteHover";
import { useActivityTracks } from "./hooks/useActivityTracks";
import { useMapItems } from "./hooks/useMapItems";
import { useItemEditor } from "./hooks/useItemEditor";
import { useMapCursor } from "./hooks/useMapCursor";
import { usePanelStack } from "./hooks/usePanelStack";
import { useCourseSelection } from "./hooks/useCourseSelection";
import { useActivitySelect } from "./hooks/useActivitySelect";
import { useMergeSelect } from "./hooks/useMergeSelect";
import { useWildfires } from "./hooks/useWildfires";
import LayerPanel from "./components/LayerPanel";
import WildfirePanel from "./components/WildfirePanel";
import ControlPopup from "./components/ControlPopup";
import PointInfoPanel from "./components/PointInfoPanel";
import RouteDetailPanel from "./components/RouteDetailPanel";
import RegionDownloader from "./components/RegionDownloader";
import TrackLibrary from "./components/TrackLibrary";
import ItemEditor from "./components/ItemEditor";
import ItemDetail from "./components/ItemDetail";
import ActivityDetailPanel from "./components/ActivityDetailPanel";
import MergePanel from "./components/MergePanel";
import MapToolbar from "./components/MapToolbar";
import ProgressRing from "./components/ProgressRing";
import MapLegend from "./components/MapLegend";

/**
 * MapView — the main interactive map page.
 *
 * This is intentionally a large, cohesive component. It owns an imperative
 * MapLibre map instance (via useMapInit) and orchestrates a dozen sibling hooks
 * that each attach their own sources/layers/handlers to that map. The bulk of
 * the wiring that STAYS in this file is the cross-cutting glue between those
 * hooks — selection state, the merge working-set, and which bottom panel is
 * open — because those concerns share state and must coordinate. Splitting them
 * further would mean threading 5-6 pieces of state in and out of a hook, which
 * hurts readability rather than helping, so they are kept here and commented.
 *
 * Self-contained concerns HAVE been pulled into hooks with tiny interfaces:
 *   • useMapSettings()        — one-shot settings fetch (map_enabled + units).
 *   • useDownloadStatus(regs) — global-download polling + toast status buckets.
 * plus the pre-existing per-feature hooks (useMapInit, useLayerToggles,
 * useRegionDownload, usePointInfo, useMapItems, useItemEditor, …).
 *
 * WIRING ORDER (important — do not reorder):
 *   1. Map init + settings/toggles (state that everything downstream reads).
 *   2. Feature hooks that add sources/layers/handlers to the map.
 *   3. Selection glue (course / activity / merge) — depends on the toggles and
 *      on each other's "is anything else selected?" state, so they live inline.
 *   4. Effects that paint feature-state highlights and re-apply them after a
 *      style reload (MapLibre resets feature-state + geojson on styledata).
 *   5. The render tree (panels/toolbar/toast), gated on settings + ready.
 */
export default function MapView() {
  const containerRef = useRef(null);
  const gridCanvasRef = useRef(null);
  // Opened from a race plan ("Draw on map"): the next saved track becomes
  // that race's course, and the user is taken back to the plan.
  const [searchParams] = useSearchParams();
  const raceGoal = searchParams.get("raceGoal");
  const navigate = useNavigate();
  const [hideBanner, setHideBanner] = useState(false);
  const [routeDetail, setRouteDetail] = useState(null);   // {routeId, sectionId} | null
  const [selected, setSelected] = useState(null);         // {kind: "track"|"place", id} | null
  const selectedCourseId = selected?.kind === "track" ? selected.id : null;
  const [selectedActivity, setSelectedActivity] = useState(null);  // {id, name, dist} | null
  const [libraryOpen, setLibraryOpen] = useState(false);
  const [libraryKey, setLibraryKey] = useState(null);     // the row to open, "track-<id>"
  const [hasDevice, setHasDevice] = useState(true);
  // The library's width while it is open: the panels along the bottom of the
  // map stop short of it rather than running underneath (they overlapped).
  const [libraryWidth, setLibraryWidth] = useState(0);
  const rightInset = libraryOpen && libraryWidth ? libraryWidth + 12 + 8 : undefined;
  const [bottomPanel, setBottomPanel] = useState(null);   // null | "legend" | "layers" | "areas" | "merge"
  const [mergeSources, setMergeSources] = useState([]);   // ordered [{key,kind,id,name,dist,reverse}]
  const [merging, setMerging] = useState(false);          // merge request in flight
  const [mergeError, setMergeError] = useState(null);
  const mergeMode = bottomPanel === "merge";
  const { map, ready, error } = useMapInit(containerRef);
  // Direct wheel/trackpad zoom replacing the built-in handler's detection
  // delay + easing tail. Attached to the bare map (not gated on `ready`) —
  // zooming shouldn't wait for the style to finish loading.
  useWheelZoom(map);
  const { settings, settingsLoaded } = useMapSettings();

  const { groups, toggles, setVisibility, applyInitial } = useLayerToggles(map);
  const region = useRegionDownload(map);
  // Tracks, places and collections (data + map sources), and the editor that
  // draws and edits them.
  const items = useMapItems(map, ready);
  const showCursor = useMapCursor(map, ready);
  // After a save: show what was saved — selected on the map, and its row open
  // in the library when that is up. A track drawn from a race plan becomes
  // that race's course, and the user goes back to the plan.
  const onSaved = useCallback(async ({ kind, id }) => {
    await items.refresh();
    setSelected({ kind, id });
    setLibraryKey(`${kind}-${id}`);
    if (raceGoal && kind === "track") {
      await api.courseFromTrack(raceGoal, id);
      navigate(`/race-plans/${raceGoal}`);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [items.refresh, raceGoal, navigate]);
  const editor = useItemEditor(map, { atlas: items.atlas, onSaved });
  // The item open in the editor is drawn by the editor, not the map's layers.
  useEffect(() => {
    items.setEditing(editor.open ? {
      track: editor.kind === "track" ? editor.id : null,
      place: editor.kind === "place" ? editor.id : null,
    } : null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [editor.open, editor.kind, editor.id, items.setEditing]);
  // Typing a search puts the place-details panel away: it sits right under the
  // search box and covered the results.
  const pointCloseRef = useRef(null);
  const search = usePoiSearch(map, ready, { onTyping: () => pointCloseRef.current?.() });
  usePoiFeatures(map, ready);
  // Grid-ranks the public-land area names (grant-once, pan-stable) — the layer
  // starts filtered to nothing and this hook owns its filter.
  usePublicLandLabelRank(map, ready);
  // Speculatively warms the HTTP cache for tiles just outside the viewport
  // while the camera sits idle, so the next pan/zoom resolves from disk cache.
  useTilePrefetch(map, ready);
  // 3D camera/terrain state, lifted here so the toolbar, the grid overlay, and the
  // layer panel all share one is3D truth (the grid is force-hidden in 3D).
  const { is3D, toggle: toggle3D, orbitTo } = use3D(map, ready);
  useGridOverlay(map, ready, toggles.grid && !is3D, gridCanvasRef);
  // Click-anywhere info panel — suppressed while building a route or drawing a region.
  // Clicking a long-trail segment also auto-opens its elevation/section detail panel.
  // Point-info is suppressed while merging UNLESS no track is selected yet — then an
  // empty-map click should dismiss the merge panel and reveal the location details.
  const pointInfo = usePointInfo(map, ready,
    !editor.open && !region.drawing && (!mergeMode || mergeSources.length === 0),
    useCallback((routeId, sectionId) => { setRouteDetail({ routeId, sectionId }); setSelected(null); }, []));
  pointCloseRef.current = pointInfo.close;
  // Light up long-trail segments on hover (pointer cursor) to signal clickability.
  useRouteHover(map, ready);

  // Live wildfire + smoke overlays. Fetches only when the account-level opt-in
  // (settings.wildfire_enabled) AND the combined layer toggle are both on.
  const { selectedFire, closeFire } = useWildfires(
    map, ready, settings?.wildfire_enabled ?? false, toggles.wildfires);
  // The fire card and the point-info card share the top-left slot — opening
  // either dismisses the other (usePointInfo already skips clicks ON a fire).
  useEffect(() => { if (selectedFire) pointInfo.close(); },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [selectedFire]);
  useEffect(() => { if (pointInfo.point) closeFire(); },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [pointInfo.point]);

  // Past-activity tracks layer (theme-coloured lines; toggle in the layer panel).
  const { setHoveredActivity } = useActivityTracks(map, ready, toggles.activity_tracks);

  // Clicking a saved track or place selects it → the detail panel.
  const selectItem = useCallback((sel) => {
    setSelected(sel);
    if (sel != null) { setSelectedActivity(null); setRouteDetail(null); pointInfo.close(); }
  }, [pointInfo]);
  useCourseSelection(
    map, ready, !editor.open && !region.drawing && !mergeMode && toggles.custom_tracks,
    selected, selectItem,
  );
  // Opening the editor puts away what it would sit on top of.
  useEffect(() => {
    if (editor.open) { setSelected(null); setSelectedActivity(null); pointInfo.close(); }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [editor.open]);

  // Which watch controls to offer: none without a watch to send to. Shown if
  // the answer is unknown, rather than hiding a working feature.
  useEffect(() => {
    api.getGarminDevices().then((list) => setHasDevice(Array.isArray(list) ? list.length > 0 : true)).catch(() => {});
  }, []);

  // Single-click selection for recorded activities → the activity detail panel.
  const selectActivity = useCallback((meta) => {
    if (!meta) { setSelectedActivity(null); return; }
    setSelectedActivity(meta);
    setSelected(null); setRouteDetail(null); pointInfo.close();
  }, [pointInfo]);
  useActivitySelect(
    map, ready, !editor.open && !region.drawing && !mergeMode && toggles.activity_tracks,
    selectActivity,
  );

  // Amber highlight for the selected activity (feature-state on activity_tracks).
  const prevActSel = useRef(null);
  useEffect(() => {
    if (!map || !ready) return undefined;
    const apply = () => {
      const id = selectedActivity?.id ?? null;
      if (prevActSel.current != null && prevActSel.current !== id) {
        try { map.setFeatureState({ source: "activity_tracks", id: prevActSel.current }, { selected: false }); } catch { /* gone */ }
      }
      if (id != null) { try { map.setFeatureState({ source: "activity_tracks", id }, { selected: true }); } catch { /* not ready */ } }
      prevActSel.current = id;
    };
    apply();
    map.on("styledata", apply);   // re-apply after a style reload resets feature-state
    return () => map.off("styledata", apply);
  }, [selectedActivity, map, ready]);

  const flyToTrack = useCallback((t) => {
    if (map && t?.bounds) {
      map.fitBounds([[t.bounds[0], t.bounds[1]], [t.bounds[2], t.bounds[3]]],
        { padding: 80, maxZoom: 15, duration: 600 });
    }
  }, [map]);

  // Feed the activity_preview source with an arbitrary line — used by the track
  // simplify slider to overlay the reduced geometry (dashed accent) on the original.
  const previewGeometry = useCallback((coords) => {
    try {
      map?.getSource("activity_preview")?.setData(
        coords && coords.length
          ? { type: "Feature", properties: {}, geometry: { type: "LineString", coordinates: coords } }
          : { type: "FeatureCollection", features: [] });
    } catch { /* source not ready */ }
  }, [map]);

  const flyToPlace = useCallback((p) => {
    if (map && p) map.easeTo({ center: [p.lng, p.lat], zoom: Math.max(map.getZoom(), 14), duration: 600 });
  }, [map]);

  const createFolder = useCallback(async (name) => {
    const f = await api.createCourseFolder({ name });
    await items.refresh();
    return f;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [items.refresh]);

  // Tracks joined, in the order given, each turned round if that starts it
  // nearer where the last one ended — then opened in the editor as a new
  // track, to be named and refined before it is saved. The originals stay.
  const combineTracks = useCallback(async (list) => {
    const joined = [];
    for (const t of list) {
      let line;
      try { line = (await api.getCourse(t.id)).geometry || []; } catch { continue; }
      line = line.filter((c) => c && c[0] != null && c[1] != null);
      if (line.length < 2) continue;
      const end = joined[joined.length - 1];
      if (end) {
        const gap = (p) => (p[0] - end[0]) ** 2 + (p[1] - end[1]) ** 2;
        if (gap(line[line.length - 1]) < gap(line[0])) line = [...line].reverse();
      }
      joined.push(...line);
    }
    editor.startFromLine(joined, {
      name: list.map((t) => t.name).join(" + ").slice(0, 80),
      color: list[0]?.color,
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [editor.startFromLine]);

  // ── Merge: ctrl/merge-mode click multi-select on the map ──────────────────
  // Toggle a clicked track/activity in the ordered merge set. When the set is
  // empty, seed it from whatever is currently single-selected — so "click one
  // (overview opens), then ctrl-click another" merges both. A modifier add also
  // opens the merge panel and drops the single selection.
  const mkSource = useCallback((kind, id, name, dist) => {
    const fromList = kind === "track" ? items.tracks.find((t) => t.id === id) : null;
    return {
      key: `${kind}-${id}`, kind, id,
      name: name || fromList?.name || (kind === "track" ? "Track" : "Activity"),
      dist: dist ?? fromList?.distance_m ?? null, reverse: false,
    };
  }, [items.tracks]);

  const toggleMergeSource = useCallback((hit) => {
    setMergeError(null);
    const key = `${hit.kind}-${hit.id}`;
    setMergeSources((cur) => {
      let base = cur;
      if (cur.length === 0) {
        if (selectedCourseId != null) base = [mkSource("track", selectedCourseId)];
        else if (selectedActivity?.id != null) base = [mkSource("activity", selectedActivity.id, selectedActivity.name, selectedActivity.dist)];
      }
      if (base.some((s) => s.key === key)) return base.filter((s) => s.key !== key);
      return [...base, mkSource(hit.kind, hit.id, hit.name, hit.dist)];
    });
    if (hit.modifier) { setBottomPanel("merge"); setSelected(null); setSelectedActivity(null); }
  }, [mkSource, selectedCourseId, selectedActivity]);

  // In merge mode, an empty-map click with nothing selected yet dismisses the panel
  // (point-info is enabled in that state, so the location popup appears instead).
  const onMergeEmptyClick = useCallback(() => {
    if (mergeMode && mergeSources.length === 0) setBottomPanel(null);
  }, [mergeMode, mergeSources.length]);
  useMergeSelect(map, ready, mergeMode, toggleMergeSource, onMergeEmptyClick);

  // Paint the amber `merge` highlight on the selected features; clear removed ones.
  const prevMergeRef = useRef([]);
  useEffect(() => {
    if (!map || !ready) return;
    const src = (s) => (s.kind === "track" ? "custom_tracks" : "activity_tracks");
    const set = (s, on) => { try { map.setFeatureState({ source: src(s), id: s.id }, { merge: on }); } catch { /* gone */ } };
    const live = new Set(mergeSources.map((s) => s.key));
    for (const s of prevMergeRef.current) if (!live.has(s.key)) set(s, false);
    for (const s of mergeSources) set(s, true);
    prevMergeRef.current = mergeSources;
  }, [mergeSources, map, ready]);

  // Re-apply highlight after a style reload (which resets feature-state + geojson).
  useEffect(() => {
    if (!map || !ready) return;
    const reapply = () => {
      for (const s of mergeSources) {
        try { map.setFeatureState({ source: s.kind === "track" ? "custom_tracks" : "activity_tracks", id: s.id }, { merge: true }); } catch { /* not ready */ }
      }
    };
    map.on("styledata", reapply);
    return () => map.off("styledata", reapply);
  }, [map, ready, mergeSources]);

  // Closing the merge panel clears the working set (and its highlight via the effect).
  useEffect(() => { if (bottomPanel !== "merge") { setMergeSources([]); setMergeError(null); } }, [bottomPanel]);

  const doMerge = useCallback(async (payload) => {
    setMerging(true); setMergeError(null);
    try {
      const { track } = await api.mergeCourses(payload);
      await items.refresh();
      setBottomPanel(null);          // closes panel → clears the set via the effect
      setSelected({ kind: "track", id: track.id });
      flyToTrack(track);
    } catch (err) {
      setMergeError(err.message || "Merge failed");
    } finally { setMerging(false); }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [items.refresh, flyToTrack]);

  // Open an activity by id (from a deep-link, the manager picker, or point-info):
  // fit the map to it and select it (→ the activity detail panel + amber highlight).
  const openActivityById = useCallback(async (id) => {
    selectActivity({ id });
    try {
      const track = await api.getTrack(id);
      const coords = (track || []).filter((p) => p.lat != null && p.lng != null);
      if (coords.length >= 2) {
        let minx = 180, miny = 90, maxx = -180, maxy = -90;
        for (const c of coords) {
          if (c.lng < minx) minx = c.lng; if (c.lng > maxx) maxx = c.lng;
          if (c.lat < miny) miny = c.lat; if (c.lat > maxy) maxy = c.lat;
        }
        map?.fitBounds([[minx, miny], [maxx, maxy]], { padding: 90, maxZoom: 15, duration: 600 });
      }
    } catch { /* ignore */ }
  }, [map, selectActivity]);

  // "Make track" (from the activity detail panel): a new track along the
  // activity, opened in the editor — named after it, linked to it, saved only
  // when the person saves.
  const [creatingFromActivity, setCreatingFromActivity] = useState(false);
  const handleCreateFromActivity = useCallback(async (activityId) => {
    setCreatingFromActivity(true);
    try {
      const [points, act] = await Promise.all([
        api.getTrack(activityId),
        api.getActivity(activityId).catch(() => null),
      ]);
      const line = (points || []).filter((p) => p.lat != null && p.lng != null)
        .map((p) => (p.altitude != null ? [p.lng, p.lat, p.altitude] : [p.lng, p.lat]));
      editor.startFromLine(line, {
        name: act?.name || selectedActivity?.name || "Track",
        activityId,
      });
    } finally { setCreatingFromActivity(false); }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [editor.startFromLine, selectedActivity]);

  // A right-click is the desktop's long-press: it starts a drawing there —
  // one point is a place, a second makes it a track — and puts away the
  // panels it would otherwise open under.
  useEffect(() => {
    if (!map || !ready) return undefined;
    const onContext = (e) => {
      if (editor.open || region.drawing) return;
      e.preventDefault?.();
      pointInfo.close();
      setRouteDetail(null);
      editor.startNew([e.lngLat.lng, e.lngLat.lat]);
    };
    map.on("contextmenu", onContext);
    return () => map.off("contextmenu", onContext);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [map, ready, editor.open, editor.startNew, region.drawing]);

  // Deep link from the Activities page: /maps?previewActivity=<id>.
  const deepLinkedRef = useRef(false);
  useEffect(() => {
    if (!ready || !map || deepLinkedRef.current) return;
    const id = new URLSearchParams(window.location.search).get("previewActivity");
    if (id) { deepLinkedRef.current = true; openActivityById(Number(id)); }
  }, [ready, map, openActivityById]);

  useEffect(() => {
    if (ready && map) applyInitial(map);
  }, [ready, map, applyInitial]);

  // Global-download polling + toast status buckets (region status folded in).
  const { regionDownloading, regionMerging, globalActive, globalErrored, showToast } =
    useDownloadStatus(region.regions);

  // Esc puts away the panel last used, then the one before it. The editor
  // is not among them: Esc there would throw away a drawing.
  const touchPanel = usePanelStack({
    point: { open: !!pointInfo.point, close: pointInfo.close },
    fire: { open: !!selectedFire, close: closeFire },
    route: { open: !!routeDetail, close: () => setRouteDetail(null) },
    item: { open: selected != null && !editor.open, close: () => setSelected(null) },
    activity: { open: selectedActivity != null, close: () => setSelectedActivity(null) },
    library: { open: libraryOpen, close: () => setLibraryOpen(false) },
    popup: { open: bottomPanel != null, close: () => setBottomPanel(null) },
  });
  // On a panel's wrapper, so a click inside it makes it the most recent. The
  // wrapper is `contents`: it has no box, so the panel positions as before.
  const grab = (id) => ({ className: "contents", onPointerDownCapture: () => touchPanel(id) });

  if (settingsLoaded && !settings?.map_enabled) {
    return (
      <div className="h-full flex flex-col items-center justify-center bg-[#f2efe9] gap-4 p-7 text-center">
        <svg className="w-12 h-12 text-slate-300" fill="none" stroke="currentColor" strokeWidth={1.5} viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" d="M9 6.75V15m6-6v8.25m.503 3.498 4.875-2.437c.381-.19.622-.58.622-1.006V4.82c0-.836-.88-1.38-1.628-1.006l-3.869 1.934c-.317.159-.69.159-1.006 0L9.503 3.252a1.125 1.125 0 0 0-1.006 0L3.622 5.689C3.24 5.88 3 6.27 3 6.695V19.18c0 .836.88 1.38 1.628 1.006l3.869-1.934c-.317-.159.69-.159 1.006 0l4.994 2.497c.317.158.69.158 1.006 0Z" />
        </svg>
        <h2 className="text-lg font-semibold text-slate-600">Map tiles are not enabled</h2>
        <p className="text-sm text-slate-400 max-w-sm leading-relaxed">
          Enable map tiles in Settings to download the global basemap
          (<span className="text-amber-500">~3.5 GB</span>).
        </p>
        <Link
          to="/settings"
          className="inline-flex items-center gap-1.5 px-3.5 py-1.5 rounded-lg bg-accent-500 text-white text-sm font-medium hover:bg-accent-600 transition-colors"
        >
          Open Settings
          <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" d="M13.5 4.5 21 12m0 0-7.5 7.5M21 12H3" />
          </svg>
        </Link>
      </div>
    );
  }

  const imperial = settings?.units === "imperial";

  return (
    <div className="relative h-full">
      <div ref={containerRef} className="w-full h-full" />
      {/* Grid/graticule HUD — painted over the map by useGridOverlay. Sits above
          the map canvas but below the controls, and never eats pointer events. */}
      <canvas ref={gridCanvasRef} className="absolute inset-0 w-full h-full pointer-events-none z-[5]" />

      <div {...grab("point")}>
      <PointInfoPanel
        point={pointInfo.point} trails={pointInfo.trails} pois={pointInfo.pois}
        activities={pointInfo.activities}
        land={pointInfo.land} info={pointInfo.info} loading={pointInfo.loading}
        imperial={imperial}
        onClose={pointInfo.close}
        onSelectRoute={(routeId, sectionId) => setRouteDetail({ routeId, sectionId })}
        onSelectActivity={(id) => { setHoveredActivity(null); pointInfo.close(); openActivityById(id); }}
        onHoverActivity={setHoveredActivity}
        onSavePlace={(pt, name) => { pointInfo.close(); editor.startNew([pt.lng, pt.lat], { name }); }}
      />
      </div>

      {/* How many of a searched category are marked — and when the view holds
          more than are drawn, that zooming in shows the rest. */}
      {search.marked && (
        <div className="absolute top-[0.9rem] left-[39rem] z-10 px-2.5 py-1 rounded-full bg-white/95 dark:bg-slate-900/95 shadow border border-slate-200 dark:border-slate-700 text-[11px] text-slate-600 dark:text-slate-300">
          {search.marked.count === 0 ? `No ${search.marked.category} in view`
            : search.capped ? `${search.marked.count}+ ${search.marked.category} — zoom in for the rest`
              : `${search.marked.count} ${search.marked.category} in view`}
        </div>
      )}

      {/* Fire detail card (same slot as PointInfoPanel; usePointInfo skips fire clicks). */}
      {selectedFire && <div {...grab("fire")}><WildfirePanel fire={selectedFire} onClose={closeFire} /></div>}

      <div {...grab("route")}>
      {routeDetail && (
        <RouteDetailPanel
          routeId={routeDetail.routeId} sectionId={routeDetail.sectionId}
          imperial={imperial}
          onClose={() => setRouteDetail(null)} rightInset={rightInset}
        />
      )}
      </div>

      {!libraryOpen && (
        <div data-tour="map-tracks" className="absolute top-3 right-3 z-10 flex flex-col gap-1.5 items-stretch min-w-[180px]">
          {/* Chevron so it reads as a button, not a header. Its hover is a
              solid shade, like Draw's below it: a translucent tint let the
              map show through the button under the pointer. */}
          <button
            onClick={() => setLibraryOpen(true)}
            className="flex items-center justify-between gap-2 px-2.5 py-2 text-xs font-semibold rounded-xl shadow-lg border border-slate-200 dark:border-slate-700 text-accent-700 dark:text-accent-300 bg-white dark:bg-slate-900 hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
          >
            <span className="flex items-center gap-2">
              <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" d="M9 6.75V15m6-6v8.25m.503 3.498 4.875-2.437c.381-.19.622-.58.622-1.006V4.82c0-.836-.88-1.38-1.628-1.006l-3.869 1.934c-.317.159-.69.159-1.006 0L9.503 3.252a1.125 1.125 0 0 0-1.006 0L3.622 5.689C3.24 5.88 3 6.27 3 6.695V19.18c0 .836.88 1.38 1.628 1.006l3.869-1.934c.317-.159.69-.159 1.006 0l4.994 2.497c.317.158.69.158 1.006 0Z" />
              </svg>
              My Tracks
            </span>
            <span className="flex items-center gap-1.5">
              {items.tracks.length + items.places.length > 0 && (
                <span className="badge bg-accent-100 dark:bg-accent-900/40 text-accent-700 dark:text-accent-300">{items.tracks.length + items.places.length}</span>
              )}
              <svg className="w-3.5 h-3.5 opacity-70" fill="none" stroke="currentColor" strokeWidth={2.5} viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" d="M9 5l7 7-7 7" />
              </svg>
            </span>
          </button>
          {!editor.open && (
            <button onClick={() => editor.startNew()} title="Click the map to draw a track or drop a place (or right-click anywhere)"
                    className="btn btn-primary btn-sm shadow-lg">
              Draw
            </button>
          )}
        </div>
      )}

      {/* Bottom-left control COLUMN: Legend / Layers / Areas / Merge popups + MapToolbar.
          A vertical stack (mirrors the right-side zoom column). Icon-only; the label
          animate-expands on hover — but only when no popup is open, so an expanded
          label never overlaps the popup that sits just to the column's right. */}
      <MapToolbar map={map} mapReady={ready} is3D={is3D} onToggle3D={toggle3D} orbitTo={orbitTo} />
      <div data-tour="map-tools" className="absolute bottom-4 left-3 z-20 flex flex-col items-start gap-2">
        {[
          ["legend", "Legend", "M3.75 5.25h16.5M3.75 9.75h16.5M3.75 14.25h16.5M3.75 18.75h16.5"],
          ["layers", "Layers", "m2.25 12 8.954 4.477a1.5 1.5 0 0 0 1.342 0L21.75 12M2.25 7.5l8.954-4.477a1.5 1.5 0 0 1 1.342 0L21.75 7.5l-9.204 4.602a1.5 1.5 0 0 1-1.342 0L2.25 7.5Z"],
          ["areas", "Areas", "M9 6.75V15m6-6v8.25m.503 3.498 4.875-2.437c.381-.19.622-.58.622-1.006V4.82c0-.836-.88-1.38-1.628-1.006l-3.869 1.934c-.317.159-.69.159-1.006 0L9.503 3.252a1.125 1.125 0 0 0-1.006 0L3.622 5.689C3.24 5.88 3 6.27 3 6.695V19.18c0 .836.88 1.38 1.628 1.006l3.869-1.934c.317-.159.69-.159 1.006 0l4.994 2.497c.317.158.69.158 1.006 0Z"],
          ["merge", "Merge", "M7.5 21 3 16.5m0 0L7.5 12M3 16.5h13.5m0-13.5L21 7.5m0 0L16.5 12M21 7.5H7.5"],
        ].map(([id, label, d]) => {
          const active = bottomPanel === id;
          const allowExpand = bottomPanel == null;   // no popup open → safe to expand
          return (
            <button
              key={id}
              onClick={() => setBottomPanel((p) => (p === id ? null : id))}
              title={label}
              className={`group flex items-center px-2 py-1.5 rounded-xl shadow-lg border text-sm font-medium transition-colors
                ${active
                  ? "bg-accent-500 text-white border-accent-500"
                  : "bg-white dark:bg-slate-900 text-slate-700 dark:text-slate-200 border-slate-200 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-800"}`}
            >
              <svg className="w-4 h-4 shrink-0" fill="none" stroke="currentColor" strokeWidth={1.8} viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" d={d} />
              </svg>
              <span className={`overflow-hidden whitespace-nowrap transition-all duration-200 max-w-0 opacity-0 ${
                allowExpand ? "group-hover:max-w-[6rem] group-hover:ml-1.5 group-hover:opacity-100" : ""}`}>
                {label}
              </span>
            </button>
          );
        })}
      </div>

      <div {...grab("popup")}>
      {bottomPanel === "merge" && (
        <MergePanel
          sources={mergeSources}
          imperial={imperial}
          busy={merging} error={mergeError}
          onReorder={setMergeSources}
          onReverse={(key) => setMergeSources((cur) => cur.map((s) => s.key === key ? { ...s, reverse: !s.reverse } : s))}
          onRemove={(key) => setMergeSources((cur) => cur.filter((s) => s.key !== key))}
          onClear={() => setMergeSources([])}
          onMerge={doMerge}
          onClose={() => setBottomPanel(null)}
        />
      )}

      {bottomPanel === "legend" && <MapLegend onClose={() => setBottomPanel(null)} />}
      {bottomPanel === "layers" && (
        <ControlPopup title="Layers" onClose={() => setBottomPanel(null)} width="w-[230px]">
          <LayerPanel groups={groups} toggles={toggles} onToggle={setVisibility} is3D={is3D}
            wildfireEnabled={settings?.wildfire_enabled ?? false} />
        </ControlPopup>
      )}
      {bottomPanel === "areas" && (
        <ControlPopup title="High-Resolution Areas" onClose={() => setBottomPanel(null)} width="w-[256px]">
          <RegionDownloader
            drawing={region.drawing} bbox={region.bbox} sizeEstimate={region.sizeEstimate} estimateError={region.estimateError}
            regions={region.regions} pending={region.pending}
            onStart={region.startDrawing} onCancel={region.cancelDrawing}
            onDownload={region.downloadRegion} onDelete={region.deleteRegion}
            onHighlight={region.highlightRegion} onZoom={region.zoomToRegion}
          />
        </ControlPopup>
      )}
      </div>

      {/* The editor, and the detail of a selected track or place, share the
          bottom of the map: one is open at a time. */}
      <ItemEditor editor={editor} folders={items.folders} atlas={items.atlas}
                  imperial={imperial} onCreateFolder={createFolder} rightInset={rightInset} />
      {selected != null && !editor.open && (
        <div {...grab("item")}>
      <ItemDetail
          kind={selected.kind} id={selected.id} places={items.places}
          atlas={items.atlas} imperial={imperial} hasDevice={hasDevice}
          onChanged={items.refresh} onClose={() => setSelected(null)}
          onEdit={(it) => (selected.kind === "track" ? editor.editTrack(it) : editor.editPlace(it))}
          onScrub={showCursor} rightInset={rightInset}
        />
      </div>
      )}

      {/* Activity detail overlay (a recorded activity selected on the map). */}
      {selectedActivity != null && (
        <div {...grab("activity")}>
      <ActivityDetailPanel
          activityId={selectedActivity.id}
          imperial={imperial}
          creating={creatingFromActivity}
          onCreateTrack={handleCreateFromActivity} rightInset={rightInset}
          onClose={() => setSelectedActivity(null)}
        />
      </div>
      )}

      {!ready && !error && (
        <div className="absolute inset-0 flex flex-col items-center justify-center bg-[#f2efe9] gap-3 z-20">
          <div className="spinner w-8 h-8" />
          <p className="text-sm text-slate-500">Loading map…</p>
        </div>
      )}

      {/* The area drawn was already covered by one we have — say which. */}
      {region.alreadyHave && (
        <div className="absolute top-28 left-1/2 -translate-x-1/2 z-20 bg-white dark:bg-slate-900 rounded-xl shadow-lg border border-sky-200 dark:border-sky-800 px-3.5 py-2.5 flex items-start gap-3 max-w-sm w-[calc(100%-2rem)]">
          <div className="min-w-0 w-full">
            <p className="text-sm font-medium text-slate-800 dark:text-slate-200">
              Already downloaded
            </p>
            <p className="text-xs text-slate-600 dark:text-slate-300">
              “{region.alreadyHave.name}” already covers that area, so there is
              nothing new to fetch.
            </p>
          </div>
          <button
            onClick={region.dismissAlreadyHave}
            className="btn btn-neutral btn-sm shrink-0"
          >
            Dismiss
          </button>
        </div>
      )}

      {/* Map-data progress as a pill at the top, as the phone shows it: a
          wheel and a percentage, out of the way of the editor and the panels
          that share the bottom of the map. Errors widen it into a card. */}
      {showToast && (
        // Under the search row, not in it: the search box is wide enough now
        // to run underneath a pill centred at the top.
        <div className="absolute top-[3.75rem] left-1/2 -translate-x-1/2 z-20 max-w-sm w-max">
          <div className={`bg-white/95 dark:bg-slate-900/95 backdrop-blur shadow-lg border border-slate-200 dark:border-slate-700 px-3 py-1.5 ${
            globalErrored.length ? "rounded-xl" : "rounded-full"}`}>
            {(globalActive.length > 0 || regionDownloading.length > 0 || regionMerging.length > 0) && (() => {
              const pct = globalActive.length
                ? Math.round(globalActive.reduce((n, d) => n + (d.progress || 0), 0) / globalActive.length)
                : null;
              return (
                <div className="flex items-center gap-2" title={globalActive.map((d) => `${d.label}: ${Math.round(d.progress || 0)}% · ${d.detail || d.size_hint || ""}`).join("\n")}>
                  {pct != null
                    ? <ProgressRing percent={pct} size={20} className="text-sky-500 shrink-0" />
                    : <div className="w-4 h-4 border-2 border-sky-500 border-t-transparent rounded-full animate-spin shrink-0" />}
                  <span className="text-xs font-medium text-slate-700 dark:text-slate-200 whitespace-nowrap">
                    {regionMerging.length && !globalActive.length ? "Merging map tiles…" : "Setting up map data"}
                    {pct != null && <span className="text-slate-400 tabular-nums"> · {pct}%</span>}
                  </span>
                </div>
              );
            })()}
            {globalErrored.map((d) => (
              <p key={d.id} className="text-xs text-red-500">{d.label}: {d.error}</p>
            ))}
          </div>
        </div>
      )}

      {error && !hideBanner && (
        <div className="absolute top-28 left-1/2 -translate-x-1/2 z-20 max-w-md w-[calc(100%-2rem)] bg-white dark:bg-slate-900 rounded-xl shadow-lg border border-amber-200 dark:border-amber-800 p-3.5">
          <div className="flex items-start gap-3">
            <svg className="w-5 h-5 shrink-0 text-amber-500 mt-0.5" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L4.082 16.5c-.77.833.192 2.5 1.732 2.5z" />
            </svg>
            <div className="min-w-0">
              <p className="text-sm font-medium text-slate-800 dark:text-slate-200">Map data not available</p>
              <p className="text-xs text-slate-500 dark:text-slate-400 mt-1 leading-relaxed">
                No map tiles are installed yet. Click <span className="font-medium">Download Area</span> above,
                draw a rectangle, and download your first region. The global overview downloads automatically.
              </p>
              <p className="text-[10px] text-slate-400 dark:text-slate-500 mt-1.5">{error}</p>
            </div>
            <button onClick={() => setHideBanner(true)} className="icon-btn shrink-0">
              <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>
        </div>
      )}

      <div {...grab("library")}>
      <TrackLibrary
        open={libraryOpen} onClose={() => setLibraryOpen(false)}
        tracks={items.tracks} places={items.places} folders={items.folders} atlas={items.atlas}
        imperial={imperial} hasDevice={hasDevice} openKey={libraryKey}
        onRefresh={items.refresh}
        onShowTrack={(t) => { selectItem({ kind: "track", id: t.id }); flyToTrack(t); }}
        onShowPlace={(p) => { selectItem({ kind: "place", id: p.id }); flyToPlace(p); }}
        onEditTrack={(t) => editor.editTrack(t)}
        onEditPlace={(p) => editor.editPlace(p)}
        onWidth={setLibraryWidth}
        onCombine={combineTracks}
        onCreateFolder={createFolder}
      />
      </div>
    </div>
  );
}
