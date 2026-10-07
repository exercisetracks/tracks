// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useRef } from "react";
import maplibregl from "../../lib/maplibre";
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
  // sitting at bottom-12, and ActivityRoute's legend is itself bottom-LEFT, so
  // moving attribution there would just trade one overlap for another.
  // Both overridable for callers whose chrome lives elsewhere.
  navPosition = "top-left",
  attributionPosition = "bottom-right",
}) {
  const containerRef = useRef(null);
  const mapRef = useRef(null);
  const onReadyRef = useRef(onReady);
  onReadyRef.current = onReady;
  // The theme the current map instance was built with. Per instance, not "has
  // this component styled once": StrictMode (dev) mounts, removes and creates
  // the map again while refs survive, and a component-level flag then sent the
  // brand-new map a setStyle before its first style had loaded. MapLibre
  // rebuilt the style from scratch mid-load and never fired `load`, so onReady
  // never ran and the map stayed a blank, unsized canvas. Lazy-loading the
  // dashboard heatmap shifted the timing enough to hit it on every load.
  const themeRef = useRef(theme);

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
    themeRef.current = theme;
    if (interactive) {
      map.addControl(new maplibregl.NavigationControl({ showCompass: false }), navPosition);
    }
    map.addControl(new maplibregl.AttributionControl({ compact: true }), attributionPosition);
    startAttributionCollapsed(map);
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
  useEffect(() => {
    const map = mapRef.current;
    if (!map || themeRef.current === theme) return; // this map already has it
    themeRef.current = theme;
    map.setStyle(buildBasemapStyle(theme));
    map.once("styledata", () => onReadyRef.current?.(map));
  }, [theme]);

  // The theme on the container too, for the controls MapLibre draws itself
  // (index.css): the map's theme, not the page's, decides how they look.
  const themed = `${className} ${theme === "dark" ? "map-theme-dark" : "map-theme-light"}`.trim();
  return <div ref={containerRef} className={themed} style={style} />;
}

/**
 * Collapse the credits to their "i" button the first time MapLibre opens them.
 *
 * A compact attribution control opens itself as soon as the first source
 * reports its credits, and stays open until the map is dragged — a strip of
 * text over the corner of every small map. It is collapsed exactly the way
 * MapLibre's own button does it (the class, plus `open` on the <details>), so
 * a click on "i" still expands it.
 */
function startAttributionCollapsed(map) {
  const el = map.getContainer().querySelector(".maplibregl-ctrl-attrib");
  if (!el) return;
  const collapse = () => {
    if (!el.classList.contains("maplibregl-compact-show")) return false;
    el.classList.remove("maplibregl-compact-show");
    el.setAttribute("open", "");
    return true;
  };
  if (collapse()) return;
  const observer = new MutationObserver(() => { if (collapse()) observer.disconnect(); });
  observer.observe(el, { attributes: true, attributeFilter: ["class"] });
  map.once("remove", () => observer.disconnect());
}
