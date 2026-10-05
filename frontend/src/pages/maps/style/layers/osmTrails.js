// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { COLORS } from "../palette";

// ── OSM rich trails (downloaded regions) ──────────────────────────────────────
// Rendered from the trail_builder's per-region overlay `trails` tileset, which
// carries real OSM attributes the basemap lacks: `use`, surface (paved),
// trail_visibility, bridge, sac_scale, name, oneway. The per-feature `min_zoom`
// baked into the tiles gates visibility; `minzoom` is a cheap tile-fetch floor.
// The long-distance ROUTE layers (CDT/PCT/AT…) live in longTrails.js.

const OSM_SRC = { source: "overlay", "source-layer": "trails" };

// Path-type trails (everything except vehicle tracks, steps and routes).
const PATH_KINDS = ["literal", ["path", "footway", "bridleway", "cycleway"]];
const isPath = ["in", ["get", "kind"], PATH_KINDS];

// Trail hue = highest permitted use (Gaia convention, `use` baked into the
// overlay tiles): foot = dark topo ink, horse = vibrant green, bike = vibrant
// red, moto = purple. Kept legible by THIN dashed lines over the soft yellow
// halo below (osm_trails_casing).
const TRAIL_INK = "#43301d";
const USE_COLOR = ["match", ["get", "use"],
  "horse", COLORS.trails.horse,
  "bike", COLORS.trails.bike,
  "moto", COLORS.trails.moto,
  TRAIL_INK];
// Tiles carry the long/named trail skeleton from z9 (trail_classify grades
// min_zoom 9) and there is NO data below z9.0 — so the ramp is a tight snap
// right at 9: trails arrive at ~75% ink the moment their tiles load instead
// of ghosting in around z10.
const TRAIL_FADE = ["interpolate", ["linear"], ["zoom"],
  8.9, 0, 9.05, 0.75, 11, 0.9, 13, 0.95, 16, 1];

const isFaint = ["in", ["get", "trail_visibility"], ["literal", ["bad", "horrible", "no"]]];
const isDifficult = ["in", ["get", "sac_scale"],
  ["literal", ["demanding_mountain_hiking", "alpine_hiking",
               "demanding_alpine_hiking", "difficult_alpine_hiking"]]];

