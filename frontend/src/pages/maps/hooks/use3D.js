// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useState, useEffect, useCallback, useRef } from "react";

// Pitch (camera tilt) applied when toggling 3D on via the pip.
const PITCH_3D = 60;
// Above this pitch the map counts as "3D" — lights the control and turns on the
// terrain mesh. Small enough that a deliberate drag-tilt registers, large enough
// that incidental wobble at pitch 0 doesn't.
const PITCH_THRESHOLD = 5;
// Hard cap on tilt. Kept a touch below a full 80° so the orbit gizmo can't drive
// the camera into the near-horizon band where the terrain tile-fan explodes.
export const MAX_PITCH = 75;
// Vertical exaggeration of the terrain mesh. >1 makes relief read clearly at the
// zoom levels people actually fly the map around at.
const EXAGGERATION = 1.4;
// How long after the last camera-move frame we treat the camera as settled and
// bring the shed detail back. Off `moveend` (fires at the true end of a
// gesture/animation), debounced to coalesce a compass drag's burst of moveends
// so a tilted orbit stays gated end-to-end instead of flickering the restore
// between frames.
const MOVE_SETTLE_MS = 240;

// The movement LOD only sheds layers on a TILTED pan — a flat 2D pan is cheap
// enough to leave at full detail, but an orbiting/tilted pan re-collides the
// densest label layers against a moving horizon every frame.
//
// A ZOOM never sheds anything: MapLibre keeps rendering the already-placed
// symbol quads through a zoom, scaling them continuously with the camera each
// frame — full collision only re-runs once the camera settles. Icons/labels
// blanking out mid-zoom reads as broken, so this LOD leaves zoom alone
// entirely and lets that native scaling show through.
//
// Only SYMBOL layers are ever shed (collectHeavyLayers filters on type), so the
// contour LINES the user wants held stay drawn through every gesture regardless.
const LOD_KEEP = new Set([
  "contours_index", "contours_intermediate", "contours_labels",
  "poi_db_icons", "poi_water_icons",
]);

// Collect the heavy (symbol) layers to hide during motion, minus whatever the
// active gesture wants kept. Hiding only toggles layer visibility (no canvas
// resize), so it never flashes the terrain.
function collectHeavyLayers(map, keep) {
  const ids = [];
  let order;
  try { order = map.getLayersOrder(); } catch { return ids; }
  for (const id of order) {
    if (keep.has(id)) continue;
    let type;
    try { type = map.getLayer(id)?.type; } catch { continue; }
    if (type === "symbol") ids.push(id);
  }
  return ids;
}

// Pick the best available DEM raster source for the terrain mesh. The sharp
// regional `dem` (z8-16, downloaded areas) is preferred; `dem_overview` (global
// z0-7) is the fallback so a coarse global 3D still works everywhere. Returns
// null when no DEM has been downloaded (tilt still works, just flat).
function pickDemSource(map) {
  if (map.getSource("dem")) return "dem";
  if (map.getSource("dem_overview")) return "dem_overview";
  return null;
}

