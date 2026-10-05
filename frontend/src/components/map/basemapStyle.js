// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Self-hosted basemap style for the activity / route / heatmap maps.
//
// Renders the same global vector basemap the maps page uses (master/planet basemap
// served by Caddy at /api/tiles/basemap), mirroring the maps page's BASEMAP TIER so
// these backdrops are rich and correct — land, water (oceans/lakes/rivers), the full
// landcover + protected-land (landuse) tint, GPU hillshade, roads, admin boundaries
// and place labels. It deliberately omits the heavy region OVERLAY (OSM trails/POIs/
// contours) — this is a backdrop for colourful GPS overlays, not the planning map.
//
// Two earlier bugs this fixes: (1) it pointed at the removed /api/tiles/master path
// (blank backdrop); (2) it filled the whole `water` source-layer with no `earth`
// land polygon underneath, so non-open-water features painted "water" across land.
// We now draw `earth` and filter the water fill to ocean/lake/water exactly like the
// maps page. Light + dark palettes follow the app theme.

const ATTRIBUTION =
  '<a href="https://protomaps.com">Protomaps</a> © <a href="https://openstreetmap.org">OpenStreetMap</a>';
const DEM_ATTRIBUTION = '<a href="https://mapterhorn.com/attribution">© Mapterhorn</a>';

const PALETTES = {
  light: {
    earth: "#f4f1ea", water: "#bcdce6",
    forest: "#cfe0bc", wood: "#d4e3c4", grass: "#e3edcf", farmland: "#eae2cb",
    scrub: "#dee6c0", rock: "#e7e1d2", glacier: "#eef3f5", wetland: "#d3e3da",
    sand: "#efe7cd", urban: "#e9e5dd", park: "#cfe6bf",
    roadMajor: "#ffffff", roadCasing: "#d9d3c4", roadMinor: "#efeae0",
    trail: "#6b5b47", trailCasing: "rgba(255,255,255,0.65)",
    boundary: "#8b8472", label: "#454545", labelHalo: "#f4f1ea",
    hsShadow: "#8a7a5c", hsHighlight: "#fffdf7", hsAccent: "#9a8a6a", hsExag: 0.32,
  },
  dark: {
    earth: "#15181d", water: "#0e141b",
    forest: "#18241a", wood: "#1b271d", grass: "#1b231a", farmland: "#20231a",
    scrub: "#1d2519", rock: "#26262a", glacier: "#28343f", wetland: "#16241f",
    sand: "#2a2820", urban: "#20242b", park: "#18281a",
    roadMajor: "#434956", roadCasing: "#262b33", roadMinor: "#2b313a",
    trail: "#9aa3b2", trailCasing: "rgba(0,0,0,0.4)",
    boundary: "#566072", label: "#c4cad4", labelHalo: "#0c0f13",
    hsShadow: "#000000", hsHighlight: "#3a4250", hsAccent: "#000000", hsExag: 0.3,
  },
};

// kind → fill colour, shared by landcover + landuse (keeps both in lock-step).
function landMatch(c) {
  return [
    "match", ["get", "kind"],
    "forest", c.forest, "wood", c.wood,
    "grassland", c.grass, "grass", c.grass, "meadow", c.grass,
    "farmland", c.farmland, "scrub", c.scrub,
    "barren", c.rock, "bare_rock", c.rock, "rock", c.rock,
    "glacier", c.glacier, "wetland", c.wetland,
    "sand", c.sand, "urban_area", c.urban,
    "park", c.park, "national_park", c.park, "nature_reserve", c.park,
    "recreation_ground", c.park,
    c.earth,
  ];
}

// Only natural / protected kinds are overzoomed (overzooming urban blocks looks bad).
const GREEN_KINDS = ["literal", [
  "forest", "wood", "national_park", "park", "nature_reserve",
  "meadow", "grass", "grassland", "scrub",
]];

