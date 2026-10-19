// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { COLORS } from "../palette";
// Basemap landuse polygons — the visible green "Parks & Landuse" wash was REMOVED.
//
// Those two fills (`landuse` + the overzoomed `landuse_overview`) sat ABOVE water
// and hillshade in the layer order, so their translucent green dimmed lake blues
// and muddied the relief across whole national forests — toggling them off made
// the map read "perfect". The broad terrain green now comes solely from the
// `landcover` layer, and NAMED public lands from the `publiclands` overlay (with
// its own boundary band + labels), so nothing legible is lost.
//
// The base `landuse` polygon layer is KEPT but fully transparent (fill-opacity 0)
// for ONE reason: usePointInfo's click-to-identify reads the land type at the
// clicked point from THIS layer via queryRenderedFeatures — and opacity-0 fills
// stay queryable (only `visibility:none` would drop them from the query index).
// So clicking outside a downloaded region still reports "National Forest / Park /
// Grassland / …". No `fill-color` is needed (it never paints); classifyLand reads
// the feature's own `kind`. The overzoom layer is gone entirely (never queried).
// Parks, gardens, playing fields, golf courses and lawns, drawn green, as on
// most street maps: a city park used to read only as a place with no roads.
// Only the small, city-scale kinds -- the forest/national-park wash removed
// above stays removed, and so does farmland.
//
// Two layers, because the two sizes want different places in the stack:
// - City green sits above the land washes (landcover, vegetation, public
//   lands), which otherwise dim it into the grey-green around it, and under
//   the relief and the roads. It is opaque, and a city park's pond would be
//   covered by it -- a small price at these sizes.
// - Nature reserves can run to hundreds of square kilometres and hold whole
//   lakes, so they go under the water, lighter, as a tint rather than a fill.
const PARK_FILLS = [
  // [kinds, colour, opacity]
  [["park", "garden", "playground", "dog_park", "village_green", "recreation_ground"], COLORS.landuse.park, 1],
  [["golf_course", "pitch"], COLORS.landuse.golf_course, 1],
  [["grass", "meadow"], COLORS.landuse.meadow, 0.85],
  [["cemetery"], COLORS.landuse.cemetery, 1],
];
export const PARK_KINDS = PARK_FILLS.flatMap(([kinds]) => kinds);

function byKind(index) {
  const out = ["match", ["get", "kind"]];
  for (const row of PARK_FILLS) out.push(row[0], row[index]);
  out.push(index === 1 ? "rgba(0,0,0,0)" : 0);
  return out;
}

/** City-scale green, above the land washes. */
export function buildParks() {
  return [
    {
      id: "landuse_parks",
      source: "basemap",
      "source-layer": "landuse",
      type: "fill",
      minzoom: 9,
      filter: ["in", ["get", "kind"], ["literal", PARK_KINDS]],
      paint: {
        "fill-color": byKind(1),
        // In over a zoom, so a city's parks arrive as the city does.
        "fill-opacity": ["interpolate", ["linear"], ["zoom"], 9, 0, 10.5, byKind(2)],
      },
    },
  ];
}

/** Nature reserves: a light green tint, under the water. */
export function buildReserves() {
  return [
    {
      id: "landuse_reserves",
      source: "basemap",
      "source-layer": "landuse",
      type: "fill",
      minzoom: 8,
      filter: ["==", ["get", "kind"], "nature_reserve"],
      paint: {
        "fill-color": COLORS.landuse.nature_reserve,
        "fill-opacity": ["interpolate", ["linear"], ["zoom"], 8, 0, 9.5, 0.6],
      },
    },
  ];
}

export function buildLanduse() {
  return [
    {
      id: "landuse",
      source: "basemap",
      "source-layer": "landuse",
      type: "fill",
      minzoom: 5,
      maxzoom: 17,
      // Invisible: present purely so usePointInfo can query land type at a click.
      paint: { "fill-opacity": 0 },
    },
  ];
}
