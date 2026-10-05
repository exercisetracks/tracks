// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useState, useRef } from "react";
import maplibregl from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import { buildStyle } from "../style";
import { installMissingTrailLogo } from "../trailBadge";

const PMT_PROTOCOL = "pmt";

async function pmtHandler(params, abortController) {
  const url = params.url.slice(PMT_PROTOCOL.length + 3);
  const options = {};
  if (params.method) options.method = params.method;
  if (params.headers) options.headers = params.headers;
  if (params.credentials) options.credentials = params.credentials;
  if (abortController) options.signal = abortController.signal;
  const response = await fetch(url, options);
  if (response.status === 204) {
    throw new Error("no tile");
  }
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`);
  }
  const buffer = await response.arrayBuffer();
  return {
    data: buffer,
    cacheControl: response.headers.get("Cache-Control"),
    expires: response.headers.get("Expires"),
  };
}

const WORKER_PROTOCOL_CODE = `
self.addProtocol('${PMT_PROTOCOL}', async (params, abortController) => {
  const url = params.url.slice(${PMT_PROTOCOL.length + 3});
  const options = {};
  if (params.method) options.method = params.method;
  if (params.headers) options.headers = params.headers;
  if (params.credentials) options.credentials = params.credentials;
  if (abortController) options.signal = abortController.signal;
  const response = await fetch(url, options);
  if (response.status === 204) {
    throw new Error('no tile');
  }
  if (!response.ok) {
    throw new Error('HTTP ' + response.status);
  }
  const buffer = await response.arrayBuffer();
  return { data: buffer, cacheControl: response.headers.get('Cache-Control'), expires: response.headers.get('Expires') };
});
`;

export function useMapInit(containerRef) {
  const [map, setMap] = useState(null);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState(null);
  const loadedRef = useRef(false);

  useEffect(() => {
    if (!containerRef.current) return;

    maplibregl.addProtocol(PMT_PROTOCOL, pmtHandler);
    const blob = new Blob([WORKER_PROTOCOL_CODE], { type: "application/javascript" });
    const workerUrl = URL.createObjectURL(blob);
    maplibregl.importScriptInWorkers(workerUrl).finally(() => {
      URL.revokeObjectURL(workerUrl);
    });

    const instance = new maplibregl.Map({
      container: containerRef.current,
      style: buildStyle(),
      center: [-98.5, 39.8],
      zoom: 4,
      minZoom: 1,
      maxZoom: 16,
      // This is a US-focused app that never pans across the antimeridian, so we
      // don't need MapLibre to render the wrapped-around duplicate world copies it
      // draws by default at low zoom — dropping them cuts tile requests and draw
      // calls at the global overview for free.
      renderWorldCopies: false,
      // No symbol fade animation. Collision detection is disabled style-wide
      // (withStablePlacement in style/index.js), so the only time a symbol
      // appears/disappears is when the underlying tile data changes across a
      // zoom level — and the paper-map feel we want is for it to just BE there,
      // not shimmer in over 300ms while placement settles.
      fadeDuration: 0,
      // Capped below a full 80° so the orbit gizmo can't drive the camera into
      // the near-horizon band where the terrain tile-fan explodes — a cheap
      // draw-distance limit that keeps tilted framerates sane. Kept in sync with
      // MAX_PITCH in use3D.js.
      maxPitch: 75,
      // No maxTileCacheSize override: MapLibre's default cache is sized
      // dynamically from the current viewport's tile count, which is exactly
      // what we want in 3D — at high tilt the visible-tile set balloons, and a
      // fixed cap smaller than that set thrashed (evict → refetch) as you panned,
      // making distant terrain flash between rendered and blank. Letting it
      // auto-size keeps the tilted horizon resident.
      // Abort in-flight tile requests for zoom levels the camera is flying past
      // (MapLibre's default). Keeping them alive (false) floods the tile worker
      // and network with tiles that are already stale before they arrive, which
      // is exactly the churn that made fast zoom stutter — the destination level
      // loads sooner when the doomed intermediate requests get out of its way.
      cancelPendingTileRequestsWhileZooming: true,
      // Don't re-download tiles we already hold just because an HTTP expiry
      // lapsed. The basemap/overlay/DEM archives only change on a region merge
      // (handled explicitly via the ?v=mtime cache-bust), so honouring per-tile
      // expiry only buys pointless refetches that slow re-visited zoom levels.
      refreshExpiredTiles: false,
      attributionControl: false,
      transformRequest: (url, resourceType) => {
        if (url.startsWith("/api/")) {
          if (resourceType === "Tile" && url.includes(".mvt")) {
            return { url: `${PMT_PROTOCOL}://${window.location.origin}${url}` };
          }
          return { url: window.location.origin + url };
        }
      },
    });

    // Small distance scale bar (imperial — US hiking app, feet elevations).
    instance.addControl(
      new maplibregl.ScaleControl({ maxWidth: 110, unit: "imperial" }),
      "bottom-left",
    );
    // Re-home it to the bottom-CENTER, clear of the corner button stacks that were
    // covering it. Its corner container is anchored bottom-left, so moving the
    // element under the map container lets `left:50%` center it on the full width.
    const scaleEl = instance.getContainer().querySelector(".maplibregl-ctrl-scale");
    if (scaleEl) {
      const scaleWrap = document.createElement("div");
      Object.assign(scaleWrap.style, {
        position: "absolute", left: "50%", bottom: "8px",
        transform: "translateX(-50%)", zIndex: "1", pointerEvents: "none",
      });
      scaleWrap.appendChild(scaleEl);
      instance.getContainer().appendChild(scaleWrap);
      // Hide the flat scale bar once the camera tilts into 3D: a single bar isn't
      // meaningful across a pitched perspective, and MapLibre re-solves its width
      // every frame there — which is the "jitter" seen while panning tilted.
      const syncScaleVis = () => {
        scaleWrap.style.display = instance.getPitch() > 5 ? "none" : "";
      };
      instance.on("pitch", syncScaleVis);
      syncScaleVis();
    }

    // Generate long-trail emblem badges on demand the first time the route-icon
    // layer asks for them (keyed off the trail name; see trailLogos.js).
    instance.on("styleimagemissing", (e) => installMissingTrailLogo(instance, e.id));

    instance.on("load", () => {
      loadedRef.current = true;
      setReady(true);
      setError(null);
      instance.setSky({
        "sky-color": "#a8d8f0",
        "horizon-color": "#f4e8d1",
        "fog-color": "#dce8f2",
        "fog-ground-blend": 0.5,
        "horizon-fog-blend": 0.6,
        "sky-horizon-blend": 0.2,
        "atmosphere-blend": 0.8,
      });
    });
    // Dev-only handle for debugging / automated screenshots. Guarded so it
    // never appears in production builds.
    if (import.meta.env.DEV) {
      window.__mapDebug = instance;
    }
    instance.on("error", (e) => {
      if (loadedRef.current) return;
      const msg = e?.error?.message || e?.error || "Map tile loading error";
      setError(msg);
    });

    const safetyTimer = setTimeout(() => {
      if (!loadedRef.current) {
        setReady(true);
        setError("Map data not available. Download an area to get started.");
      }
    }, 8000);

    setMap(instance);

    return () => {
      clearTimeout(safetyTimer);
      instance.remove();
      setMap(null);
      setReady(false);
      setError(null);
    };
  }, []);

  return { map, ready, error };
}
