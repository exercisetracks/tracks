// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Self-hosted MapLibre race map for the Race Plan page. Draws the course as a
// purple line and lets the user click to drop a weather-location pin; a
// `marker` puts a dot on the course where the elevation plot is hovered. It is a
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

export default function RaceMap({ coursePath, pinLat, pinLon, onPin, disabled, theme, center, zoom, marker, legs }) {
  const mapRef = useRef(null);
  const pinRef = useRef(null);
  const markerRef = useRef(null);
  const [ready, setReady] = useState(false);
  const didFit = useRef(false);
  // Refs mirror the latest onPin/disabled so the once-bound click handler always
  // sees current values without re-binding (which would drop the listener).
  const onPinRef = useRef(onPin);      onPinRef.current = onPin;
  const disabledRef = useRef(disabled); disabledRef.current = disabled;

  // Course as a GeoJSON LineString FeatureCollection (empty until >1 point).
  // A triathlon's legs, when given, are drawn each in its colour, over the
  // whole course (which a leg's own file may not follow).
  const courseFC = useMemo(() => {
    const line = (path, color) => ({ type: "Feature", properties: { color },
      geometry: { type: "LineString", coordinates: path.map(p => [p[1], p[0]]) } });
    const features = coursePath?.length > 1 ? [line(coursePath, "#8b5cf6")] : [];
    for (const l of legs ?? []) if (l.path?.length > 1) features.push(line(l.path, l.color));
    return { type: "FeatureCollection", features };
  }, [coursePath, legs]);

  // Add the course source/layer on first ready, or update its data thereafter.
  const ensureCourse = (map) => {
    if (!map.getSource("course")) {
      map.addSource("course", { type: "geojson", data: courseFC });
      map.addLayer({
        id: "course-line", type: "line", source: "course",
        layout: { "line-cap": "round", "line-join": "round" },
        paint: { "line-color": ["get", "color"], "line-width": 3, "line-opacity": 0.85 },
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

  // The spot the elevation plot is pointing at, [lat, lon] or null.
  useEffect(() => {
    if (!ready) return;
    if (!marker) { markerRef.current?.remove(); markerRef.current = null; return; }
    if (!markerRef.current) {
      const el = document.createElement("div");
      el.className = "w-3.5 h-3.5 rounded-full bg-white border-[3px] border-violet-500 shadow";
      markerRef.current = new maplibregl.Marker({ element: el }).setLngLat([marker[1], marker[0]]).addTo(mapRef.current);
    } else {
      markerRef.current.setLngLat([marker[1], marker[0]]);
    }
  }, [ready, marker]);

  // fit to the course once it loads
  useEffect(() => {
    if (!ready || didFit.current || !(coursePath?.length > 1 || legs?.some((l) => l.path?.length > 1))) return;
    let minLat=90,maxLat=-90,minLng=180,maxLng=-180;
    for (const [la, lo] of [...(coursePath ?? []), ...(legs ?? []).flatMap((l) => l.path ?? [])]) {
      if (la<minLat) minLat=la; if (la>maxLat) maxLat=la;
      if (lo<minLng) minLng=lo; if (lo>maxLng) maxLng=lo;
    }
    didFit.current = true;
    mapRef.current.fitBounds([[minLng,minLat],[maxLng,maxLat]], { padding: 24, animate: false });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready, coursePath, legs]);

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
