// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { COLORS } from "../palette";

// USGS PAVED road classes, derived from the basemap roads `kind` + `kind_detail`:
//   primary highway   — motorway / trunk / primary (solid red, cased; divided
//                        highways get a median gap via a thin centre line)
//   secondary highway — secondary (red, lighter)
//   light-duty road   — tertiary / unclassified / residential (grey-cased cream)
// Unpaved drivable roads (service double-dash + 4×4 track) are drawn by tracks.js;
// foot/bike/horse paths by trails.js. Order: tunnels → surface (casing then fill,
// light-duty under secondary under primary) → bridges.

const SRC = { source: "basemap", "source-layer": "roads" };
// OSM overlay (downloaded regions) carries paved light-duty roads (kind=lightduty)
// from z9-10, so they fade in early like the 4×4 network instead of popping in at
// z12 (the zoom the basemap first carries them). See trail_builder.classify.
const OSM = { source: "overlay", "source-layer": "trails" };
const isLightDutyOsm = ["==", ["get", "kind"], "lightduty"];
const KD = ["get", "kind_detail"];

const isPrimary = ["any",
  ["in", KD, ["literal", ["motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link"]]],
  ["==", ["get", "kind"], "highway"],   // low-zoom fallback before kind_detail is precise
];
const isSecondary = ["in", KD, ["literal", ["secondary", "secondary_link"]]];
const isLightDuty = ["in", KD, ["literal",
  ["tertiary", "tertiary_link", "unclassified", "residential", "living_street", "road"]]];
const isDivided = ["in", KD, ["literal", ["motorway", "motorway_link", "trunk", "trunk_link"]]];

