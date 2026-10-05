// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useRef, useCallback } from "react";
import { api } from "../../../api/client";

const HOVER_SRC = { source: "activity_tracks" };

// Module-level cache of the activity-tracks geojson so re-opening the map page (or
// re-toggling the layer) shows the tracks INSTANTLY instead of blocking on a full
// re-fetch every time. The very first load still pays the fetch; afterwards the
// cached set is fed immediately and refreshed in the background (stale-while-
// revalidate) so newly-imported activities appear without a visible reload.
let _cache = null;        // last geojson
let _cacheAt = 0;         // ms timestamp
const _STALE_MS = 30_000;

// Feeds the `activity_tracks` GeoJSON source (past activities as queryable lines)
// and paints them in the user's theme accent. Data loads the first time the layer
// is toggled on; visibility itself is handled by the generic layer toggle.
function accentRgb(level = 500) {
  try {
    const v = getComputedStyle(document.documentElement).getPropertyValue(`--accent-${level}`).trim();
    if (v) return `rgb(${v})`;
  } catch { /* ignore */ }
  return "#10b981";
}

export function useActivityTracks(map, ready, enabled, accent) {
  const fetchingRef = useRef(false);
  const dataRef = useRef(_cache);   // seed from the cross-mount cache
  const hoveredRef = useRef(null);

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
    const hasLayer = () => { try { return !!map.getLayer(HIT); } catch { return false; } };
    const onMove = (e) => {
      const f = e.features && e.features[0];
      const id = f && f.id != null ? f.id : null;
      map.getCanvas().style.cursor = id != null ? "pointer" : "";
      setHoveredActivity(id);
    };
    const onLeave = () => { map.getCanvas().style.cursor = ""; setHoveredActivity(null); };
    if (hasLayer()) { map.on("mousemove", HIT, onMove); map.on("mouseleave", HIT, onLeave); }
    return () => {
      try { map.off("mousemove", HIT, onMove); map.off("mouseleave", HIT, onLeave); } catch { /* ignore */ }
    };
  }, [map, ready, setHoveredActivity]);

  // When enabled: feed the cached set immediately (no pause), then revalidate in the
  // background only if it's missing or stale.
  useEffect(() => {
    if (!ready || !map || !enabled) return;
    const feed = (gj) => {
      dataRef.current = gj;
      try {
        map.getSource("activity_tracks")?.setData(gj);
        // Apply the accent colour now that data + theme vars are live (arrows stay white).
        map.setPaintProperty("activity_tracks_line", "line-color", accentRgb(500));
      } catch { /* not ready */ }
    };

    if (_cache) feed(_cache);   // instant from cache

    if ((!_cache || Date.now() - _cacheAt > _STALE_MS) && !fetchingRef.current) {
      fetchingRef.current = true;
      api.getActivityTracks()
        .then((gj) => { _cache = gj; _cacheAt = Date.now(); feed(gj); })
        .catch(() => { /* keep showing cache */ })
        .finally(() => { fetchingRef.current = false; });
    }
  }, [map, ready, enabled]);

  // Paint the line + arrows in the theme accent (re-applies on accent change and
  // after a style reload, which also drops geojson data — re-feed it then).
  useEffect(() => {
    if (!ready || !map) return;
    const apply = () => {
      try { map.setPaintProperty("activity_tracks_line", "line-color", accentRgb(500)); } catch { /* not added */ }
      if (dataRef.current) { try { map.getSource("activity_tracks")?.setData(dataRef.current); } catch { /* ignore */ } }
    };
    apply();
    map.on("styledata", apply);
    return () => map.off("styledata", apply);
  }, [map, ready, accent]);

  return { setHoveredActivity };
}
