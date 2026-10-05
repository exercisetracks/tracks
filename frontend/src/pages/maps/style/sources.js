// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// A version string (Unix mtime) is appended as ?v= to enable cache busting
// after region merges without requiring a full page reload.

export const TILESET_NAMES = {
  // basemap is intentionally absent: it's served as one source split by zoom in
  // Caddy (static z0-12 planet + small z13-15 detail) and is NOT cache-busted on
  // a region download — so it never visibly reloads. New z13-15 detail in a
  // freshly-downloaded area is fetched fresh on the next zoom-in.
  // Single multi-layer OSM overlay (trails/water/areas/infra/landuse). Listed
  // here so _refreshTiles live-updates it (?v=mtime) when a new region merges.
  overlay: "master_overlay",
  dem: "master_dem",
  // Global z0-7 DEM overview, served as a maxzoom:7 overzoom source so hillshade
  // fills z8-12 in areas with no regional DEM downloaded (soft relief everywhere).
  dem_overview: "planet_dem_z7",
  contours: "master_contours",
};

const TILE_EXT = {
  basemap: ".mvt",
  overlay: ".mvt",
  dem: ".webp",
  dem_overview: ".webp",
  contours: ".mvt",
};

export function buildSourceUrl(key, version) {
  const name = TILESET_NAMES[key];
  const ext = TILE_EXT[key];
  if (!name || !ext) return null;
  const v = version ? `?v=${version}` : "";
  return `/api/tiles/${name}/{z}/{x}/{y}${ext}${v}`;
}

const EMPTY_FC = { type: "FeatureCollection", features: [] };

/**
 * The two elevation sources, kept apart from `buildSources` on purpose.
 *
 * In the browser these are added at runtime once the version endpoint reports a
 * DEM (see `ensureDemSource`), because a style that names an archive nobody has
 * downloaded yet spends the session 404ing for tiles. The served style has a
 * better answer available to it — the backend drops sources whose archive is not
 * on disk, layers and all (`_fit_to_available_tiles`) — so it can carry them
 * honestly, which is what `buildStyle({ includeDem: true })` is for.
 *
 * Either way the definitions live here rather than in two places that drift.
 */
export function buildDemSources(version = "") {
  return {
    // Regional relief: only exists where a region has been downloaded. minzoom 8
    // keeps MapLibre off the z0-7 levels this archive never carries.
    dem: {
      type: "raster-dem",
      tiles: [buildSourceUrl("dem", version)],
      tileSize: 512,
      minzoom: 8,
      maxzoom: 16,
      encoding: "terrarium",
      attribution: '<a href="https://mapterhorn.com/attribution">© Mapterhorn</a>',
    },
    // Global z0-7 overview, declared as maxzoom 7 so MapLibre overzooms it across
    // the whole detail band. Without it, land outside any downloaded region
    // renders dead flat above z7 — this keeps soft relief everywhere and the
    // sharp regional shading sits on top wherever real z8-12 data exists.
    dem_overview: {
      type: "raster-dem",
      tiles: [buildSourceUrl("dem_overview")],
      tileSize: 512,
      minzoom: 0,
      maxzoom: 7,
      encoding: "terrarium",
      attribution: '<a href="https://mapterhorn.com/attribution">© Mapterhorn</a>',
    },
  };
}

