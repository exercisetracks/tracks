// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useState, useCallback, useRef, useEffect } from "react";
import { api } from "../../../api/client";
import {
  formatBytes,
  waitForTilesHealthy,
  refreshTiles, // internally lazily calls ensureDemSource once tiles are healthy
} from "./regionDownloadUtils";

// ===========================================================================
// useRegionDownload — the region-data download orchestrator for the map page.
//
// This hook owns an imperative, stateful state machine that is intentionally
// KEPT WHOLE: draw a bbox → estimate its size → download → poll progress →
// merge/refresh tiles, plus outline overlays and cancel handling. Its effects
// and callbacks all close over the same `map`, `handlersRef`, version refs, and
// state setters, so splitting them apart would just force those refs to be
// threaded through arguments and make the code harder to follow, not easier.
//
// The genuinely decoupled pieces (size formatting + the tile-refresh / DEM-
// ensure helpers, which take `map` explicitly and touch no hook state) live in
// ./regionDownloadUtils to keep this file focused on the state machine.
//
// Sections below:
//   1. State + refs
//   2. Region list loading + adaptive poll
//   3. Download-size estimate (debounced)
//   4. Region outline overlays on the map
//   5. Tile-version watcher (post-merge refresh)
//   6. Bbox drawing: cleanup / rect rendering / draw interaction
//   7. Public actions: download / delete / highlight / zoom
// ===========================================================================
export function useRegionDownload(map) {
  // --- 1. State + refs ---------------------------------------------------
  const [drawing, setDrawing] = useState(false);
  const [bbox, setBbox] = useState(null);
  const [sizeEstimate, setSizeEstimate] = useState(null);
  const [regions, setRegions] = useState([]);
  const [pending, setPending] = useState(false);
  // The saved area that already covered the last box drawn, if one did.
  const [alreadyHave, setAlreadyHave] = useState(null);
  const handlersRef = useRef({});
  const lastVersionRef = useRef(null);
  const versionPollRef = useRef(null);
  const highlightedRef = useRef(null);

  // --- 2. Region list loading + adaptive poll ----------------------------
  const loadRegions = useCallback(async () => {
    try {
      const r = await api.getRegions();
      setRegions(r);
    } catch {}
  }, []);

  // Poll fast while something is in flight (download/merge/delete) so progress
  // wheels appear and retire promptly; idle back to a lazy 30s.
  const hasActive = regions.some((r) =>
    ["downloading", "downloading_dem", "merging", "trails", "contours", "combining", "cancelling", "deleting"].includes(r.status)
  );
  useEffect(() => {
    loadRegions();
    const iv = setInterval(loadRegions, hasActive ? 4000 : 30000);
    return () => clearInterval(iv);
  }, [loadRegions, hasActive]);

  // --- 3. Download-size estimate (debounced) -----------------------------
  // Precise download-size estimate: the exact byte size of the basemap (z13-15)
  // + DEM extracts, computed server-side via `pmtiles extract --dry-run`.
  // Debounced so it only fires when the user pauses dragging the rectangle.
  useEffect(() => {
    if (!drawing || !bbox) return;
    const [w, s, e, n] = bbox;
    if (w === e || s === n) { setSizeEstimate(null); return; }
    setSizeEstimate("…");
    let live = true;
    const t = setTimeout(async () => {
      try {
        const { bytes } = await api.estimateRegion(bbox);
        if (live) setSizeEstimate(formatBytes(bytes));
      } catch {
        if (live) setSizeEstimate(null);
      }
    }, 500);
    return () => { live = false; clearTimeout(t); };
  }, [bbox, drawing]);

  // --- 4. Region outline overlays on the map -----------------------------
  useEffect(() => {
    if (!map) return;

    // Drawing region overlays mutates the style (addSource/addLayer), which
    // throws "Style is not done loading" if the regions API resolves before the
    // map's first 'load'. Defer until the style is ready, then re-run by bumping
    // the regions reference.
    if (!map.isStyleLoaded()) {
      const onLoad = () => setRegions((r) => [...r]);
      map.once("load", onLoad);
      return () => map.off("load", onLoad);
    }

    const installed = regions.filter((r) => r.status === "installed");

    const features = installed.map((r) => ({
      type: "Feature",
      id: r.id, // top-level id enables setFeatureState for hover highlighting
      // Merged areas carry a union Polygon/MultiPolygon outline; plain areas just
      // draw their bbox rectangle.
      geometry: r.geometry || {
        type: "Polygon",
        coordinates: [[
          [r.bbox[0], r.bbox[1]],
          [r.bbox[2], r.bbox[1]],
          [r.bbox[2], r.bbox[3]],
          [r.bbox[0], r.bbox[3]],
          [r.bbox[0], r.bbox[1]],
        ]],
      },
      properties: { id: r.id, name: r.name },
    }));

    const geojson = { type: "FeatureCollection", features };

    if (!map.getSource("regions")) {
      map.addSource("regions", { type: "geojson", data: geojson });
      const hover = (on, off) => ["case", ["boolean", ["feature-state", "hover"], false], on, off];
      map.addLayer({
        id: "regions-fill",
        type: "fill",
        source: "regions",
        paint: { "fill-color": "#3b82f6", "fill-opacity": hover(0.18, 0.04) },
      });
      map.addLayer({
        id: "regions-border-solid",
        type: "line",
        source: "regions",
        paint: { "line-color": "#3b82f6", "line-width": hover(3, 2), "line-opacity": hover(0.6, 0.25) },
      });
      map.addLayer({
        id: "regions-border-dash",
        type: "line",
        source: "regions",
        paint: { "line-color": hover("#2563eb", "#60a5fa"), "line-width": hover(3, 2), "line-opacity": 1, "line-dasharray": [4, 4] },
      });
    } else if (features.length > 0) {
      map.getSource("regions").setData(geojson);
      ["regions-fill", "regions-border-solid", "regions-border-dash"].forEach(
        (id) => { try { map.setLayoutProperty(id, "visibility", "visible"); } catch {} }
      );
    } else {
      ["regions-fill", "regions-border-solid", "regions-border-dash"].forEach(
        (id) => { try { map.setLayoutProperty(id, "visibility", "none"); } catch {} }
      );
    }
  }, [map, regions]);

  // --- 5. Tile-version watcher (post-merge refresh) ----------------------
  useEffect(() => {
    if (!map) return;
    let active = true;

    async function checkVersion() {
      if (!active) return;
      try {
        const version = await api.getTileVersion();
        const prev = lastVersionRef.current;
        if (!prev) {
          lastVersionRef.current = version;
          if (!map.isMoving()) await refreshTiles(map);
          return;
        }
        const changed = Object.keys(version).some((k) => version[k] !== prev[k]);
        if (!changed) return;
        // Don't refresh mid-gesture; retry on moveend. lastVersionRef isn't
        // advanced until a refresh succeeds, so the poll also retries.
        if (map.isMoving()) {
          map.once("moveend", () => { if (active) checkVersion(); });
          return;
        }
        // A merge swapped small archives and restarted go-pmtiles. Wait until it
        // is serving again (instead of a fixed sleep that can race the restart),
        // then refresh ONLY the changed sources (overlay / contours / dem). The
        // basemap is not in TILESET_NAMES, so it never reloads / flashes.
        await waitForTilesHealthy(() => active);
        if (!active || map.isMoving()) return;
        await refreshTiles(map);
        lastVersionRef.current = version;
      } catch {}
    }

    // Refresh immediately on map load. Also call regardless of map.loaded()
    // state — if the safety timer pre-empted the load event we still need to
    // kick an initial refresh so existing tiles show up without a page reload.
    const onLoad = () => { if (active) refreshTiles(map); };
    if (map.loaded()) {
      refreshTiles(map);
    } else {
      map.once("load", onLoad);
      // Safety-net: if the load event never fires (tiles missing, style error),
      // still attempt a refresh after the map's safety timer window (8s + buffer).
      setTimeout(() => { if (active && !map.loaded()) refreshTiles(map); }, 9000);
    }

    checkVersion();
    // Poll every 10s so new tiles appear within ~15s of a merge completing,
    // rather than waiting up to 30s.
    versionPollRef.current = setInterval(checkVersion, 10_000);
    return () => {
      active = false;
      clearInterval(versionPollRef.current);
      map.off("load", onLoad);
    };
  }, [map]);

  // --- 6. Bbox drawing: cleanup / rect rendering / draw interaction ------
  // Draw a rectangle by drag; corners are draggable handles for resizing. The
  // live handlers are stashed on handlersRef so cleanup can detach the exact
  // same function references. `_drawRect` renders the rectangle + corner dots.
  function _cleanupDraw() {
    if (!map) return;
    const h = handlersRef.current;
    if (h.mousedown) map.off("mousedown", h.mousedown);
    if (h.mousemove) map.off("mousemove", h.mousemove);
    if (h.mouseup) map.off("mouseup", h.mouseup);
    if (h.dblclick) map.off("dblclick", h.dblclick);
    handlersRef.current = {};
    _cleanupRect();
  }

  function _cleanupRect() {
    if (!map) return;
    try { map.removeLayer("draw-rect-fill"); } catch {}
    try { map.removeLayer("draw-rect-line"); } catch {}
    try { map.removeLayer("draw-rect-corners"); } catch {}
    try { map.removeSource("draw-rect"); } catch {}
  }

  function _drawRect(bbox) {
    if (!map) return;
    const [w, s, e, n] = bbox;

    const polygon = {
      type: "Feature",
      geometry: {
        type: "Polygon",
        coordinates: [[[w, s], [e, s], [e, n], [w, n], [w, s]]],
      },
    };
    const outline = {
      type: "Feature",
      geometry: {
        type: "LineString",
        coordinates: [[w, s], [e, s], [e, n], [w, n], [w, s]],
      },
    };
    const corners = {
      type: "FeatureCollection",
      features: [
        { type: "Feature", geometry: { type: "Point", coordinates: [w, n] }, properties: { corner: "nw" } },
        { type: "Feature", geometry: { type: "Point", coordinates: [e, n] }, properties: { corner: "ne" } },
        { type: "Feature", geometry: { type: "Point", coordinates: [e, s] }, properties: { corner: "se" } },
        { type: "Feature", geometry: { type: "Point", coordinates: [w, s] }, properties: { corner: "sw" } },
      ],
    };

    if (!map.getSource("draw-rect")) {
      map.addSource("draw-rect", { type: "geojson", data: { type: "FeatureCollection", features: [polygon, outline, ...corners.features] } });
      map.addLayer({ id: "draw-rect-fill", type: "fill", source: "draw-rect", paint: { "fill-color": "#2563eb", "fill-opacity": 0.12 } });
      map.addLayer({ id: "draw-rect-line", type: "line", source: "draw-rect", paint: { "line-color": "#2563eb", "line-width": 2, "line-opacity": 0.9, "line-dasharray": [3, 2] } });
      map.addLayer({ id: "draw-rect-corners", type: "circle", source: "draw-rect", filter: ["has", "corner"], paint: { "circle-radius": 6, "circle-color": "#2563eb", "circle-opacity": 0.9, "circle-stroke-color": "#ffffff", "circle-stroke-width": 2, "circle-stroke-opacity": 1 } });
    } else {
      map.getSource("draw-rect").setData({ type: "FeatureCollection", features: [polygon, outline, ...corners.features] });
    }
    map.getCanvas().style.cursor = "crosshair";
  }

  const startDrawing = useCallback(() => {
    if (!map) return;
    _cleanupDraw();
    setDrawing(true);
    setBbox(null);
    setSizeEstimate(null);
    map.getCanvas().style.cursor = "crosshair";

    try { map.dragPan.disable(); } catch {}
    try { map.boxZoom.disable(); } catch {}
    try { map.scrollZoom.disable(); } catch {}

    let startLngLat = null;
    let hasRect = false;
    let currentBbox = null;
    let resizingCorner = null;

    function cornerUnderPoint(point) {
      if (!currentBbox) return null;
      const [w, s, e, n] = currentBbox;
      const corners = {
        nw: [w, n], ne: [e, n], se: [e, s], sw: [w, s],
      };
      for (const [name, [clng, clat]] of Object.entries(corners)) {
        const cp = map.project([clng, clat]);
        const dx = point.x - cp.x;
        const dy = point.y - cp.y;
        if (Math.sqrt(dx * dx + dy * dy) < 14) return name;
      }
      return null;
    }

    handlersRef.current.mousedown = (e) => {
      e.preventDefault();
      if (currentBbox) {
        resizingCorner = cornerUnderPoint(e.point);
        if (resizingCorner) {
          startLngLat = map.unproject([e.point.x, e.point.y]);
          return;
        }
      }
      resizingCorner = null;
      startLngLat = map.unproject([e.point.x, e.point.y]);
      currentBbox = null;
      setBbox(null);
      setSizeEstimate(null);
      _cleanupRect();
    };

    handlersRef.current.mousemove = (e) => {
      if (!startLngLat) return;
      const end = map.unproject([e.point.x, e.point.y]);

      if (resizingCorner) {
        const [w, s, e2, n] = currentBbox;
        let nw = w, ns = s, ne2 = e2, nn = n;
        if (resizingCorner.includes("n")) nn = end.lat;
        if (resizingCorner.includes("s")) ns = end.lat;
        if (resizingCorner.includes("e")) ne2 = end.lng;
        if (resizingCorner.includes("w")) nw = end.lng;
        const newBbox = [
          Math.min(nw, ne2),
          Math.min(ns, nn),
          Math.max(nw, ne2),
          Math.max(ns, nn),
        ];
        currentBbox = newBbox;
        setBbox(newBbox);
        setSizeEstimate("…");
        _drawRect(newBbox);
        return;
      }

      const w = Math.min(startLngLat.lng, end.lng);
      const s = Math.min(startLngLat.lat, end.lat);
      const e2 = Math.max(startLngLat.lng, end.lng);
      const n = Math.max(startLngLat.lat, end.lat);
      const newBbox = [w, s, e2, n];
      currentBbox = newBbox;
      setBbox(newBbox);
      setSizeEstimate("…");
      _drawRect(newBbox);
      hasRect = true;
    };

    handlersRef.current.mouseup = () => {
      resizingCorner = null;
      startLngLat = null;
      map.getCanvas().style.cursor = currentBbox ? "default" : "crosshair";
      if (!hasRect && !currentBbox) {
        map.off("mousedown", handlersRef.current.mousedown);
        map.off("mousemove", handlersRef.current.mousemove);
        map.off("mouseup", handlersRef.current.mouseup);
        try { map.dragPan.enable(); } catch {}
        try { map.boxZoom.enable(); } catch {}
        try { map.scrollZoom.enable(); } catch {}
        setDrawing(false);
        _cleanupRect();
      }
    };

    handlersRef.current.dblclick = () => {
      map.off("mousedown", handlersRef.current.mousedown);
      map.off("mousemove", handlersRef.current.mousemove);
      map.off("mouseup", handlersRef.current.mouseup);
      map.off("dblclick", handlersRef.current.dblclick);
      _cleanupRect();
      map.getCanvas().style.cursor = "";
      setDrawing(false);
      setBbox(null);
      setSizeEstimate(null);
      try { map.dragPan.enable(); } catch {}
      try { map.boxZoom.enable(); } catch {}
      try { map.scrollZoom.enable(); } catch {}
    };

    map.on("mousedown", handlersRef.current.mousedown);
    map.on("mousemove", handlersRef.current.mousemove);
    map.on("mouseup", handlersRef.current.mouseup);
    map.on("dblclick", handlersRef.current.dblclick);
  }, [map]);

  const cancelDrawing = useCallback(() => {
    _cleanupDraw();
    setDrawing(false);
    setBbox(null);
    setSizeEstimate(null);
    if (map) {
      map.getCanvas().style.cursor = "";
      try { map.dragPan.enable(); } catch {}
      try { map.boxZoom.enable(); } catch {}
      try { map.scrollZoom.enable(); } catch {}
    }
  }, [map]);

  // --- 7. Public actions: download / delete / highlight / zoom -----------
  const deleteRegion = useCallback(
    async (id) => {
      await api.deleteRegion(id);
      await loadRegions();
    },
    [loadRegions, map]
  );

  // Highlight a saved area's outline on the map (hover in the list). Clears the
  // previously-highlighted area first; pass null to clear.
  const highlightRegion = useCallback((id) => {
    if (!map || !map.getSource("regions")) return;
    const prev = highlightedRef.current;
    if (prev != null && prev !== id) {
      try { map.setFeatureState({ source: "regions", id: prev }, { hover: false }); } catch {}
    }
    highlightedRef.current = id;
    if (id != null) {
      try { map.setFeatureState({ source: "regions", id }, { hover: true }); } catch {}
    }
  }, [map]);

  // Zoom/pan so the area fills ~50% of the viewport (25% padding each side).
  const zoomToRegion = useCallback((region) => {
    if (!map || !region?.bbox) return;
    const [w, s, e, n] = region.bbox;
    const c = map.getContainer();
    const padX = Math.max(20, Math.min(c.clientWidth * 0.25, c.clientWidth / 2 - 20));
    const padY = Math.max(20, Math.min(c.clientHeight * 0.25, c.clientHeight / 2 - 20));
    try {
      map.fitBounds([[w, s], [e, n]], {
        padding: { top: padY, bottom: padY, left: padX, right: padX },
        duration: 800,
        maxZoom: 14,
      });
    } catch {}
  }, [map]);

  const downloadRegion = useCallback(
    async (name) => {
      if (!bbox) return;
      setPending(true);
      try {
        const res = await api.downloadRegion({ bbox, name });
        await loadRegions();
        // The server answers "exists" when an area it already holds covers what
        // was drawn, and starts nothing. Without saying so this reads as a
        // button that did nothing: the box closes, no download toast appears,
        // and the area responsible is already sitting in the list unremarked.
        if (res?.status === "exists" && res.region) {
          highlightRegion(res.region.id);
          zoomToRegion(res.region);
          setAlreadyHave(res.region);
        }
      } catch (e) {
        alert(e?.message || "Download failed");
      } finally {
        setPending(false);
        cancelDrawing();
      }
    },
    [bbox, loadRegions, cancelDrawing, highlightRegion, zoomToRegion, map]
  );

  return {
    drawing, bbox, sizeEstimate, regions, pending,
    alreadyHave, dismissAlreadyHave: () => setAlreadyHave(null),
    startDrawing, cancelDrawing, downloadRegion, deleteRegion,
    highlightRegion, zoomToRegion,
  };
}
