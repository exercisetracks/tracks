// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { COLORS } from "../palette";

// Water = basemap polygons + coarse rivers, PLUS the OSM waterway network
// (water_osm, downloaded regions) styled like a USGS quad sheet: perennial lines
// solid, intermittent dash-dot, sized stream → river. Per-feature min_zoom in the
// tiles gates visibility; `minzoom` here is just the tile-fetch floor.

const W_SRC = { source: "overlay", "source-layer": "water" };
const isInter = ["==", ["get", "intermittent"], 1];

export function buildWater() {
  return [
    // ── Basemap coarse rivers (large, low-zoom) ───────────────────────────────
    {
      id: "water_rivers",
      source: "basemap",
      "source-layer": "water",
      type: "line",
      filter: ["==", ["get", "kind"], "river"],
      minzoom: 7,
      paint: {
        "line-color": COLORS.water,
        "line-width": ["interpolate", ["exponential", 1.6], ["zoom"], 7, 0.6, 10, 1.4, 16, 5],
        "line-opacity": ["interpolate", ["linear"], ["zoom"], 7, 0, 8, 1],
      },
    },

    // ── OSM rivers — perennial (solid) ────────────────────────────────────────
    {
      id: "water_osm_rivers",
      ...W_SRC,
      type: "line",
      minzoom: 9,
      filter: ["all", ["==", ["get", "kind"], "river"], ["!", isInter]],
      layout: { "line-cap": "round", "line-join": "round" },
      paint: {
        "line-color": COLORS.streamLine,
        "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 9, 0.9, 12, 1.6, 16, 4.0],
      },
    },
    // ── OSM canals — perennial (solid, medium) ────────────────────────────────
    {
      id: "water_osm_canals",
      ...W_SRC,
      type: "line",
      minzoom: 10,
      filter: ["all", ["in", ["get", "kind"], ["literal", ["canal", "tidal_channel"]]], ["!", isInter]],
      layout: { "line-cap": "round", "line-join": "round" },
      paint: {
        "line-color": COLORS.streamLine,
        "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 10, 0.8, 13, 1.4, 16, 3.0],
      },
    },
    // ── OSM streams — perennial (solid, thin) ─────────────────────────────────
    {
      id: "water_osm_streams",
      ...W_SRC,
      type: "line",
      minzoom: 11,
      filter: ["all", ["==", ["get", "kind"], "stream"], ["!", isInter]],
      layout: { "line-cap": "round", "line-join": "round" },
      paint: {
        "line-color": COLORS.streamLine,
        "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 11, 0.6, 13, 1.1, 16, 2.4],
        "line-opacity": ["interpolate", ["linear"], ["zoom"], 11, 0.7, 13, 1],
      },
    },
    // ── OSM ditches / drains — thin solid ─────────────────────────────────────
    {
      id: "water_osm_ditches",
      ...W_SRC,
      type: "line",
      minzoom: 13,
      filter: ["in", ["get", "kind"], ["literal", ["ditch", "drain"]]],
      layout: { "line-cap": "round", "line-join": "round" },
      paint: {
        "line-color": COLORS.streamLine,
        "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 13, 0.5, 16, 1.4],
        "line-opacity": 0.75,
      },
    },
    // ── OSM intermittent (streams/rivers/canals) — USGS dash-dot ──────────────
    {
      id: "water_osm_intermittent",
      ...W_SRC,
      type: "line",
      minzoom: 11,
      filter: ["all",
        ["in", ["get", "kind"], ["literal", ["river", "stream", "canal", "tidal_channel"]]],
        isInter],
      layout: { "line-cap": "butt", "line-join": "round" },
      paint: {
        "line-color": COLORS.streamLine,
        "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 11, 0.7, 13, 1.2, 16, 2.6],
        "line-dasharray": [3, 1.5, 0.5, 1.5],
        "line-opacity": 0.9,
      },
    },

    // ── Basemap water polygons (oceans, lakes, ponds) ─────────────────────────
    // Drawn ABOVE the waterway lines so a river/stream flowing into a lake or
    // pond is covered by the open water — no stray line spearing across the
    // surface from the river mouth.
    {
      id: "water_polygons",
      source: "basemap",
      "source-layer": "water",
      type: "fill",
      filter: ["in", ["get", "kind"], ["literal", ["ocean", "lake", "water"]]],
      paint: { "fill-color": COLORS.water, "fill-opacity": 1 },
    },

    // ── Stream / river names (italic blue, like a quad sheet) ─────────────────
    {
      id: "water_osm_labels",
      ...W_SRC,
      type: "symbol",
      // Watercourse names appear a step earlier (z10) than before (z11) so a river
      // is named as soon as its line is legible.
      minzoom: 10,
      // Only label genuinely named watercourses (the tiles carry name="" for
      // unnamed ones, which would otherwise place empty labels and steal slots).
      filter: ["all",
        ["!=", ["coalesce", ["get", "name"], ""], ""],
        ["in", ["get", "kind"], ["literal", ["river", "stream", "canal", "tidal_channel"]]]],
      layout: {
        "text-field": ["get", "name"],
        "text-font": ["Noto Sans Italic"],
        "text-size": ["interpolate", ["linear"], ["zoom"], 10, 9.5, 13, 11.5, 16, 13],
        "symbol-placement": "line",
        "symbol-spacing": 260,
        "text-max-angle": 40,
        "text-letter-spacing": 0.03,
        "text-padding": 3,
        "symbol-avoid-edges": false,
      },
      paint: {
        // Deep blue ink + a thicker cream halo so creek/river names read
        // confidently over green terrain and contour lines.
        "text-color": COLORS.streamLabel,
        "text-halo-color": COLORS.background,
        "text-halo-width": 2.0,
        "text-opacity": ["interpolate", ["linear"], ["zoom"], 10, 0, 10.6, 1],
      },
    },
  ];
}
