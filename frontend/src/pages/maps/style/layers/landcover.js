// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { COLORS } from "../palette";

// Global landcover (ESA-WorldCover-derived) is embedded in the basemap tiles but
// the `landcover` source-layer only carries data at z0-7. So we render it twice:
//
//   • landcover        — crisp per-zoom tiles from the `basemap` source, z0-7,
//                        the strong low-zoom regional cover (fades out by z8).
//   • landcover_overview — the SAME cover from the maxzoom-7 `basemap_overview`
//                        source, which MapLibre overzooms up to z15, so unforested
//                        open country keeps a faint tint instead of going blank
//                        white above z7. No extra data is pulled (see sources.js).
//
// Shared kind→colour map so both layers stay in lock-step.
const FILL_COLOR = [
  "match", ["get", "kind"],
  "forest", COLORS.landcover.forest,
  "wood", COLORS.landcover.wood,
  "grassland", COLORS.landcover.grassland,
  "grass", COLORS.landcover.grass,
  "farmland", COLORS.landcover.farmland,
  "scrub", COLORS.landcover.scrub,
  "barren", COLORS.landcover.barren,
  "glacier", COLORS.landcover.glacier,
  "urban_area", COLORS.landcover.urban_area,
  "wetland", COLORS.landcover.wetland,
  "sand", COLORS.landcover.sand,
  "rock", COLORS.landcover.rock,
  COLORS.earth,
];

export function buildLandcover() {
  return [
    {
      id: "landcover",
      source: "basemap",
      "source-layer": "landcover",
      type: "fill",
      minzoom: 0,
      maxzoom: 8,
      paint: {
        "fill-color": FILL_COLOR,
        "fill-opacity": ["interpolate", ["linear"], ["zoom"],
          0, 0.85,
          6, 0.85,
          8, 0,
        ],
      },
    },
    // Overzoomed wash: picks up exactly where the crisp layer fades out (z7-8) and
    // carries the coarse regional cover to z15. Kept gentle — it's overzoomed z7
    // data, so it reads as a soft tint under the hillshade/contours/overlays rather
    // than hard blocks. Eased back slightly at the top end where region overlays
    // (vegetation/public-lands) take over the detail. Sits below `landuse`, so
    // OSM-tagged polygons still refine it.
    {
      id: "landcover_overview",
      source: "basemap_overview",
      "source-layer": "landcover",
      type: "fill",
      minzoom: 7,
      paint: {
        "fill-color": FILL_COLOR,
        "fill-opacity": ["interpolate", ["linear"], ["zoom"],
          7, 0,
          8.5, 0.55,
          13, 0.55,
          15, 0.45,
        ],
      },
    },
  ];
}
