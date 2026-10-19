// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { COLORS } from "../palette";

// ── Basemap-fallback trails (whole planet, outside downloaded regions) ─────────
// The Protomaps basemap emits trails in the `roads` layer as kind="path",
// sub-classified by kind_detail. No access/surface tags, so hue is approximated
// from kind_detail and everything dirt-dashes. Rendered under the richer OSM
// trails (osmTrails.js), which overlay these once a region is downloaded. The
// long-distance route layers live in longTrails.js.

// Smooth fade-in at a layer's first zoom so trails don't pop into existence.
// NOTE: a "zoom" expression must sit at the TOP LEVEL of an interpolate/step —
// it cannot be nested inside another operator, so any opacity ceiling is baked
// into the output stops here.
const fadeIn = (z, max = 1) => ["interpolate", ["linear"], ["zoom"], z, 0, z + 0.5, max];

const TRAIL_FILTER = ["==", ["get", "kind"], "path"];

/**
 * How a trail name fits along its line, loosening as you zoom in, so that
 * close in every named trail carries its name. A name is placed only where a
 * stretch of the line is long and straight enough for it, and switchbacking
 * singletrack -- most mountain-bike trails, and ski runs -- rarely has a
 * stretch bending less than 40 degrees, so those went unnamed at every zoom.
 * By z15 a bend of up to 70 degrees is accepted, names repeat more often and
 * pack closer, which is what gets a short, twisting trail its name.
 */
export const TRAIL_NAME_FIT = {
  "symbol-spacing": ["interpolate", ["linear"], ["zoom"], 12, 300, 15, 200, 17, 140],
  "text-max-angle": ["interpolate", ["linear"], ["zoom"], 12, 40, 15, 70],
  "text-padding": ["interpolate", ["linear"], ["zoom"], 12, 4, 15, 1],
};
const isDetail = (...vals) => ["in", ["get", "kind_detail"], ["literal", vals]];