export function buildOsmTrails() {
  return [
    // ── Yellow halo under path trails (not steps/faint) ───────────────────────
    // Gaia-style: a soft, slightly blurred pale-yellow band under every dashed
    // trail line so it floats off hillshade/landcover in any hue. Starts with
    // the trails at z10 (they fade in from z9.5) so the network reads while
    // still zoomed out. Kept narrow so nearby trails don't merge into blobs.
    {
      id: "osm_trails_casing",
      ...OSM_SRC,
      type: "line",
      minzoom: 9,
      filter: ["all",
        ["in", ["get", "kind"], ["literal", ["path", "footway", "bridleway", "cycleway"]]],
        ["!", isFaint]],
      layout: { "line-cap": "round", "line-join": "round" },
      paint: {
        "line-color": COLORS.trails.halo,
        // Wider than the trail line with a generous blur: the band's edges
        // dissolve into the terrain (soft falloff) instead of reading as a
        // second cased line.
        "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 9, 3.2, 13, 5.6, 16, 10],
        "line-blur": ["interpolate", ["linear"], ["zoom"], 9, 2.0, 16, 3.6],
        "line-opacity": ["interpolate", ["linear"], ["zoom"], 8.9, 0, 9.05, 0.6, 13, 0.85, 16, 0.9],
      },
    },

    // ── Bridge deck edges ─────────────────────────────────────────────────────
    {
      id: "osm_trails_bridge",
      ...OSM_SRC,
      type: "line",
      minzoom: 14,
      filter: ["==", ["get", "bridge"], 1],
      layout: { "line-cap": "butt", "line-join": "round" },
      paint: {
        "line-color": COLORS.trails.bridge,
        "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 14, 4.0, 16, 6.5],
        "line-opacity": 0.9,
      },
    },

    // ── Unpaved path — USGS fine dash, use-coloured, grows from z9 ────────────
    {
      id: "osm_trails_unpaved",
      ...OSM_SRC,
      type: "line",
      minzoom: 9,
      filter: ["all", isPath, ["!=", ["get", "paved"], 1], ["!", isFaint]],
      layout: { "line-cap": "round", "line-join": "round" },
      paint: {
        "line-color": USE_COLOR,
        "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 9, 1.0, 13, 1.9, 16, 3.2],
        "line-dasharray": [3, 2],
        "line-opacity": TRAIL_FADE,
      },
    },

    // ── Paved path — solid, use-coloured, grows from z9 ───────────────────────
    {
      id: "osm_trails_paved",
      ...OSM_SRC,
      type: "line",
      minzoom: 9,
      filter: ["all", isPath, ["==", ["get", "paved"], 1], ["!", isFaint]],
      layout: { "line-cap": "round", "line-join": "round" },
      paint: {
        "line-color": USE_COLOR,
        "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 9, 1.1, 13, 2.0, 16, 3.4],
        "line-opacity": TRAIL_FADE,
      },
    },

    // ── Faint / abandoned tread — sparse dots, ghosted ────────────────────────
    {
      id: "osm_trails_faint",
      ...OSM_SRC,
      type: "line",
      minzoom: 12,
      filter: ["all",
        ["in", ["get", "kind"], ["literal", ["path", "footway", "bridleway", "cycleway"]]],
        isFaint],
      layout: { "line-cap": "round", "line-join": "round" },
      paint: {
        "line-color": TRAIL_INK,
        "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 12, 0.9, 16, 2.6],
        "line-dasharray": [1, 3],
        "line-opacity": 0.5,
      },
    },

    // ── Technical / exposed alpine overlay (SAC T4+) ──────────────────────────
    {
      id: "osm_trails_difficult",
      ...OSM_SRC,
      type: "line",
      minzoom: 14,
      filter: ["all", isPath, isDifficult],
      layout: { "line-cap": "butt", "line-join": "round" },
      paint: {
        "line-color": COLORS.trails.difficult,
        "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 14, 1.0, 16, 2.2],
        "line-dasharray": [1, 3],
        "line-opacity": 0.7,
      },
    },

    // ── Steps — rungs ─────────────────────────────────────────────────────────
    {
      id: "osm_trails_steps",
      ...OSM_SRC,
      type: "line",
      minzoom: 14,
      filter: ["==", ["get", "kind"], "steps"],
      layout: { "line-cap": "butt", "line-join": "round" },
      paint: {
        "line-color": COLORS.trails.steps,
        "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 14, 3.0, 16, 5.0],
        "line-dasharray": [0.4, 0.5],
      },
    },

    // ── One-way direction arrows (subtle, high zoom) ──────────────────────────
    {
      id: "osm_trails_oneway",
      ...OSM_SRC,
      type: "symbol",
      minzoom: 14,
      filter: ["all", ["==", ["get", "oneway"], 1], isPath],
      layout: {
        "text-field": "▸",
        "text-font": ["Noto Sans Regular"],
        "text-size": ["interpolate", ["linear"], ["zoom"], 14, 10, 16, 14],
        "symbol-placement": "line",
        "symbol-spacing": 90,
        "text-keep-upright": false,
        "text-allow-overlap": true,
        "text-padding": 2,
      },
      paint: {
        "text-color": COLORS.trails.label,
        "text-halo-color": COLORS.background,
        "text-halo-width": 1,
        "text-opacity": 0.55,
      },
    },

    // ── Trail names (OSM has far better coverage than the basemap) ────────────
    {
      id: "osm_trails_labels",
      ...OSM_SRC,
      type: "symbol",
      minzoom: 12,
      filter: ["all", ["has", "name"], ["!=", ["get", "route"], 1]],
      layout: {
        "text-field": ["get", "name"],
        "text-font": ["Noto Sans Italic"],
        "text-size": ["interpolate", ["linear"], ["zoom"], 12, 10.5, 16, 13.5],
        "symbol-placement": "line",
        "symbol-spacing": 300,
        "text-max-angle": 40,
        "text-padding": 4,
      },
      paint: {
        "text-color": COLORS.trails.label,
        "text-halo-color": COLORS.background,
        "text-halo-width": 1.5,
        "text-opacity": ["interpolate", ["linear"], ["zoom"], 12, 0, 12.5, 1],
      },
    },
  ];
}