export function buildSources(version = "") {
  const v = version ? `?v=${version}` : "";
  return {
    // GeoJSON source populated dynamically by usePoiFeatures on moveend/zoomend
    poi_features: {
      type: "geojson",
      data: EMPTY_FC,
    },
    // User custom tracks (courses), fed by useCustomTracks. promoteId:"id" lets
    // feature-state (hover/selected) light a whole track on the GPU.
    custom_tracks: {
      type: "geojson",
      data: EMPTY_FC,
      promoteId: "id",
    },
    // Past activities as queryable lines (the map-page "activity heatmap"), fed by
    // useActivityTracks. promoteId:"id" carries the activity id for click-select.
    activity_tracks: {
      type: "geojson",
      data: EMPTY_FC,
      promoteId: "id",
    },
    // Single-activity preview line shown while deciding whether to turn an activity
    // into a custom track (useActivityPreview). Empty until previewing.
    activity_preview: {
      type: "geojson",
      data: EMPTY_FC,
    },
    // Live wildfire + smoke overlays (opt-in), fed by useWildfires from the
    // backend NIFC/NOAA proxy. Empty until the toggles are switched on.
    wildfire_points: {
      type: "geojson",
      data: EMPTY_FC,
      attribution: "Fires: NIFC · NRCan CWFIS",
    },
    wildfire_perimeters: {
      type: "geojson",
      data: EMPTY_FC,
    },
    smoke_plumes: {
      type: "geojson",
      data: EMPTY_FC,
      attribution: "Smoke: NOAA HMS",
    },
    basemap: {
      type: "vector",
      // One continuous source backed by two files (Caddy splits on zoom): z0-12
      // from the static planet_basemap, z13-15 from master_basemap_detail. No
      // ?v= cache-bust — the overview never changes and detail is fetched fresh
      // on zoom-in, so the basemap never reloads.
      tiles: [`/api/tiles/basemap/{z}/{x}/{y}.mvt`],
      minzoom: 0,
      maxzoom: 15,
      attribution:
        '<a href="https://protomaps.com">Protomaps</a> © <a href="https://openstreetmap.org">OpenStreetMap</a>',
    },
    // Overzoomed landcover base wash. The basemap's `landcover` source-layer only
    // carries data at z0-7 (global ESA-WorldCover-derived cover); above z7 the only
    // land tint is `landuse`, which exists only where OSM has tagged polygons — so
    // open, unforested country (rangeland, desert, prairie) goes blank white. We
    // declare the SAME basemap tiles a second time capped at maxzoom 7, so MapLibre
    // overzooms that z7 cover up to z15 and the landcover layer can keep a faint
    // regional tint everywhere. No new data is fetched beyond the z7 landcover tiles
    // already in planet_basemap. Mirrors the dem_overview overzoom pattern above.
    basemap_overview: {
      type: "vector",
      tiles: [`/api/tiles/basemap/{z}/{x}/{y}.mvt`],
      minzoom: 0,
      maxzoom: 7,
      attribution:
        '<a href="https://protomaps.com">Protomaps</a> © <a href="https://openstreetmap.org">OpenStreetMap</a>',
    },
    // (basemap_landuse_overview removed with the "Parks & Landuse" wash — see
    // layers/landuse.js. The overzoomed public-land green dimmed water/hillshade
    // and is no longer drawn; landcover carries the terrain green.)
    // dem source is NOT included here — it is added dynamically by useRegionDownload
    // once master_dem.pmtiles exists, to prevent 404 console spam when the DEM
    // file hasn't been downloaded yet. See _ensureDemSource() in useRegionDownload.js.
    // Single multi-layer OSM overlay for downloaded regions — trails, water,
    // areas (vegetation/wetland), infra, and landuse (public lands) in ONE
    // tippecanoe-built archive (master_overlay), each exposed as its own
    // `source-layer`. One continuous z3-15 source (replacing the old five, which
    // each cut off at a different minzoom) so overlays no longer pop in at
    // different per-source zoom thresholds — per-feature LOD (tippecanoe minzoom)
    // plus the layers' own opacity fades handle appearance. The layers still read
    // their original source-layer names (trails/water/areas/infra/landuse).
    // Only present in downloaded regions; the basemap layers remain the global
    // fallback.
    overlay: {
      type: "vector",
      tiles: [`/api/tiles/master_overlay/{z}/{x}/{y}.mvt${v}`],
      // Region overlays start at z6: big public-land polygons are graded down
      // that low (landuse_builder._area_min_zoom) so they can fade in well
      // before the z9 detail band; trails/water/areas/infra still only carry
      // real data from z9+ (their own per-feature minzoom), the z3-5 long-trail
      // overview comes from the separate routes_osm source. minzoom 6 stops
      // MapLibre requesting non-existent z3-5 overlay tiles.
      minzoom: 6,
      maxzoom: 15,
      attribution:
        '© <a href="https://openstreetmap.org">OpenStreetMap</a> contributors',
    },
    // Region-INDEPENDENT long-distance route overview (master_routes) — built by
    // trail_builder.build_global_routes over the contiguous US, so long trails
    // (CDT/PCT/AT/CT/ADT…) + their emblem icons show everywhere, not just inside
    // downloaded regions. Archive is z3-12; maxzoom 12 so MapLibre overzooms it.
    routes_osm: {
      type: "vector",
      tiles: [`/api/tiles/master_routes/{z}/{x}/{y}.mvt${v}`],
      minzoom: 3,
      maxzoom: 12,
      // Promote each feature's baked `feat_key` (route_id:section_id) to the
      // feature id so feature-state hover lights a whole section at once on the
      // GPU (useRouteHover) — all of a section's way-features share one key.
      promoteId: { routes: "feat_key" },
      attribution:
        '© <a href="https://openstreetmap.org">OpenStreetMap</a> contributors',
    },
    contours: {
      type: "vector",
      // Archive contains z9–z12 tiles; z9 uses a sparse 1000 ft interval so the
      // overview stays readable. minzoom must be 9 (not 10) or MapLibre never
      // requests the z9 tiles and contours only begin at z10. maxzoom MUST match
      // the archive (12) so MapLibre overzooms z13-15 client-side — setting it to
      // 15 made it request non-existent z13-15 tiles (404 spam) and could drop
      // the overzoomed contour labels.
      minzoom: 9,
      maxzoom: 12,
      tiles: [`/api/tiles/master_contours/{z}/{x}/{y}.mvt${v}`],
      attribution: "",
    },
  };
}

