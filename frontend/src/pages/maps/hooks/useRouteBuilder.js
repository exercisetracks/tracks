// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// useRouteBuilder — the interactive route-drawing hook for the map page.
// Given the MapLibre map instance it lets the user click waypoints, snaps the
// legs to trails/roads via the backend router (falling back to straight legs
// densified with intermediate points so the off-trail elevation profile still
// has usable samples), and maintains the route source/layers (ROUTE_SRC + the
// casing/line layers). Returns the route state plus the add/undo/clear/export
// controls the map UI binds to. Accent colour is resolved from the theme CSS
// var since MapLibre's colour parser doesn't understand CSS var().
import { useState, useCallback, useRef, useEffect, useMemo } from "react";
import maplibregl from "../../../lib/maplibre";
import { api } from "../../../api/client";

const ROUTE_SRC = "route";
const ROUTE_LAYER = "route-line";
const ROUTE_CASING = "route-casing";

// Resolve the user's theme accent (a "r g b" CSS var) to an rgb() string that
// MapLibre's colour parser accepts (it doesn't understand CSS var()).
function _accent(level = 600) {
  try {
    const v = getComputedStyle(document.documentElement).getPropertyValue(`--accent-${level}`).trim();
    if (v) return `rgb(${v})`;
  } catch { /* ignore */ }
  return "#2563eb";
}

function _haversineKm(a, b) {
  const R = 6371;
  const dLat = ((b[1] - a[1]) * Math.PI) / 180;
  const dLon = ((b[0] - a[0]) * Math.PI) / 180;
  const la1 = (a[1] * Math.PI) / 180;
  const la2 = (b[1] * Math.PI) / 180;
  const sd = Math.sin(dLat / 2) ** 2 + Math.cos(la1) * Math.cos(la2) * Math.sin(dLon / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(sd), Math.sqrt(1 - sd));
}

// Insert intermediate points along each straight leg (~every `stepM` metres) so
// the un-snapped (off-trail) elevation profile has enough samples to be useful.
function _densify(waypoints, stepM = 90, maxPts = 600) {
  const out = [];
  for (let i = 0; i < waypoints.length - 1; i++) {
    const a = waypoints[i];
    const b = waypoints[i + 1];
    out.push(a);
    const segM = _haversineKm(a, b) * 1000;
    const n = Math.min(maxPts, Math.floor(segM / stepM));
    for (let k = 1; k < n; k++) {
      const t = k / n;
      out.push([a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t]);
    }
  }
  out.push(waypoints[waypoints.length - 1]);
  return out;
}

