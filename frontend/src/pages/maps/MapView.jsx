// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useState, useEffect, useRef, useCallback } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { api } from "../../api/client";
import { useTheme } from "../../context/ThemeContext";
import { useMapInit } from "./hooks/useMapInit";
import { useWheelZoom } from "./hooks/useWheelZoom";
import { useMapSettings } from "./hooks/useMapSettings";
import { useDownloadStatus } from "./hooks/useDownloadStatus";
import { useLayerToggles } from "./hooks/useLayerToggles";
import { useRegionDownload } from "./hooks/useRegionDownload";
import { useRouteBuilder } from "./hooks/useRouteBuilder";
import { usePoiSearch } from "./hooks/usePoiSearch";
import { usePoiFeatures } from "./hooks/usePoiFeatures";
import { usePublicLandLabelRank } from "./hooks/usePublicLandLabelRank";
import { useTilePrefetch } from "./hooks/useTilePrefetch";
import { useGridOverlay } from "./hooks/useGridOverlay";
import { use3D } from "./hooks/use3D";
import { usePointInfo } from "./hooks/usePointInfo";
import { useRouteHover } from "./hooks/useRouteHover";
import { useActivityTracks } from "./hooks/useActivityTracks";
import { useCustomTracks } from "./hooks/useCustomTracks";
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
import RouteBuilder from "./components/RouteBuilder";
import ElevationProfile from "./components/ElevationProfile";
import CustomTrackManager from "./components/CustomTrackManager";
import CustomTrackDetail from "./components/CustomTrackDetail";
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
 * useRegionDownload, useRouteBuilder, usePointInfo, useCustomTracks, …).
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
  const { accent } = useTheme();
  // Opened from a race plan ("Draw on map"): the next saved track becomes
  // that race's course, and the user is taken back to the plan.
  const [searchParams] = useSearchParams();
  const raceGoal = searchParams.get("raceGoal");
  const navigate = useNavigate();
  const [hideBanner, setHideBanner] = useState(false);
  const [routeDetail, setRouteDetail] = useState(null);   // {routeId, sectionId} | null
  const [selectedCourseId, setSelectedCourseId] = useState(null);
  const [selectedActivity, setSelectedActivity] = useState(null);  // {id, name, dist} | null
  const [trackManagerOpen, setTrackManagerOpen] = useState(false);
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
  const route = useRouteBuilder(map);
  usePoiSearch(map, ready);
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
    !route.building && !region.drawing && (!mergeMode || mergeSources.length === 0),
    useCallback((routeId, sectionId) => { setRouteDetail({ routeId, sectionId }); setSelectedCourseId(null); }, []));
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
  const { setHoveredActivity } = useActivityTracks(map, ready, toggles.activity_tracks, accent);

  // Custom tracks: data source for the map + manager list, and click selection.
  const customTracks = useCustomTracks(map, ready);
  const selectCourse = useCallback((id) => {
    setSelectedCourseId(id);
    if (id != null) { setSelectedActivity(null); setRouteDetail(null); pointInfo.close(); }
  }, [pointInfo]);
  useCourseSelection(
    map, ready, !route.building && !region.drawing && !mergeMode && toggles.custom_tracks,
    selectedCourseId, selectCourse,
  );

  // Single-click selection for recorded activities → the activity detail panel.
  const selectActivity = useCallback((meta) => {
    if (!meta) { setSelectedActivity(null); return; }
    setSelectedActivity(meta);
    setSelectedCourseId(null); setRouteDetail(null); pointInfo.close();
  }, [pointInfo]);
  useActivitySelect(
    map, ready, !route.building && !region.drawing && !mergeMode && toggles.activity_tracks,
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

  const handleSaveTrack = useCallback(async (meta) => {
    const created = await api.createCourse({
      name: meta.name, color: meta.color, sport: meta.sport,
      turn_by_turn: meta.turn_by_turn, source: meta.source, geojson: meta.geojson,
    });
    await customTracks.refresh();
    route.clearRoute();
    setSelectedCourseId(created.id);
    if (raceGoal) {
      await api.courseFromTrack(raceGoal, created.id);
      navigate(`/race-plans/${raceGoal}`);
    }
  }, [customTracks, route, raceGoal, navigate]);

  // ── Merge: ctrl/merge-mode click multi-select on the map ──────────────────
  // Toggle a clicked track/activity in the ordered merge set. When the set is
  // empty, seed it from whatever is currently single-selected — so "click one
  // (overview opens), then ctrl-click another" merges both. A modifier add also
  // opens the merge panel and drops the single selection.
  const mkSource = useCallback((kind, id, name, dist) => {
    const fromList = kind === "track" ? customTracks.tracks.find((t) => t.id === id) : null;
    return {
      key: `${kind}-${id}`, kind, id,
      name: name || fromList?.name || (kind === "track" ? "Track" : "Activity"),
      dist: dist ?? fromList?.distance_m ?? null, reverse: false,
    };
  }, [customTracks.tracks]);

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
    if (hit.modifier) { setBottomPanel("merge"); setSelectedCourseId(null); setSelectedActivity(null); }
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
      await customTracks.refresh();
      setBottomPanel(null);          // closes panel → clears the set via the effect
      setSelectedCourseId(track.id);
      flyToTrack(track);
    } catch (err) {
      setMergeError(err.message || "Merge failed");
    } finally { setMerging(false); }
  }, [customTracks, flyToTrack]);

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

  // "Create track from this activity" (from the activity detail panel).
  const [creatingFromActivity, setCreatingFromActivity] = useState(false);
  const handleCreateFromActivity = useCallback(async (activityId) => {
    setCreatingFromActivity(true);
    try {
      const created = await api.courseFromActivity(activityId);
      await customTracks.refresh();
      setSelectedActivity(null);
      setSelectedCourseId(created.id);
    } finally { setCreatingFromActivity(false); }
  }, [customTracks]);

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

  return (
    <div className="relative h-full">
      <div ref={containerRef} className="w-full h-full" />
      {/* Grid/graticule HUD — painted over the map by useGridOverlay. Sits above
          the map canvas but below the controls, and never eats pointer events. */}
      <canvas ref={gridCanvasRef} className="absolute inset-0 w-full h-full pointer-events-none z-[5]" />

      <PointInfoPanel
        point={pointInfo.point} trails={pointInfo.trails} pois={pointInfo.pois}
        activities={pointInfo.activities}
        land={pointInfo.land} info={pointInfo.info} loading={pointInfo.loading}
        imperial={settings?.units === "imperial"}
        onClose={pointInfo.close}
        onSelectRoute={(routeId, sectionId) => setRouteDetail({ routeId, sectionId })}
        onSelectActivity={(id) => { setHoveredActivity(null); pointInfo.close(); openActivityById(id); }}
        onHoverActivity={setHoveredActivity}
      />

      {/* Fire detail card (same slot as PointInfoPanel; usePointInfo skips fire clicks). */}
      {selectedFire && <WildfirePanel fire={selectedFire} onClose={closeFire} />}

      {routeDetail && (
        <RouteDetailPanel
          routeId={routeDetail.routeId} sectionId={routeDetail.sectionId}
          imperial={settings?.units === "imperial"}
          onClose={() => setRouteDetail(null)}
        />
      )}

      <div data-tour="map-tracks" className="absolute top-3 right-3 bg-white dark:bg-slate-900 rounded-xl shadow-lg border border-slate-200 dark:border-slate-700 z-10 min-w-[200px] max-w-[240px]">
        {/* Tinted + chevron so it clearly reads as a button, not a header. */}
        <button
          onClick={() => setTrackManagerOpen(true)}
          className="w-full flex items-center justify-between gap-2 px-2.5 py-2 text-xs font-semibold text-accent-700 dark:text-accent-300 bg-accent-50 dark:bg-accent-900/20 hover:bg-accent-100 dark:hover:bg-accent-900/30 transition-colors rounded-t-xl"
        >
          <span className="flex items-center gap-2">
            <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" d="M9 6.75V15m6-6v8.25m.503 3.498 4.875-2.437c.381-.19.622-.58.622-1.006V4.82c0-.836-.88-1.38-1.628-1.006l-3.869 1.934c-.317.159-.69.159-1.006 0L9.503 3.252a1.125 1.125 0 0 0-1.006 0L3.622 5.689C3.24 5.88 3 6.27 3 6.695V19.18c0 .836.88 1.38 1.628 1.006l3.869-1.934c.317-.159.69-.159 1.006 0l4.994 2.497c.317.158.69.158 1.006 0Z" />
            </svg>
            My Tracks
          </span>
          <span className="flex items-center gap-1.5">
            {customTracks.tracks.length > 0 && (
              <span className="text-[10px] font-bold px-1 py-0.5 rounded-full bg-accent-100 dark:bg-accent-900/40 text-accent-700 dark:text-accent-300">{customTracks.tracks.length}</span>
            )}
            <svg className="w-3.5 h-3.5 opacity-70" fill="none" stroke="currentColor" strokeWidth={2.5} viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" d="M9 5l7 7-7 7" />
            </svg>
          </span>
        </button>
        <div className="border-t border-slate-100 dark:border-slate-800" />
        <RouteBuilder
          building={route.building} waypoints={route.waypoints}
          route={route.route} snapping={route.snapping}
          snapToTrails={route.snapToTrails} error={route.error}
          elevation={route.elevation}
          onStart={route.startBuilding} onFinish={route.finishBuilding}
          onCancel={route.cancelBuilding} onClear={route.clearRoute}
          onToggleSnap={() => route.setSnapToTrails((v) => !v)}
          onSaveTrack={handleSaveTrack}
        />
      </div>

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

      {bottomPanel === "merge" && (
        <MergePanel
          sources={mergeSources}
          imperial={settings?.units === "imperial"}
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
            drawing={region.drawing} bbox={region.bbox} sizeEstimate={region.sizeEstimate}
            regions={region.regions} pending={region.pending}
            onStart={region.startDrawing} onCancel={region.cancelDrawing}
            onDownload={region.downloadRegion} onDelete={region.deleteRegion}
            onHighlight={region.highlightRegion} onZoom={region.zoomToRegion}
          />
        </ControlPopup>
      )}

      {/* Elevation profile of the route under construction / built */}
      <ElevationProfile
        elevation={route.elevation}
        snapping={route.snapping}
        onClose={route.clearRoute}
      />

      {/* Custom-track detail + elevation overlay (selected on the map). */}
      {selectedCourseId != null && (
        <CustomTrackDetail
          trackId={selectedCourseId}
          folders={customTracks.folders}
          imperial={settings?.units === "imperial"}
          onChanged={customTracks.refresh}
          onClose={() => setSelectedCourseId(null)}
          onPreviewGeometry={previewGeometry}
        />
      )}

      {/* Activity detail overlay (a recorded activity selected on the map). */}
      {selectedActivity != null && (
        <ActivityDetailPanel
          activityId={selectedActivity.id}
          imperial={settings?.units === "imperial"}
          creating={creatingFromActivity}
          onCreateTrack={handleCreateFromActivity}
          onClose={() => setSelectedActivity(null)}
        />
      )}

      <CustomTrackManager
        open={trackManagerOpen}
        onClose={() => setTrackManagerOpen(false)}
        tracks={customTracks.tracks}
        folders={customTracks.folders}
        imperial={settings?.units === "imperial"}
        onRefresh={customTracks.refresh}
        onSelect={(t) => { selectCourse(t.id); flyToTrack(t); setTrackManagerOpen(false); }}
        onStartBuilder={route.startBuilding}
      />

      {!ready && !error && (
        <div className="absolute inset-0 flex flex-col items-center justify-center bg-[#f2efe9] gap-3 z-20">
          <div className="w-8 h-8 border-2 border-accent-500 border-t-transparent rounded-full animate-spin" />
          <p className="text-sm text-slate-500">Loading map…</p>
        </div>
      )}

      {/* The area drawn was already covered by one we have — say which. */}
      {region.alreadyHave && (
        <div className="absolute bottom-16 left-1/2 -translate-x-1/2 z-20 bg-white dark:bg-slate-900 rounded-xl shadow-lg border border-sky-200 dark:border-sky-800 px-3.5 py-2.5 flex items-start gap-3 max-w-sm w-[calc(100%-2rem)]">
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

      {/* Single unified download / error toast */}
      {showToast && (
        <div className="absolute bottom-16 left-1/2 -translate-x-1/2 z-20 bg-white dark:bg-slate-900 rounded-xl shadow-lg border border-sky-200 dark:border-sky-800 px-3.5 py-2.5 flex items-start gap-3 max-w-sm w-[calc(100%-2rem)]">
          {globalErrored.length > 0 ? (
            <svg className="w-5 h-5 shrink-0 text-red-500 mt-0.5" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v2m0 4h.01" />
            </svg>
          ) : globalActive.length === 0 ? (
            <div className="w-5 h-5 border-2 border-sky-500 border-t-transparent rounded-full animate-spin shrink-0 mt-0.5" />
          ) : null}
          <div className="min-w-0 space-y-1 w-full">
            {(globalActive.length > 0 || regionDownloading.length > 0 || regionMerging.length > 0) && (
              <>
                <p className="text-sm font-medium text-slate-800 dark:text-slate-200">Setting up map data</p>
                {globalActive.map((d) => (
                  <div key={d.id} className="flex items-center gap-2.5">
                    <ProgressRing percent={d.progress} size={30} className="text-sky-500 shrink-0" />
                    <div className="min-w-0">
                      <p className="text-xs text-slate-600 dark:text-slate-300 truncate">
                        {d.label}
                      </p>
                      <p className="text-[10px] text-slate-400">
                        {Math.round(d.progress || 0)}% · {d.detail || d.size_hint}
                      </p>
                    </div>
                  </div>
                ))}
                {regionMerging.length > 0 && (
                  <p className="text-xs text-slate-400">Merging tiles into the map…</p>
                )}
                <p className="text-[10px] text-slate-400/70">Tiles will appear automatically when ready</p>
              </>
            )}
            {globalErrored.map((d) => (
              <p key={d.id} className="text-xs text-red-500">{d.label}: {d.error}</p>
            ))}
          </div>
        </div>
      )}

      {error && !hideBanner && (
        <div className="absolute bottom-16 left-1/2 -translate-x-1/2 z-20 max-w-md w-[calc(100%-2rem)] bg-white dark:bg-slate-900 rounded-xl shadow-lg border border-amber-200 dark:border-amber-800 p-3.5">
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
            <button onClick={() => setHideBanner(true)} className="shrink-0 text-slate-400 hover:text-slate-600 dark:hover:text-slate-300">
              <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
