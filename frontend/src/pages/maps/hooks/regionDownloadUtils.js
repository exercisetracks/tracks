// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// ---------------------------------------------------------------------------
// regionDownloadUtils — co-located helpers for useRegionDownload.
//
// These are the DECOUPLED pieces of the region-download flow: they take `map`
// (and a version snapshot) as explicit arguments and share NONE of the hook's
// React state or refs, so they live cleanly at module scope. The stateful
// drawing/polling state machine stays inside the hook itself (useRegionDownload.js).
//
// Contents:
//   • formatBytes            — download-size label formatter (pure)
//   • waitForTilesHealthy    — poll go-pmtiles until it serves again post-restart
//   • refreshTiles           — re-point changed sources at fresh ?v=mtime URLs
//   • ensureDemSource        — lazily add the DEM + hillshade layers once data exists
// ---------------------------------------------------------------------------
import { api } from "../../../api/client";
import { buildSourceUrl, buildDemSources, TILESET_NAMES } from "../style/sources";
import { buildHillshade, hillshadeBeforeId } from "../style/layers/hillshade";

// Human-readable label for the estimated download size (basemap + DEM bytes).
export function formatBytes(bytes) {
  if (!bytes || bytes <= 0) return "<1 MB";
  const mb = bytes / 1e6;
  if (mb < 1) return "<1 MB";
  if (mb < 1000) return `~${Math.round(mb)} MB`;
  return `~${(mb / 1000).toFixed(1)} GB`;
}

// Poll a known-present tile until go-pmtiles is serving again after a restart,
// so the subsequent setTiles doesn't land in the ~2s restart window (which would
// error tiles and leave them blank until a manual reload). z0 basemap always
// exists. Bounded so a wedged restart can't hang the refresh loop.
export async function waitForTilesHealthy(isActive, timeoutMs = 15000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if (isActive && !isActive()) return false;
    try {
      const r = await fetch(`/api/tiles/basemap/0/0/0.mvt?h=${Date.now()}`, {
        method: "GET",
        cache: "no-store",
      });
      if (r.ok) return true;
    } catch {}
    await new Promise((res) => setTimeout(res, 500));
  }
  return false;
}

// Re-point every live source whose tileset mtime changed at its fresh
// `?v=mtime` URL (so the browser refetches merged tiles), then ensure the DEM
// source exists. The basemap is not in TILESET_NAMES, so it never reloads/flashes.
export async function refreshTiles(map) {
  try {
    const version = await api.getTileVersion();
    const style = map.getStyle();
    if (style?.sources) {
      for (const [key] of Object.entries(style.sources)) {
        if (TILESET_NAMES[key]) {
          const tilesetName = TILESET_NAMES[key];
          const mtime = version[tilesetName];
          const url = buildSourceUrl(key, mtime);
          if (url) {
            const source = map.getSource(key);
            if (source) {
              const current = source.tiles?.[0];
              if (current === url) continue;
              try { source.setTiles([url]); } catch {}
            }
          }
        }
      }

      // DEM source is omitted from the initial style to avoid 404 spam when
      // master_dem.pmtiles hasn't been downloaded yet. Add it (and the hillshade
      // layer) the first time the version endpoint reports a non-zero DEM mtime.
      ensureDemSource(map, version);

      map.triggerRepaint();
    }
  } catch {}
}

// Lazily add the regional DEM source + both hillshade layers the first time the
// version endpoint reports a non-zero DEM mtime (and keep the source URL fresh
// on subsequent merges). No-op until the DEM file actually exists.
//
// The definitions themselves come from the style builders, which is what keeps
// the map the browser assembles at runtime identical to the one the backend
// serves the phone — see buildStyle's `includeDem`.
export function ensureDemSource(map, version) {
  const demMtime = version["master_dem"];
  if (!demMtime) return; // file doesn't exist yet

  const demSources = buildDemSources(demMtime);

  if (!map.getSource("dem")) {
    const url = buildSourceUrl("dem", demMtime);
    if (!url) return;
    try {
      map.addSource("dem", demSources.dem);
    } catch { return; }
  } else {
    const url = buildSourceUrl("dem", demMtime);
    const source = map.getSource("dem");
    if (url && source.tiles?.[0] !== url) {
      try { source.setTiles([url]); } catch {}
    }
  }

  if (!map.getSource("dem_overview")) {
    try { map.addSource("dem_overview", demSources.dem_overview); } catch {}
  }

  // Under water, and in the order the builder returns them: the overview first,
  // then the sharp regional layer above it. `beforeId` is the same for both, so
  // each insert lands directly under water and above whatever went in before it.
  const beforeId = hillshadeBeforeId(map.getStyle().layers);
  for (const layer of buildHillshade()) {
    if (map.getLayer(layer.id)) continue;
    if (!map.getSource(layer.source)) continue;
    try { map.addLayer(layer, beforeId); } catch {}
  }
}