export function buildRoads() {
  return [
    // ── Tunnels (dashed casing) ───────────────────────────────────────────────
    {
      id: "roads_tunnels_casing",
      ...SRC,
      type: "line",
      filter: ["==", ["get", "is_tunnel"], true],
      minzoom: 7,
      paint: {
        "line-color": COLORS.roads.tunnelDash,
        "line-width": ["interpolate", ["exponential", 1.6], ["zoom"], 7, 0, 8, 2, 16, 10],
        "line-opacity": ["interpolate", ["linear"], ["zoom"], 7, 0, 8, 1],
        "line-dasharray": [2, 1.5],
      },
    },
    {
      id: "roads_tunnels_fill",
      ...SRC,
      type: "line",
      filter: ["==", ["get", "is_tunnel"], true],
      minzoom: 7,
      paint: {
        "line-color": COLORS.roads.lightFill,
        "line-width": ["interpolate", ["exponential", 1.6], ["zoom"], 7, 0, 8, 1, 16, 6],
        "line-opacity": ["interpolate", ["linear"], ["zoom"], 7, 0, 8, 1],
      },
    },

    // ── Light-duty roads (tertiary/residential) — topo yellow, white-cased ────
    // Bolder at low zoom so the yellow road network reads at overview.
    {
      id: "roads_light_casing",
      ...SRC,
      type: "line",
      filter: isLightDuty,
      // Basemap light-duty is the GLOBAL fallback: the basemap only carries these
      // small roads from ~z12, so fade them in there (instead of popping). Inside
      // downloaded regions the OSM overlay layers below lead with an early fade.
      minzoom: 9,
      paint: {
        "line-color": COLORS.roads.lightCasing,
        "line-width": ["interpolate", ["exponential", 1.6], ["zoom"], 9.5, 2.8, 11, 3.4, 13, 4.2, 16, 6.4],
        // Fade in over a quarter zoom (12 → 12.25), matching buildings.js, so the
        // city street network doesn't pop in.
        "line-opacity": ["interpolate", ["linear"], ["zoom"], 12, 0, 12.25, 1],
      },
    },
    {
      id: "roads_light_fill",
      ...SRC,
      type: "line",
      filter: isLightDuty,
      minzoom: 9,
      paint: {
        "line-color": COLORS.roads.lightFill,
        "line-width": ["interpolate", ["exponential", 1.6], ["zoom"], 9.5, 1.8, 11, 2.2, 13, 2.8, 16, 4.2],
        "line-opacity": ["interpolate", ["linear"], ["zoom"], 12, 0, 12.25, 1],
      },
    },

    // OSM overlay light-duty (downloaded regions) — same topo-yellow road, but
    // present from z9-10 so it FADES in early like the 4×4 tracks/service roads
    // (which lead at ~z10) rather than popping in with the basemap at z12. Drawn
    // over the basemap fallback; identical colour/width so they read as one road.
    {
      id: "roads_osm_light_casing",
      ...OSM,
      type: "line",
      filter: isLightDutyOsm,
      minzoom: 9,
      paint: {
        "line-color": COLORS.roads.lightCasing,
        "line-width": ["interpolate", ["exponential", 1.6], ["zoom"], 9.5, 2.8, 11, 3.4, 13, 4.2, 16, 6.4],
        "line-opacity": ["interpolate", ["linear"], ["zoom"], 9.5, 0, 9.75, 1],
      },
    },
    {
      id: "roads_osm_light_fill",
      ...OSM,
      type: "line",
      filter: isLightDutyOsm,
      minzoom: 9,
      paint: {
        "line-color": COLORS.roads.lightFill,
        "line-width": ["interpolate", ["exponential", 1.6], ["zoom"], 9.5, 1.8, 11, 2.2, 13, 2.8, 16, 4.2],
        "line-opacity": ["interpolate", ["linear"], ["zoom"], 9.5, 0, 9.75, 1],
      },
    },

    // (Service / unpaved roads moved to the decoupled tracks.js module.)

    // ── Secondary highway (red) ───────────────────────────────────────────────
    {
      id: "roads_secondary_casing",
      ...SRC,
      type: "line",
      filter: isSecondary,
      minzoom: 8,
      paint: {
        "line-color": COLORS.roads.secondaryCasing,
        "line-width": ["interpolate", ["exponential", 1.6], ["zoom"], 8, 0.6, 10, 2.3, 13, 3.2, 16, 7],
      },
    },
    {
      id: "roads_secondary_fill",
      ...SRC,
      type: "line",
      filter: isSecondary,
      minzoom: 8,
      paint: {
        "line-color": COLORS.roads.secondaryFill,
        "line-width": ["interpolate", ["exponential", 1.6], ["zoom"], 8, 0.3, 10, 1.4, 13, 2.0, 16, 4.5],
      },
    },

    // ── Primary highway (red, cased) ──────────────────────────────────────────
    {
      id: "roads_primary_casing",
      ...SRC,
      type: "line",
      filter: isPrimary,
      minzoom: 3,
      paint: {
        "line-color": COLORS.roads.highwayCasing,
        "line-width": ["interpolate", ["exponential", 1.6], ["zoom"], 3, 0, 5, 2.0, 10, 3.6, 13, 5, 16, 11],
      },
    },
    {
      id: "roads_primary_fill",
      ...SRC,
      type: "line",
      filter: isPrimary,
      minzoom: 3,
      paint: {
        "line-color": COLORS.roads.highwayFill,
        "line-width": ["interpolate", ["exponential", 1.6], ["zoom"], 3, 0, 5, 0.9, 10, 2.0, 13, 3, 16, 7],
      },
    },
    // Divided-highway median: a thin casing-coloured centre line at high zoom so
    // motorways/trunks read as the USGS "highway with median strip" double line.
    {
      id: "roads_primary_median",
      ...SRC,
      type: "line",
      filter: isDivided,
      minzoom: 13,
      paint: {
        "line-color": COLORS.roads.highwayCasing,
        "line-width": ["interpolate", ["exponential", 1.6], ["zoom"], 13, 0.4, 16, 1.2],
        "line-opacity": ["interpolate", ["linear"], ["zoom"], 13, 0, 13.25, 0.9],
      },
    },

    // ── Rail ──────────────────────────────────────────────────────────────────
    // Basemap rail kept as a faint base; the rich USGS crosstie rendering comes
    // from the infrastructure module (master_infra) where available.
    {
      id: "roads_rail",
      ...SRC,
      type: "line",
      filter: ["==", ["get", "kind"], "rail"],
      minzoom: 3,
      paint: {
        "line-color": COLORS.roads.rail,
        "line-width": ["interpolate", ["exponential", 1.6], ["zoom"], 3, 0, 6, 0.4, 16, 1.4],
        "line-dasharray": [2, 2],
        "line-opacity": 0.7,
      },
    },

    // ── Bridges ───────────────────────────────────────────────────────────────
    {
      id: "roads_bridges_casing",
      ...SRC,
      type: "line",
      filter: ["==", ["get", "is_bridge"], true],
      minzoom: 12,
      paint: {
        "line-color": COLORS.roads.highwayCasing,
        // Fade in smoothly over a quarter zoom (12 → 12.25) instead of growing
        // from zero width, so bridge decks don't pop in at the city zoom.
        "line-width": ["interpolate", ["exponential", 1.6], ["zoom"], 12, 2, 16, 8],
        "line-opacity": ["interpolate", ["linear"], ["zoom"], 12, 0, 12.25, 1],
      },
    },
    {
      id: "roads_bridges_fill",
      ...SRC,
      type: "line",
      filter: ["==", ["get", "is_bridge"], true],
      minzoom: 12,
      paint: {
        "line-color": [
          "match", ["get", "kind"],
          "highway", COLORS.roads.highwayFill,
          COLORS.roads.secondaryFill,
        ],
        "line-width": ["interpolate", ["exponential", 1.6], ["zoom"], 12, 1, 16, 5],
        "line-opacity": ["interpolate", ["linear"], ["zoom"], 12, 0, 12.25, 1],
      },
    },
  ];
}