// Owns the "3D map" state: the terrain mesh, the camera tilt/bearing, and the
// motion level-of-detail pass that sheds the heaviest symbol layers during a
// tilted pan so a settled view is full detail (zoom is exempt — see the
// LOD_KEEP comment above). Drag-into-tilt and the compass are detected the
// same way, so button state and terrain stay in sync however the user got there.
export function use3D(map, ready) {
  const [is3D, setIs3D] = useState(false);
  const is3DRef = useRef(false);
  // Snapshot of each heavy layer's visibility taken when a 3D move begins, so the
  // restore puts back exactly what the user had (respecting the LayerPanel).
  const lodSnapRef = useRef(null);
  const settleTimerRef = useRef(null);
  const movingRef = useRef(false);
  // True for the duration of a wheel/pinch zoom gesture (set on zoomstart,
  // cleared on settle) so onMove knows to exempt zoom from LOD shedding even
  // when the camera is tilted.
  const zoomingRef = useRef(false);

  // Attach/detach the terrain mesh to match `on`, idempotently. Guarded by the
  // map's own terrain state so a re-entrant call — setTerrain recalculates the
  // camera and re-fires move/pitch events synchronously — is a no-op instead of
  // an infinite setTerrain → event → setTerrain recursion that freezes the tab.
  const syncTerrain = useCallback((on) => {
    if (!map) return;
    const has = !!map.getTerrain();
    if (on && !has) {
      const source = pickDemSource(map);
      if (source) {
        try { map.setTerrain({ source, exaggeration: EXAGGERATION }); } catch {}
      }
    } else if (!on && has) {
      try { map.setTerrain(null); } catch {}
    }
  }, [map]);

  // Hide the heavy symbol layers on the first move frame; the settle debounce
  // restores them. Snapshot is only taken when there isn't one already, so
  // repeated frames in one gesture don't clobber the remembered visibilities.
  const gateHeavy = useCallback(() => {
    if (!map || lodSnapRef.current) return;
    const snap = {};
    collectHeavyLayers(map, LOD_KEEP).forEach((id) => {
      try {
        snap[id] = map.getLayoutProperty(id, "visibility") ?? "visible";
        map.setLayoutProperty(id, "visibility", "none");
      } catch {}
    });
    lodSnapRef.current = snap;
  }, [map]);

  const restoreHeavy = useCallback(() => {
    if (!map) return;
    const snap = lodSnapRef.current;
    if (!snap) return;
    lodSnapRef.current = null;
    Object.entries(snap).forEach(([id, vis]) => {
      try {
        if (map.getLayer(id)) map.setLayoutProperty(id, "visibility", vis);
      } catch {}
    });
  }, [map]);

  useEffect(() => {
    if (!map || !ready) return;

    // Camera settled: leave "moving" and bring the shed detail back.
    const onSettle = () => {
      movingRef.current = false;
      zoomingRef.current = false;
      restoreHeavy();
    };

    // `zoomstart` fires just before the first `move` of a wheel/pinch zoom, so by
    // the time onMove runs we already know whether this gesture is a zoom and can
    // exempt it from shedding. Reset happens on settle.
    const onZoomStart = () => { zoomingRef.current = true; };

    // Every move frame: shed the heavy symbol layers only on a TILTED pan (a flat
    // 2D pan is cheap and we don't want labels popping on it). A zoom is never
    // gated — see the LOD_KEEP comment above. The restore is driven off the
    // settle debounce, which fires only at the true end of a gesture — never
    // between slow frames — so a tilted orbit stays gated throughout.
    const onMove = () => {
      if (zoomingRef.current) return;
      if (map.getPitch() <= PITCH_THRESHOLD) return; // flat pan → full detail
      clearTimeout(settleTimerRef.current); // a new frame ⇒ not settled yet
      if (!movingRef.current) {
        movingRef.current = true;
        gateHeavy();
      }
    };

    // Flip the button state the moment the tilt crosses the 3D threshold, either
    // direction. Doesn't touch terrain (setTerrain mid-animation stalls easing).
    const onPitch = () => {
      const pitched = map.getPitch() > PITCH_THRESHOLD;
      if (pitched === is3DRef.current) return;
      is3DRef.current = pitched;
      setIs3D(pitched);
    };

    // End of a gesture/animation: reconcile the mesh to the final pitch, then
    // debounce the settle so a compass drag's burst of moveends coalesces into
    // one restore.
    const onEnd = () => {
      syncTerrain(map.getPitch() > PITCH_THRESHOLD);
      clearTimeout(settleTimerRef.current);
      settleTimerRef.current = setTimeout(onSettle, MOVE_SETTLE_MS);
    };

    map.on("zoomstart", onZoomStart);
    map.on("move", onMove);
    map.on("pitch", onPitch);
    map.on("moveend", onEnd);
    map.on("pitchend", onEnd);
    onPitch();
    return () => {
      map.off("zoomstart", onZoomStart);
      map.off("move", onMove);
      map.off("pitch", onPitch);
      map.off("moveend", onEnd);
      map.off("pitchend", onEnd);
      clearTimeout(settleTimerRef.current);
      movingRef.current = false;
      zoomingRef.current = false;
      restoreHeavy(); // never unmount with detail stuck hidden
    };
  }, [map, ready, syncTerrain, gateHeavy, restoreHeavy]);

  // 3D pip: always animate the tilt (independent of terrain, so it can never be
  // blocked). Going up, attach the mesh first so it's visible through the tilt;
  // going flat, let the moveend reconcile detach it when the animation lands.
  const toggle = useCallback(() => {
    if (!map) return;
    if (map.getPitch() > PITCH_THRESHOLD) {
      map.easeTo({ pitch: 0, duration: 600 });
    } else {
      syncTerrain(true);
      map.easeTo({ pitch: PITCH_3D, duration: 600 });
    }
  }, [map, syncTerrain]);

  // Orbit gizmo (the compass): drive bearing and/or pitch directly, no easing so
  // the camera tracks the drag. Attaches the mesh on the way up; detach on the
  // way to flat is left to the moveend reconcile.
  const orbitTo = useCallback(({ bearing, pitch }) => {
    if (!map) return;
    const opts = {};
    if (bearing != null) opts.bearing = bearing;
    if (pitch != null) {
      const p = Math.max(0, Math.min(MAX_PITCH, pitch));
      opts.pitch = p;
      if (p > PITCH_THRESHOLD) syncTerrain(true);
    }
    try { map.jumpTo(opts); } catch {}
  }, [map, syncTerrain]);

  return { is3D, toggle, orbitTo };
}
