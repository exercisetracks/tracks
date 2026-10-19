// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useRef, useCallback } from "react";
import { api } from "../../../api/client";
import { onLayerHover } from "./hoverHub";
import { HeatmapGlowLayer, MAP_ACTIVITY_GLOW, buildHeatmapVerts } from "../../../components/map/HeatmapGlowLayer";

const GLOW_ID = "activity_tracks_glow";
const DRAPED_ID = "activity_tracks_draped";

// The theme's accent as [r, g, b]: the activities' colour, as their lines
// always were — distinct from the basemap's orange roads and red trails.
function accentRgb() {
  try {
    const v = getComputedStyle(document.documentElement).getPropertyValue("--accent-600").trim();
    const parts = v.split(/[\s,]+/).map(Number);
    if (parts.length >= 3 && parts.every((n) => Number.isFinite(n))) return parts.slice(0, 3);
  } catch { /* default below */ }
  return [37, 99, 235];
}

// GeoJSON LineStrings ([lng, lat]) → the glow's tracks ([lat, lng]).
function tracksOf(gj) {
  return (gj?.features || [])
    .map((f) => f?.geometry?.type === "LineString" ? f.geometry.coordinates.map((c) => [c[1], c[0]]) : null)
    .filter((t) => t && t.length > 1);
}

const HOVER_SRC = { source: "activity_tracks" };

// Module-level cache of the activity-tracks geojson so re-opening the map page (or
// re-toggling the layer) shows the tracks INSTANTLY instead of blocking on a full
// re-fetch every time. The very first load still pays the fetch; afterwards the
// cached set is fed immediately and refreshed in the background (stale-while-
// revalidate) so newly-imported activities appear without a visible reload.
let _cache = null;        // last geojson
let _cacheAt = 0;         // ms timestamp
const _STALE_MS = 30_000;