export function useRouteBuilder(map) {
  const [waypoints, setWaypoints] = useState([]);
  const [route, setRoute] = useState(null);
  const [snapping, setSnapping] = useState(false);
  const [building, setBuilding] = useState(false);
  const [snapToTrails, setSnapToTrails] = useState(true);
  const [error, setError] = useState(null);

  const markersRef = useRef([]);
  const handlersRef = useRef({});
  const snapTimer = useRef(null);
  const reqId = useRef(0);

  // ── Map helpers ─────────────────────────────────────────────────────────────
  const _clearMarkers = () => {
    markersRef.current.forEach((m) => m.remove());
    markersRef.current = [];
  };

  const _clearRoute = useCallback(() => {
    if (!map) return;
    for (const id of [ROUTE_LAYER, ROUTE_CASING]) {
      try { map.removeLayer(id); } catch {}
    }
    try { map.removeSource(ROUTE_SRC); } catch {}
  }, [map]);

  const _drawRoute = useCallback((geojson, snapped) => {
    if (!map) return;
    if (!map.getSource(ROUTE_SRC)) {
      map.addSource(ROUTE_SRC, { type: "geojson", data: geojson });
    } else {
      map.getSource(ROUTE_SRC).setData(geojson);
    }
    // Re-create the layers so we can swap solid (snapped) ↔ dashed (off-trail)
    // cleanly without fighting line-dasharray state.
    for (const id of [ROUTE_LAYER, ROUTE_CASING]) {
      try { map.removeLayer(id); } catch {}
    }
    map.addLayer({
      id: ROUTE_CASING, type: "line", source: ROUTE_SRC,
      layout: { "line-cap": "round", "line-join": "round" },
      paint: { "line-color": "#ffffff", "line-width": 7, "line-opacity": 0.7 },
    });
    map.addLayer({
      id: ROUTE_LAYER, type: "line", source: ROUTE_SRC,
      layout: { "line-cap": "round", "line-join": "round" },
      paint: {
        "line-color": snapped ? _accent(600) : "#64748b",
        "line-width": 4,
        "line-opacity": 0.95,
        ...(snapped ? {} : { "line-dasharray": [2, 1.6] }),
      },
    });
  }, [map]);

  const _cleanupBuilders = useCallback(() => {
    if (!map) return;
    const h = handlersRef.current;
    if (h.click) map.off("click", h.click);
    handlersRef.current = {};
  }, [map]);

  const _addMarker = useCallback((lngLat) => {
    if (!map) return;
    // Small numbered circle in the theme accent colour (not the big default pin).
    const el = document.createElement("div");
    el.textContent = String(markersRef.current.length + 1);
    Object.assign(el.style, {
      width: "18px", height: "18px", borderRadius: "9999px",
      background: "rgb(var(--accent-600))", color: "#fff",
      font: "700 10px/18px ui-sans-serif, system-ui, sans-serif",
      textAlign: "center", border: "2px solid #fff",
      boxShadow: "0 1px 3px rgba(0,0,0,0.45)", cursor: "grab",
    });
    const marker = new maplibregl.Marker({ element: el, draggable: true })
      .setLngLat(lngLat)
      .addTo(map);
    marker.on("dragend", () => {
      const idx = markersRef.current.indexOf(marker);
      if (idx === -1) return;
      setWaypoints((prev) => prev.map((w, i) => (i === idx ? marker.getLngLat().toArray() : w)));
    });
    markersRef.current.push(marker);
  }, [map]);

  // Build a direct (un-snapped) line through the waypoints with DEM elevation —
  // used for the off-trail mode and as a fallback when BRouter can't route.
  const _buildStraight = useCallback(async (wps) => {
    const dense = _densify(wps);
    let eles = [];
    try {
      const r = await api.routeElevation({ coordinates: dense });
      eles = r.elevations || [];
    } catch { /* elevation is best-effort */ }
    return {
      type: "FeatureCollection",
      features: [{
        type: "Feature", properties: {},
        geometry: { type: "LineString", coordinates: dense.map((c, i) => [c[0], c[1], eles[i] ?? null]) },
      }],
    };
  }, []);

  // ── Live recompute (snap or straight + elevation), debounced ────────────────
  useEffect(() => {
    if (!map) return;
    if (waypoints.length < 2) {
      _clearRoute();
      setRoute(null);
      return;
    }
    const id = ++reqId.current;
    clearTimeout(snapTimer.current);
    snapTimer.current = setTimeout(async () => {
      setSnapping(true);
      setError(null);
      try {
        let geojson;
        let snapped = snapToTrails;
        if (snapToTrails) {
          try {
            geojson = await api.snapRoute({ coordinates: waypoints });
          } catch {
            // BRouter couldn't route (e.g. a point is far from any trail) — fall
            // back to a direct line so the user always gets a preview + profile.
            snapped = false;
            geojson = await _buildStraight(waypoints);
            if (id === reqId.current) setError("Couldn't snap part of this to trails — showing a direct line.");
          }
        } else {
          geojson = await _buildStraight(waypoints);
        }
        if (id !== reqId.current) return; // a newer request superseded this one
        setRoute(geojson);
        _drawRoute(geojson, snapped);
      } catch (e) {
        if (id === reqId.current) setError(e?.message || "Routing failed");
      } finally {
        if (id === reqId.current) setSnapping(false);
      }
    }, 280);
    return () => clearTimeout(snapTimer.current);
  }, [waypoints, snapToTrails, map, _drawRoute, _clearRoute, _buildStraight]);

  // ── Public actions ──────────────────────────────────────────────────────────
  const startBuilding = useCallback(() => {
    if (!map) return;
    _cleanupBuilders();
    _clearMarkers();
    _clearRoute();
    setBuilding(true);
    setWaypoints([]);
    setRoute(null);
    setError(null);
    map.getCanvas().style.cursor = "crosshair";
    handlersRef.current.click = (e) => {
      const ll = e.lngLat.toArray();
      _addMarker(ll);
      setWaypoints((prev) => [...prev, ll]);
    };
    map.on("click", handlersRef.current.click);
  }, [map, _cleanupBuilders, _clearRoute, _addMarker]);

  const finishBuilding = useCallback(() => {
    _cleanupBuilders();
    setBuilding(false);
    if (map) map.getCanvas().style.cursor = "";
  }, [map, _cleanupBuilders]);

  const cancelBuilding = useCallback(() => {
    _cleanupBuilders();
    _clearMarkers();
    _clearRoute();
    setBuilding(false);
    setWaypoints([]);
    setRoute(null);
    setError(null);
    if (map) map.getCanvas().style.cursor = "";
  }, [map, _cleanupBuilders, _clearRoute]);

  const clearRoute = useCallback(() => {
    _cleanupBuilders();
    _clearMarkers();
    _clearRoute();
    setRoute(null);
    setWaypoints([]);
    setBuilding(false);
    setError(null);
    if (map) map.getCanvas().style.cursor = "";
  }, [map, _cleanupBuilders, _clearRoute]);

  // ── Derived: elevation profile series + stats ───────────────────────────────
  const elevation = useMemo(() => {
    const coords = route?.features?.[0]?.geometry?.coordinates;
    if (!coords || coords.length < 2) return null;
    let cum = 0, gain = 0, loss = 0, minE = Infinity, maxE = -Infinity;
    const points = [];
    for (let i = 0; i < coords.length; i++) {
      if (i > 0) cum += _haversineKm(coords[i - 1], coords[i]);
      const e = coords[i][2] ?? null;
      points.push({ d: cum, e });
      if (e != null) {
        if (e < minE) minE = e;
        if (e > maxE) maxE = e;
        const pe = i > 0 ? coords[i - 1][2] : null;
        if (pe != null) { const dd = e - pe; if (dd > 0) gain += dd; else loss -= dd; }
      }
    }
    const hasEle = points.some((p) => p.e != null);
    return {
      points, hasEle,
      distanceKm: cum,
      gain: Math.round(gain),
      loss: Math.round(loss),
      min: isFinite(minE) ? minE : null,
      max: isFinite(maxE) ? maxE : null,
    };
  }, [route]);

  return {
    waypoints, route, snapping, building, snapToTrails, error, elevation,
    setSnapToTrails,
    startBuilding, finishBuilding, cancelBuilding, clearRoute,
  };
}