export function buildBasemapStyle(theme = "light") {
  const c = PALETTES[theme] || PALETTES.light;
  const fill = landMatch(c);
  const basemapSrc = {
    type: "vector",
    // Caddy serves /api/tiles/basemap as one zoom-split source: z0-12 from the
    // static planet basemap, z13-15 from master_basemap_detail (built only for
    // DOWNLOADED regions).
    //
    // maxzoom is deliberately 12, not 15. A source's maxzoom tells MapLibre
    // "real tiles exist up to here, overzoom above it" — declaring 15 made it
    // request genuine z13/z14/z15 tiles, which 404 everywhere outside a
    // downloaded region (and everywhere at all when no region is downloaded).
    // MapLibre doesn't fall back to overzooming on a failed fetch, so the whole
    // backdrop went blank the moment you zoomed past z12 — reported as "the map
    // disappears instead of overzooming". Capping at 12 means the z12 tile is
    // scaled up indefinitely instead: blurrier, but continuous at every zoom.
    //
    // Tradeoff, deliberate: inside a downloaded region this backdrop no longer
    // picks up the crisper z13-15 roads/water/labels. That detail belongs to the
    // planning map (pages/maps, its own style/sources.js); this file is only the
    // backdrop behind GPS overlays — see the header comment — where "never blank"
    // beats "sharper in one region". If that detail is ever wanted here, the fix
    // is a second z12-capped fallback source under the detail layers, mirroring
    // the basemap_overview pattern below, not raising this number back to 15.
    tiles: ["/api/tiles/basemap/{z}/{x}/{y}.mvt"],
    minzoom: 0,
    maxzoom: 12,
    attribution: ATTRIBUTION,
  };

  return {
    version: 8,
    glyphs: "/api/fonts/{fontstack}/{range}.pbf",
    sources: {
      basemap: basemapSrc,
      // Overzoom helpers (same tiles, capped) so the z0-7 landcover and z0-9 landuse
      // tint carry up to high zoom everywhere — no new data fetched. Mirrors the maps
      // page (sources.js basemap_overview / basemap_landuse_overview).
      basemap_overview: { ...basemapSrc, maxzoom: 7 },
      basemap_landuse_overview: { ...basemapSrc, maxzoom: 9 },
      // Per-region OSM overlay (downloaded regions): the rich trail network (+ water,
      // landuse, etc.) the maps page shows. Only the `trails` source-layer is used
      // here. z9-15; empty (graceful) outside downloaded regions.
      overlay: {
        type: "vector",
        tiles: ["/api/tiles/master_overlay/{z}/{x}/{y}.mvt"],
        minzoom: 9,
        maxzoom: 15,
        attribution: '© <a href="https://openstreetmap.org">OpenStreetMap</a> contributors',
      },
      // Global z0-7 DEM overview → GPU hillshade everywhere (soft relief even with no
      // regional DEM downloaded). Terrarium-encoded webp, served by Caddy.
      dem_overview: {
        type: "raster-dem",
        tiles: ["/api/tiles/planet_dem_z7/{z}/{x}/{y}.webp"],
        tileSize: 512,
        minzoom: 0,
        maxzoom: 7,
        encoding: "terrarium",
        attribution: DEM_ATTRIBUTION,
      },
    },
    layers: [
      { id: "bg", type: "background", paint: { "background-color": c.earth } },
      // Land polygon — establishes "land" so the water fill only paints actual water.
      { id: "earth", source: "basemap", "source-layer": "earth", type: "fill", paint: { "fill-color": c.earth } },

      // GPU hillshade under the land cover + water so relief reads through the tint.
      {
        id: "hillshade",
        type: "hillshade",
        source: "dem_overview",
        paint: {
          "hillshade-exaggeration": ["interpolate", ["linear"], ["zoom"], 5, c.hsExag + 0.1, 10, c.hsExag, 14, c.hsExag - 0.1],
          "hillshade-illumination-direction": 315,
          "hillshade-illumination-anchor": "map",
          "hillshade-shadow-color": c.hsShadow,
          "hillshade-highlight-color": c.hsHighlight,
          "hillshade-accent-color": c.hsAccent,
        },
      },

      // Landcover (ESA-WorldCover): crisp z0-7 from basemap, then the overzoomed
      // wash carries the tint to high zoom everywhere.
      {
        id: "landcover", source: "basemap", "source-layer": "landcover", type: "fill",
        minzoom: 0, maxzoom: 8,
        paint: { "fill-color": fill, "fill-opacity": ["interpolate", ["linear"], ["zoom"], 0, 0.8, 6, 0.8, 8, 0] },
      },
      {
        id: "landcover_overview", source: "basemap_overview", "source-layer": "landcover", type: "fill",
        minzoom: 7,
        paint: { "fill-color": fill, "fill-opacity": ["interpolate", ["linear"], ["zoom"], 7, 0, 8.5, 0.5, 13, 0.5, 15, 0.42] },
      },

      // Protected / natural land (parks, national forests, wilderness) tint.
      {
        id: "landuse", source: "basemap", "source-layer": "landuse", type: "fill",
        minzoom: 5, filter: ["in", ["get", "kind"], GREEN_KINDS],
        paint: { "fill-color": fill, "fill-opacity": ["interpolate", ["linear"], ["zoom"], 5, 0, 7, 0.15, 10, 0.3, 14, 0.4] },
      },
      {
        id: "landuse_overview", source: "basemap_landuse_overview", "source-layer": "landuse", type: "fill",
        minzoom: 9, filter: ["in", ["get", "kind"], GREEN_KINDS],
        paint: { "fill-color": fill, "fill-opacity": ["interpolate", ["linear"], ["zoom"], 9.8, 0, 10.6, 0.3, 13, 0.3, 15, 0.2] },
      },

      // Rivers (coarse basemap lines) under the open-water fill.
      {
        id: "water_rivers", source: "basemap", "source-layer": "water", type: "line",
        filter: ["==", ["get", "kind"], "river"], minzoom: 7,
        paint: {
          "line-color": c.water,
          "line-width": ["interpolate", ["exponential", 1.6], ["zoom"], 7, 0.6, 10, 1.4, 16, 5],
          "line-opacity": ["interpolate", ["linear"], ["zoom"], 7, 0, 8, 1],
        },
      },
      // Open water (oceans, lakes) — filtered like the maps page so non-open-water
      // features never paint across land.
      {
        id: "water", source: "basemap", "source-layer": "water", type: "fill",
        filter: ["in", ["get", "kind"], ["literal", ["ocean", "lake", "water"]]],
        paint: { "fill-color": c.water },
      },

      // Roads.
      {
        id: "roads_minor", source: "basemap", "source-layer": "roads", type: "line",
        filter: ["in", ["get", "kind"], ["literal", ["minor_road", "other"]]], minzoom: 11,
        paint: { "line-color": c.roadMinor, "line-width": ["interpolate", ["exponential", 1.6], ["zoom"], 11, 0.4, 16, 3] },
      },
      {
        id: "roads_major_casing", source: "basemap", "source-layer": "roads", type: "line",
        filter: ["in", ["get", "kind"], ["literal", ["highway", "major_road", "medium_road"]]], minzoom: 6,
        paint: { "line-color": c.roadCasing, "line-width": ["interpolate", ["exponential", 1.6], ["zoom"], 6, 0.8, 16, 6] },
      },
      {
        id: "roads_major", source: "basemap", "source-layer": "roads", type: "line",
        filter: ["in", ["get", "kind"], ["literal", ["highway", "major_road", "medium_road"]]], minzoom: 6,
        paint: { "line-color": c.roadMajor, "line-width": ["interpolate", ["exponential", 1.6], ["zoom"], 6, 0.4, 16, 4] },
      },

      // Trails (footpaths / bridleways / cycleways) — the planet basemap emits these
      // in the `roads` layer as kind="path". Global (everywhere), z12+. Casing lifts
      // the dashed trail off the hillshade. (Region OSM rich trails are intentionally
      // not pulled into this backdrop.)
      {
        id: "trails_casing", source: "basemap", "source-layer": "roads", type: "line",
        minzoom: 12, filter: ["==", ["get", "kind"], "path"],
        layout: { "line-cap": "round", "line-join": "round" },
        paint: {
          "line-color": c.trailCasing,
          "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 12, 2.0, 14, 3.4, 16, 6],
          "line-opacity": ["interpolate", ["linear"], ["zoom"], 12, 0, 12.5, 1],
        },
      },
      {
        id: "trails_path", source: "basemap", "source-layer": "roads", type: "line",
        minzoom: 12, filter: ["==", ["get", "kind"], "path"],
        layout: { "line-cap": "round", "line-join": "round" },
        paint: {
          "line-color": c.trail,
          "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 12, 1.0, 14, 1.9, 16, 3.2],
          "line-dasharray": [2.2, 1.6],
          "line-opacity": ["interpolate", ["linear"], ["zoom"], 12, 0, 12.5, 0.95],
        },
      },

      // Rich OSM trail network from the region overlay (the same trails the maps page
      // shows in downloaded regions). Drawn over the global basemap-path fallback.
      {
        id: "osm_trails_casing", source: "overlay", "source-layer": "trails", type: "line",
        minzoom: 9,
        filter: ["in", ["get", "kind"], ["literal", ["path", "footway", "bridleway", "cycleway"]]],
        layout: { "line-cap": "round", "line-join": "round" },
        paint: {
          "line-color": c.trailCasing,
          "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 11, 1.6, 14, 3.2, 16, 5],
          "line-opacity": ["interpolate", ["linear"], ["zoom"], 10, 0, 11, 0.6, 16, 0.7],
        },
      },
      {
        id: "osm_trails_path", source: "overlay", "source-layer": "trails", type: "line",
        minzoom: 9,
        filter: ["in", ["get", "kind"], ["literal", ["path", "footway", "bridleway", "cycleway"]]],
        layout: { "line-cap": "round", "line-join": "round" },
        paint: {
          "line-color": c.trail,
          "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 10, 0.7, 13, 1.7, 16, 3.2],
          "line-dasharray": [3, 2],
          "line-opacity": ["interpolate", ["linear"], ["zoom"], 9.5, 0, 10, 0.6, 13, 0.92, 16, 1],
        },
      },

      // Admin boundaries (national + state) — dashed, subtle.
      {
        id: "boundaries_national", source: "basemap", "source-layer": "boundaries", type: "line",
        minzoom: 1, filter: ["any", ["==", ["get", "kind"], "country"], ["<=", ["get", "kind_detail"], 2]],
        paint: {
          "line-color": c.boundary, "line-dasharray": [6, 1.5, 1, 1.5, 1, 1.5],
          "line-width": ["interpolate", ["exponential", 1.2], ["zoom"], 2, 0.8, 6, 1.4, 12, 2.2],
          "line-opacity": ["interpolate", ["linear"], ["zoom"], 1, 0.4, 4, 0.7],
        },
      },
      {
        id: "boundaries_state", source: "basemap", "source-layer": "boundaries", type: "line",
        minzoom: 3, filter: ["any", ["==", ["get", "kind"], "region"], ["==", ["get", "kind_detail"], 4]],
        paint: {
          "line-color": c.boundary, "line-dasharray": [5, 1.5, 1, 1.5],
          "line-width": ["interpolate", ["exponential", 1.2], ["zoom"], 3, 0.5, 7, 1, 12, 1.6],
          "line-opacity": ["interpolate", ["linear"], ["zoom"], 3, 0.3, 5, 0.6],
        },
      },

      // Place labels.
      {
        id: "labels_place", source: "basemap", "source-layer": "places", type: "symbol",
        filter: ["in", ["get", "kind"], ["literal", ["locality", "city", "town", "village", "region", "country"]]],
        layout: {
          "text-field": ["get", "name"],
          "text-font": ["Noto Sans Regular"],
          "text-size": ["interpolate", ["linear"], ["zoom"], 3, 10, 12, 15],
          "text-max-width": 8,
        },
        paint: { "text-color": c.label, "text-halo-color": c.labelHalo, "text-halo-width": 1.5 },
      },
    ],
  };
}
