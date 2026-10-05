// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useRef } from "react";
import maplibregl from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import { buildBasemapStyle } from "./basemapStyle";

// Reusable self-hosted MapLibre basemap for the activity / route / heatmap maps.
// Replaces the old react-leaflet + external raster tiles. The parent receives the
// map instance via `onReady(map)` and attaches its own overlays (route lines,
// markers, custom layers). onReady fires after every style load, so overlays can
// be (idempotently) re-added when the theme switches.
export default function MapLibreMap({
  theme = "light",
  onReady,
  initialCenter = [0, 20],
  initialZoom = 1.4,
  // The backdrop's real tiles stop at z12 (see basemapStyle.js); everything
  // above is MapLibre scaling that z12 tile up, so the basemap gets steadily
  // blurrier while the GPS overlays stay vector-crisp. 19 is where the blur
  // stops being worth more zoom — the overlays are the point, and they remain
  // legible well past the basemap's usefulness. (MapLibre's own default is 22.)
  maxZoom = 19,
  interactive = true,
  className = "",
  style,
  // Zoom buttons go top-LEFT: every embedded map puts its own control panel at
  // top-3 right-3 (ActivityHeatmap's mode/sport/date box, ActivityRoute's colour
  // picker), which rendered on top of them. Top-left is free on all three.
  // Attribution stays bottom-right on purpose — the legends already clear it by
  // sitting at bottom-6, and ActivityRoute's legend is itself bottom-LEFT, so
  // moving attribution there would just trade one overlap for another.
  // Both overridable for callers whose chrome lives elsewhere.
  navPosition = "top-left",
  attributionPosition = "bottom-right",
}) {
  const containerRef = useRef(null);
  const mapRef = useRef(null);
  const onReadyRef = useRef(onReady);
  onReadyRef.current = onReady;

  // Create the map exactly once.
  useEffect(() => {
    if (!containerRef.current) return undefined;
    const map = new maplibregl.Map({
      container: containerRef.current,
      style: buildBasemapStyle(theme),
      center: initialCenter,
      zoom: initialZoom,
      maxZoom,
      interactive,
      attributionControl: false,
      // MapLibre's tile/glyph workers can't resolve root-relative "/api/..."
      // URLs (no page base in the worker), so make them absolute against the
      // current origin — same approach the maps page uses.
      transformRequest: (url) =>
        url.startsWith("/api/") ? { url: window.location.origin + url } : undefined,
    });
    mapRef.current = map;
    if (interactive) {
      map.addControl(new maplibregl.NavigationControl({ showCompass: false }), navPosition);
    }
    map.addControl(new maplibregl.AttributionControl({ compact: true }), attributionPosition);
    // These maps often mount inside a tab / a flex/grid card whose height settles
    // a tick after init. Defer onReady until the container actually has a size,
    // otherwise an early fitBounds computes the wrong zoom (and the map renders a
    // blank first frame). map fires 'resize' when its ResizeObserver catches the
    // real size, so retry then.
    const fireReady = () => {
      map.resize();
      if ((map.getCanvas()?.width ?? 0) <= 1) {
        map.once("resize", fireReady);
        return;
      }
      onReadyRef.current?.(map);
    };
    map.on("load", fireReady);

    return () => { map.remove(); mapRef.current = null; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Theme switch → swap basemap style, then let the parent re-add its overlays.
  const styledOnce = useRef(false);
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    if (!styledOnce.current) { styledOnce.current = true; return; } // initial style already set
    map.setStyle(buildBasemapStyle(theme));
    map.once("styledata", () => onReadyRef.current?.(map));
  }, [theme]);

  return <div ref={containerRef} className={className} style={style} />;
}
