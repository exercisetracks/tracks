// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Self-hosted MapLibre race map for the Race Plan page. Draws the course as a
// purple line and lets the user click to drop a weather-location pin. It is a
// pure presentational component: it takes the course path, pin coordinates,
// initial center/zoom and a theme via props, and reports pin clicks back through
// `onPin`. It holds no page state of its own.
//
// Coordinate convention: `coursePath` / `center` are [lat, lon] (as the backend
// and the rest of the page use), but MapLibre expects [lng, lat] — so every
// coordinate is flipped at the MapLibre boundary.

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import maplibregl from "../../lib/maplibre";
import MapLibreMap from "../map/MapLibreMap";

// Inline SVG for the weather pin marker (violet teardrop with a white dot).
// Assigned via innerHTML on a DOM element below — a fixed module constant, so
// there's no user-input injection risk.
const PIN_SVG =
  '<svg width="26" height="36" viewBox="0 0 26 36" xmlns="http://www.w3.org/2000/svg">' +
  '<path d="M13 0C5.8 0 0 5.8 0 13c0 9.2 13 23 13 23s13-13.8 13-23C26 5.8 20.2 0 13 0z" fill="#8b5cf6"/>' +
  '<circle cx="13" cy="13" r="5" fill="#fff"/></svg>';

export default function RaceMap({ coursePath, pinLat, pinLon, onPin, disabled, theme, center, zoom }) {
  const mapRef = useRef(null);
  const pinRef = useRef(null);
  const [ready, setReady] = useState(false);
  const didFit = useRef(false);
  // Refs mirror the latest onPin/disabled so the once-bound click handler always
  // sees current values without re-binding (which would drop the listener).
  const onPinRef = useRef(onPin);      onPinRef.current = onPin;
  const disabledRef = useRef(disabled); disabledRef.current = disabled;

  // Course as a GeoJSON LineString FeatureCollection (empty until >1 point).
  const courseFC = useMemo(() => ({
    type: "FeatureCollection",
    features: coursePath?.length > 1
      ? [{ type: "Feature", geometry: { type: "LineString", coordinates: coursePath.map(p => [p[1], p[0]]) }, properties: {} }]
      : [],
  }), [coursePath]);

  // Add the course source/layer on first ready, or update its data thereafter.
  const ensureCourse = (map) => {
    if (!map.getSource("course")) {
      map.addSource("course", { type: "geojson", data: courseFC });
      map.addLayer({
        id: "course-line", type: "line", source: "course",
        layout: { "line-cap": "round", "line-join": "round" },
        paint: { "line-color": "#8b5cf6", "line-width": 3, "line-opacity": 0.85 },
      });
    } else {
      map.getSource("course").setData(courseFC);
    }
  };

  const handleReady = useCallback((map) => {
    mapRef.current = map;
    ensureCourse(map);
    setReady(true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [courseFC]);

  // click-to-pin (bound once; survives theme/style swaps)
  useEffect(() => {
    if (!ready) return undefined;
    const map = mapRef.current;
    const onClick = (e) => { if (!disabledRef.current) onPinRef.current?.(e.lngLat.lat, e.lngLat.lng); };
    map.on("click", onClick);
    map.getCanvas().style.cursor = disabled ? "" : "crosshair";
    return () => map.off("click", onClick);
  }, [ready, disabled]);

  // keep the course line in sync (and re-add after a theme/style swap)
  useEffect(() => { if (ready) ensureCourse(mapRef.current); /* eslint-disable-line */ }, [ready, courseFC]);

  // pin marker
  useEffect(() => {
    if (!ready) return;
    const map = mapRef.current;
    if (pinLat == null || pinLon == null) {
      pinRef.current?.remove(); pinRef.current = null;
      return;
    }
    if (!pinRef.current) {
      const el = document.createElement("div");
      el.innerHTML = PIN_SVG;
      pinRef.current = new maplibregl.Marker({ element: el, anchor: "bottom" }).setLngLat([pinLon, pinLat]).addTo(map);
    } else {
      pinRef.current.setLngLat([pinLon, pinLat]);
    }
  }, [ready, pinLat, pinLon]);

  // fit to the course once it loads
  useEffect(() => {
    if (!ready || didFit.current || !(coursePath?.length > 1)) return;
    let minLat=90,maxLat=-90,minLng=180,maxLng=-180;
    for (const [la, lo] of coursePath) {
      if (la<minLat) minLat=la; if (la>maxLat) maxLat=la;
      if (lo<minLng) minLng=lo; if (lo>maxLng) maxLng=lo;
    }
    didFit.current = true;
    mapRef.current.fitBounds([[minLng,minLat],[maxLng,maxLat]], { padding: 24, animate: false });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready, coursePath]);

  return (
    <MapLibreMap
      theme={theme}
      onReady={handleReady}
      initialCenter={[center[1], center[0]]}
      initialZoom={zoom}
      className="w-full h-full"
    />
  );
}