// Feeds the `activity_tracks` GeoJSON source (past activities as queryable
// lines, for clicking and the hover halo) and draws the routes themselves as
// the dashboard's heatmap: the glow layer, in its light-map form — ink that
// deepens where routes overlap — since the map page's basemap is pale. Data
// loads the first time the layer is toggled on; the glow is shown and hidden
// with the "Activity Tracks" toggle, the hit line by the generic toggle.
export function useActivityTracks(map, ready, enabled) {
  const fetchingRef = useRef(false);
  const dataRef = useRef(_cache);   // seed from the cross-mount cache
  const hoveredRef = useRef(null);
  const glowRef = useRef(null);
  const enabledRef = useRef(enabled);
  enabledRef.current = enabled;

  // The glow: added under the hover halo, fed while the layer is on, and put
  // back after a style reload, which drops custom layers.
  const syncGlow = useCallback(() => {
    if (!map) return;
    try {
      if (!enabledRef.current || !dataRef.current) {
        if (map.getLayer(GLOW_ID)) map.removeLayer(GLOW_ID);
        glowRef.current = null;
        return;
      }
      if (!map.getLayer(GLOW_ID)) {
        const layer = new HeatmapGlowLayer(GLOW_ID);
        layer.setStyle(MAP_ACTIVITY_GLOW);
        layer.setSubtractive(true);
        map.addLayer(layer, map.getLayer("activity_tracks_hover") ? "activity_tracks_hover" : undefined);
        glowRef.current = layer;
        glowRef.current.fedWith = null;
      }
      const layer = glowRef.current || map.getLayer(GLOW_ID)?.implementation;
      if (layer && layer.fedWith !== dataRef.current) {
        const built = buildHeatmapVerts(tracksOf(dataRef.current), "frequency", "light", { color: accentRgb() });
        layer.setData(built.verts, built.count);
        layer.fedWith = dataRef.current;
      }
    } catch { /* style mid-reload; styledata calls again */ }
  }, [map]);

  // Glow on flat ground, draped lines over terrain (see activity_tracks_draped
  // in the style for why the glow cannot follow the hills).
  const syncDraped = useCallback(() => {
    if (!map) return;
    const terrain = !!map.getTerrain?.();
    const on = enabledRef.current && !!dataRef.current;
    try {
      if (map.getLayer(DRAPED_ID)) {
        map.setLayoutProperty(DRAPED_ID, "visibility", on && terrain ? "visible" : "none");
        const [r, g, b] = accentRgb();
        map.setPaintProperty(DRAPED_ID, "line-color", `rgb(${r}, ${g}, ${b})`);
      }
      if (map.getLayer(GLOW_ID)) map.setLayoutProperty(GLOW_ID, "visibility", terrain ? "none" : "visible");
    } catch { /* style mid-reload; styledata calls again */ }
  }, [map]);

  // Set the `hover` feature-state on one activity (clearing the previous). Driven
  // by both the on-map mousemove below and the point-info "Nearby activities" list.
  const setHoveredActivity = useCallback((id) => {
    if (!map || hoveredRef.current === id) return;
    if (hoveredRef.current != null) {
      try { map.setFeatureState({ ...HOVER_SRC, id: hoveredRef.current }, { hover: false }); } catch { /* gone */ }
    }
    hoveredRef.current = id;
    if (id != null) {
      try { map.setFeatureState({ ...HOVER_SRC, id }, { hover: true }); } catch { /* not ready */ }
    }
  }, [map]);

  // Hover the track under the cursor (layer-specific mousemove on the wide hit line).
  useEffect(() => {
    if (!ready || !map) return;
    const HIT = "activity_tracks_hit";
    const onMove = (e) => {
      const f = e.features && e.features[0];
      const id = f && f.id != null ? f.id : null;
      map.getCanvas().style.cursor = id != null ? "pointer" : "";
      setHoveredActivity(id);
    };
    const onLeave = () => { map.getCanvas().style.cursor = ""; setHoveredActivity(null); };
    // One shared, frame-throttled query (hoverHub): this hover was the one
    // that lagged in 3D.
    return onLayerHover(map, [HIT], { move: onMove, leave: onLeave });
  }, [map, ready, setHoveredActivity]);

  // When enabled: feed the cached set immediately (no pause), then revalidate in the
  // background only if it's missing or stale.
  useEffect(() => {
    if (!ready || !map || !enabled) return;
    const feed = (gj) => {
      dataRef.current = gj;
      try { map.getSource("activity_tracks")?.setData(gj); } catch { /* not ready */ }
      syncGlow();
      syncDraped();
    };

    if (_cache) feed(_cache);   // instant from cache

    if ((!_cache || Date.now() - _cacheAt > _STALE_MS) && !fetchingRef.current) {
      fetchingRef.current = true;
      api.getActivityTracks()
        .then((gj) => { _cache = gj; _cacheAt = Date.now(); feed(gj); })
        .catch(() => { /* keep showing cache */ })
        .finally(() => { fetchingRef.current = false; });
    }
  }, [map, ready, enabled, syncGlow, syncDraped]);

  // Off: the glow goes with the toggle.
  useEffect(() => {
    if (ready && map && !enabled) { syncGlow(); syncDraped(); }
  }, [map, ready, enabled, syncGlow, syncDraped]);

  // Into and out of 3D: MapLibre fires `terrain` when the mesh is set or removed.
  useEffect(() => {
    if (!ready || !map) return undefined;
    map.on("terrain", syncDraped);
    return () => map.off("terrain", syncDraped);
  }, [map, ready, syncDraped]);

  // After a style reload, which drops geojson data and custom layers: re-feed both.
  useEffect(() => {
    if (!ready || !map) return undefined;
    const apply = () => {
      if (dataRef.current) { try { map.getSource("activity_tracks")?.setData(dataRef.current); } catch { /* ignore */ } }
      if (!map.getLayer(GLOW_ID)) glowRef.current = null;
      syncGlow();
      syncDraped();
    };
    map.on("styledata", apply);
    return () => map.off("styledata", apply);
  }, [map, ready, syncGlow, syncDraped]);

  return { setHoveredActivity };
}