export function buildTrails() {
  return [
    // Gaia-style soft yellow halo under the path network — a slightly blurred
    // pale band that lifts the dashed lines off hillshade/landcover.
    {
      id: "trails_casing",
      source: "basemap",
      "source-layer": "roads",
      type: "line",
      minzoom: 12,
      filter: ["all", TRAIL_FILTER, isDetail("path", "footway", "bridleway", "cycleway")],
      layout: { "line-cap": "round", "line-join": "round" },
      paint: {
        "line-color": COLORS.trails.halo,
        // Matches the OSM-overlay casing: wider band, generous blur, so the
        // glow fades off into terrain rather than edging like a cased line.
        "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 12, 3.8, 14, 5.8, 16, 10],
        "line-blur": ["interpolate", ["linear"], ["zoom"], 12, 2.0, 16, 3.6],
        "line-opacity": fadeIn(12, 0.85),
      },
    },

    // Singletrack hiking trail (kind_detail = path) — the hero line, charcoal.
    {
      id: "trails_path",
      source: "basemap",
      "source-layer": "roads",
      type: "line",
      minzoom: 12,
      filter: ["all", TRAIL_FILTER, isDetail("path")],
      layout: { "line-cap": "round", "line-join": "round" },
      paint: {
        "line-color": COLORS.trails.foot,
        "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 12, 1.3, 14, 2.2, 16, 3.6],
        "line-dasharray": [2.2, 1.6],
        "line-opacity": fadeIn(12),
      },
    },

    // Developed footway.
    {
      id: "trails_footway",
      source: "basemap",
      "source-layer": "roads",
      type: "line",
      minzoom: 13,
      filter: ["all", TRAIL_FILTER, isDetail("footway")],
      layout: { "line-cap": "round", "line-join": "round" },
      paint: {
        "line-color": COLORS.trails.foot,
        "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 13, 0.9, 16, 2.2],
        "line-dasharray": [1.5, 1.5],
        "line-opacity": fadeIn(13),
      },
    },

    // Bridleway / horse trail — Gaia green (kind_detail approximates use here;
    // the richer OSM overlay colours by real access tags in downloaded areas).
    {
      id: "trails_bridleway",
      source: "basemap",
      "source-layer": "roads",
      type: "line",
      minzoom: 13,
      filter: ["all", TRAIL_FILTER, isDetail("bridleway")],
      layout: { "line-cap": "round", "line-join": "round" },
      paint: {
        "line-color": COLORS.trails.horse,
        "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 13, 1.1, 16, 2.6],
        "line-dasharray": [2.2, 1.6],
        "line-opacity": fadeIn(13),
      },
    },

    // Cycleway — Gaia red.
    {
      id: "trails_cycleway",
      source: "basemap",
      "source-layer": "roads",
      type: "line",
      minzoom: 13,
      filter: ["all", TRAIL_FILTER, isDetail("cycleway")],
      layout: { "line-cap": "round", "line-join": "round" },
      paint: {
        "line-color": COLORS.trails.bike,
        "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 13, 1.1, 16, 2.6],
        "line-dasharray": [3, 1.5],
        "line-opacity": fadeIn(13),
      },
    },

    // Steps — thick short "rungs".
    {
      id: "trails_steps",
      source: "basemap",
      "source-layer": "roads",
      type: "line",
      minzoom: 14,
      filter: ["all", TRAIL_FILTER, isDetail("steps")],
      layout: { "line-cap": "butt", "line-join": "round" },
      paint: {
        "line-color": COLORS.trails.steps,
        "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 14, 3.0, 16, 5.0],
        "line-dasharray": [0.4, 0.5],
        "line-opacity": fadeIn(14),
      },
    },

    // Catch-all so nothing tagged kind=path goes unrendered.
    {
      id: "trails_other",
      source: "basemap",
      "source-layer": "roads",
      type: "line",
      minzoom: 13,
      filter: ["all", TRAIL_FILTER,
        ["!", isDetail("track", "path", "footway", "bridleway", "cycleway", "steps")]],
      layout: { "line-cap": "round", "line-join": "round" },
      paint: {
        "line-color": COLORS.trails.other,
        "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 13, 0.8, 16, 2.0],
        "line-dasharray": [2, 1.5],
        "line-opacity": fadeIn(13, 0.85),
      },
    },

    // Names (fall back to ref such as "FR 253"), italic, along the line.
    {
      id: "trails_labels",
      source: "basemap",
      "source-layer": "roads",
      type: "symbol",
      minzoom: 13,
      filter: ["all", TRAIL_FILTER, ["any", ["has", "name"], ["has", "ref"]]],
      layout: {
        "text-field": ["coalesce", ["get", "name"], ["get", "ref"]],
        "text-font": ["Noto Sans Italic"],
        "text-size": ["interpolate", ["linear"], ["zoom"], 13, 10.5, 16, 13.5],
        "symbol-placement": "line",
        ...TRAIL_NAME_FIT,
        "text-letter-spacing": 0.02,
      },
      paint: {
        "text-color": COLORS.trails.label,
        "text-halo-color": COLORS.background,
        "text-halo-width": 1.5,
        "text-opacity": ["interpolate", ["linear"], ["zoom"], 13, 0, 13.5, 1],
      },
    },
  ];
}

/**
 * The basemap trail layers' filters with every path wholly inside [areas] left
 * out: inside a downloaded area the OSM overlay draws the same trails, richer
 * (osmTrails.js), and the basemap's copy under it was a second line along the
 * same ground -- arriving first, since basemap tiles load before the
 * overlay's, then covered, its name competing with the overlay's for the same
 * line. [areas] is MultiPolygon coordinates (the union of the downloaded
 * areas, utils/areaOutline); empty gives the filters back unmasked.
 *
 * A path crossing an area's edge is not wholly inside, so it keeps its
 * basemap line; the overlay ends at the edge, and a short doubled stretch
 * there is better than a gap. The phone applies the same rule
 * (BasemapTrailMask.kt), from the same areas.
 */
export function maskedTrailFilters(areas) {
  const out = {};
  for (const layer of buildTrails()) {
    out[layer.id] = areas?.length
      ? ["all", layer.filter, ["!", ["within", { type: "MultiPolygon", coordinates: areas }]]]
      : layer.filter;
  }
  return out;
}
