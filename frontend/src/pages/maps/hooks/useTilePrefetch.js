// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useRef } from "react";

// How long the camera must sit completely still before we start speculatively
// warming the browser's HTTP cache for tiles just outside the current view.
// Chosen well past use3D's 240ms motion-settle debounce: that one exists to
// un-hide LOD-shed labels the instant a gesture ends, but idle prefetch is a
// lower-priority background job — it should only kick in once the user has
// genuinely paused to look, not on the tail of every wheel notch of a slow zoom.
const IDLE_DELAY_MS = 900;

// Tile sources worth speculating on. POI features are fetched by bbox (not
// tiled) and already have their own debounced fetch in usePoiFeatures, so
// they're not part of this.
const PREFETCH_SOURCE_IDS = ["basemap", "overlay", "routes_osm", "contours", "dem"];

// Hard per-source cap so one idle pause can never turn into an unbounded
// request burst.
const MAX_TILES_PER_SOURCE = 24;
const FETCH_CONCURRENCY = 3;

function lon2tile(lon, z) {
  return Math.floor(((lon + 180) / 360) * 2 ** z);
}
function lat2tile(lat, z) {
  const rad = (lat * Math.PI) / 180;
  return Math.floor(((1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2) * 2 ** z);
}

// Tiles covering `bounds` at zoom `z`, padded by `pad` tiles on every edge and
// clamped to the valid [0, 2^z) tile index range. Used to speculate on a PAN.
function ringTiles(bounds, z, pad) {
  const maxIdx = 2 ** z - 1;
  const clamp = (n) => Math.max(0, Math.min(maxIdx, n));
  const x0 = clamp(lon2tile(bounds.getWest(), z) - pad);
  const x1 = clamp(lon2tile(bounds.getEast(), z) + pad);
  const y0 = clamp(lat2tile(bounds.getNorth(), z) - pad);
  const y1 = clamp(lat2tile(bounds.getSouth(), z) + pad);
  const out = [];
  for (let x = x0; x <= x1; x++) {
    for (let y = y0; y <= y1; y++) out.push([x, y]);
  }
  return out;
}

// The 4 children of tile (x,y) one zoom level down — used to speculate on a
// ZOOM-IN centered near where the user is currently looking.
function childTiles(x, y) {
  return [[2 * x, 2 * y], [2 * x + 1, 2 * y], [2 * x, 2 * y + 1], [2 * x + 1, 2 * y + 1]];
}

// Respect the same "limited connectivity" posture as the region-download
// feature: never speculate on a metered or visibly slow connection, and never
// speculate while offline (the fetches would just queue and fail anyway).
function connectionAllowsPrefetch() {
  if (navigator.onLine === false) return false;
  const conn = navigator.connection || navigator.mozConnection || navigator.webkitConnection;
  if (conn?.saveData) return false;
  if (conn?.effectiveType && /2g/.test(conn.effectiveType)) return false;
  return true;
}

async function runPool(tasks, concurrency, signal) {
  let i = 0;
  async function worker() {
    while (i < tasks.length && !signal.aborted) {
      const task = tasks[i++];
      try { await task(); } catch { /* aborted, 404, offline mid-flight — fine, this is speculative */ }
    }
  }
  await Promise.all(Array.from({ length: Math.min(concurrency, tasks.length) }, worker));
}

// Speculatively warms the browser's HTTP cache for tiles just outside the
// current viewport (pan) and one zoom level in (zoom), while the camera sits
// idle — so the pan/zoom that follows a pause resolves from disk cache
// instead of round-tripping to our own tile backend. Every URL fetched here is
// one MapLibre would eventually request anyway (this never talks to a
// third-party tile source, only our own /api/tiles); we're just front-running
// idle time with it.
//
// Aborted the instant the camera moves again, so a speculative fetch can
// never contend with a real, visible-tile request for the shared HTTP/1.1
// connection pool — that contention would recreate the exact stutter this is
// meant to prevent.
export function useTilePrefetch(map, ready) {
  const idleTimerRef = useRef(null);
  const abortRef = useRef(null);

  useEffect(() => {
    if (!map || !ready) return undefined;

    const cancelPrefetch = () => {
      clearTimeout(idleTimerRef.current);
      abortRef.current?.abort();
      abortRef.current = null;
    };

    async function runPrefetch() {
      if (!connectionAllowsPrefetch()) return;
      const controller = new AbortController();
      abortRef.current = controller;

      const z = Math.round(map.getZoom());
      const bounds = map.getBounds();
      const center = map.getCenter();
      const tasks = [];

      for (const id of PREFETCH_SOURCE_IDS) {
        const source = map.getSource(id);
        const template = source?.tiles?.[0];
        if (!template) continue; // e.g. "dem" before any region has been downloaded
        const minzoom = source.minzoom ?? 0;
        const maxzoom = source.maxzoom ?? 22;

        const coords = [];
        if (z >= minzoom && z <= maxzoom) {
          coords.push(...ringTiles(bounds, z, 1).map(([x, y]) => [z, x, y]));
        }
        if (z + 1 >= minzoom && z + 1 <= maxzoom) {
          coords.push(...childTiles(lon2tile(center.lng, z), lat2tile(center.lat, z))
            .map(([x, y]) => [z + 1, x, y]));
        }

        for (const [tz, tx, ty] of coords.slice(0, MAX_TILES_PER_SOURCE)) {
          const url = template.replace("{z}", tz).replace("{x}", tx).replace("{y}", ty);
          tasks.push(() => fetch(url, { signal: controller.signal, priority: "low" }));
        }
      }

      await runPool(tasks, FETCH_CONCURRENCY, controller.signal);
    }

    const schedulePrefetch = () => {
      cancelPrefetch();
      idleTimerRef.current = setTimeout(runPrefetch, IDLE_DELAY_MS);
    };

    map.on("moveend", schedulePrefetch);
    map.on("zoomend", schedulePrefetch);
    map.on("movestart", cancelPrefetch);
    map.on("zoomstart", cancelPrefetch);
    map.on("dragstart", cancelPrefetch);
    schedulePrefetch(); // camera is already settled on mount — speculate right away

    return () => {
      cancelPrefetch();
      map.off("moveend", schedulePrefetch);
      map.off("zoomend", schedulePrefetch);
      map.off("movestart", cancelPrefetch);
      map.off("zoomstart", cancelPrefetch);
      map.off("dragstart", cancelPrefetch);
    };
  }, [map, ready]);
}
