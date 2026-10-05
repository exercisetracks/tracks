// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { COLORS } from "../palette";

// USGS infrastructure lines from the OSM infra_builder (`infra` layer):
//   railroads  — black line + crossties (rail_tie sprite placed along the line),
//                sidings/yards thinner, tram/subway lighter
//   power      — transmission line + pylon dots; minor lines thin
//   pipeline   — dashed line + repeated "Pipeline" label
//   dam / weir — heavy dark line
//   levee      — line + perpendicular hatch ticks (levee_tick sprite)
// Only present inside downloaded regions.

const SRC = { source: "overlay", "source-layer": "infra" };
const KIND = ["get", "kind"];

export function buildInfrastructure() {
  return [
    // ── Power transmission lines (under rail) ─────────────────────────────────
    // Minor distribution line — fine DOTTED grey (round-capped dots), clearly
    // lighter than the solid transmission line and free of pylon towers.
    {
      id: "infra_power_minor",
      ...SRC,
      type: "line",
      minzoom: 12,
      filter: ["==", KIND, "power_minor"],
      layout: { "line-cap": "round" },
      paint: {
        "line-color": COLORS.infra.powerMinor,
        "line-width": ["interpolate", ["linear"], ["zoom"], 12, 0.5, 16, 1.1],
        "line-dasharray": [0.1, 2.2],
        "line-opacity": 0.7,
      },
    },
    // Transmission line — solid, carries the pylon towers below.
    {
      id: "infra_power_line",
      ...SRC,
      type: "line",
      minzoom: 9,
      filter: ["==", KIND, "power_line"],
      paint: {
        "line-color": COLORS.infra.power,
        "line-width": ["interpolate", ["linear"], ["zoom"], 9, 0.4, 13, 0.9, 16, 1.4],
        "line-opacity": 0.8,
      },
    },
    {
      id: "infra_power_pylons",
      ...SRC,
      type: "symbol",
      minzoom: 12,
      filter: ["==", KIND, "power_line"],
      layout: {
        "icon-image": "pylon",
        "icon-size": ["interpolate", ["linear"], ["zoom"], 12, 0.5, 16, 0.85],
        "symbol-placement": "line",
        "symbol-spacing": ["interpolate", ["linear"], ["zoom"], 12, 70, 16, 110],
        "icon-rotation-alignment": "viewport",
        "icon-allow-overlap": true,
        "icon-ignore-placement": true,
      },
      paint: { "icon-opacity": 0.85 },
    },

    // ── Pipelines — a solid line strung with hollow "beads" (USGS pipeline) ────
    {
      id: "infra_pipeline",
      ...SRC,
      type: "line",
      minzoom: 11,
      filter: ["==", KIND, "pipeline"],
      paint: {
        "line-color": COLORS.infra.pipeline,
        "line-width": ["interpolate", ["linear"], ["zoom"], 11, 0.6, 16, 1.6],
        "line-opacity": 0.85,
      },
    },
    {
      id: "infra_pipeline_beads",
      ...SRC,
      type: "symbol",
      minzoom: 12,
      filter: ["==", KIND, "pipeline"],
      layout: {
        "icon-image": "pipeline_marker",
        "icon-size": ["interpolate", ["linear"], ["zoom"], 12, 0.4, 16, 0.7],
        "symbol-placement": "line",
        "symbol-spacing": ["interpolate", ["linear"], ["zoom"], 12, 22, 16, 34],
        "icon-allow-overlap": true,
        "icon-ignore-placement": true,
      },
      paint: { "icon-opacity": 0.9 },
    },
    {
      id: "infra_pipeline_label",
      ...SRC,
      type: "symbol",
      minzoom: 13,
      filter: ["==", KIND, "pipeline"],
      layout: {
        "text-field": "Pipeline",
        "text-font": ["Noto Sans Italic"],
        "text-size": 10,
        "symbol-placement": "line",
        "symbol-spacing": 400,
        "text-letter-spacing": 0.05,
      },
      paint: {
        "text-color": COLORS.infra.label,
        "text-halo-color": COLORS.background,
        "text-halo-width": 1.5,
      },
    },

    // ── Dams / weirs ──────────────────────────────────────────────────────────
    {
      id: "infra_dam",
      ...SRC,
      type: "line",
      minzoom: 12,
      filter: ["in", KIND, ["literal", ["dam", "weir"]]],
      layout: { "line-cap": "butt" },
      paint: {
        "line-color": COLORS.infra.dam,
        "line-width": ["interpolate", ["linear"], ["zoom"],
          12, ["case", ["==", KIND, "dam"], 2.5, 1.5],
          16, ["case", ["==", KIND, "dam"], 7, 4]],
      },
    },

    // ── Levees (line + perpendicular hatch ticks) ─────────────────────────────
    {
      id: "infra_levee",
      ...SRC,
      type: "line",
      minzoom: 12,
      filter: ["==", KIND, "levee"],
      paint: {
        "line-color": COLORS.infra.levee,
        "line-width": ["interpolate", ["linear"], ["zoom"], 12, 0.6, 16, 1.6],
        "line-opacity": 0.85,
      },
    },
    {
      id: "infra_levee_ticks",
      ...SRC,
      type: "symbol",
      minzoom: 13,
      filter: ["==", KIND, "levee"],
      layout: {
        "icon-image": "levee_tick",
        "icon-size": ["interpolate", ["linear"], ["zoom"], 13, 0.5, 16, 0.8],
        "symbol-placement": "line",
        "symbol-spacing": 18,
        "icon-allow-overlap": true,
        "icon-ignore-placement": true,
      },
    },

    // ── Railroads (casing → line → crossties) ─────────────────────────────────
    {
      id: "infra_rail_minor",
      ...SRC,
      type: "line",
      minzoom: 11,
      filter: ["==", KIND, "rail_minor"],
      paint: {
        "line-color": COLORS.infra.railMinor,
        "line-width": ["interpolate", ["linear"], ["zoom"], 11, 0.5, 16, 1.6],
        "line-dasharray": [3, 2],
      },
    },
    // Solid white casing under the full double-track width so the ladder lifts
    // cleanly off terrain/landcover.
    {
      id: "infra_rail_casing",
      ...SRC,
      type: "line",
      minzoom: 9,
      filter: ["in", KIND, ["literal", ["rail", "rail_service"]]],
      layout: { "line-cap": "round", "line-join": "round" },
      paint: {
        "line-color": COLORS.infra.railCasing,
        "line-width": ["interpolate", ["linear"], ["zoom"],
          9, 1.4, 13, 3.4, 16, ["case", ["==", KIND, "rail_service"], 5.0, 7.0]],
      },
    },
    // Two parallel rails. `line-gap-width` opens a centred gap so a single line
    // renders as the two outer rails; the gap collapses to 0 at low zoom so it
    // reads as one hairline far out, then splits into a true double track.
    {
      id: "infra_rail_line",
      ...SRC,
      type: "line",
      minzoom: 9,
      filter: ["in", KIND, ["literal", ["rail", "rail_service"]]],
      paint: {
        "line-color": COLORS.infra.rail,
        "line-width": ["interpolate", ["linear"], ["zoom"],
          9, 0.5, 13, 0.9, 16, ["case", ["==", KIND, "rail_service"], 1.0, 1.3]],
        "line-gap-width": ["interpolate", ["linear"], ["zoom"],
          11, 0, 12.5, 1.0, 16, ["case", ["==", KIND, "rail_service"], 2.4, 3.4]],
      },
    },
    // Crossties — perpendicular bars (tall sprite, map-aligned) spanning both
    // rails at a regular spacing, completing the ladder.
    {
      id: "infra_rail_ties",
      ...SRC,
      type: "symbol",
      minzoom: 12,
      filter: ["in", KIND, ["literal", ["rail", "rail_service"]]],
      layout: {
        "icon-image": "rail_tie",
        "icon-size": ["interpolate", ["linear"], ["zoom"], 12, 0.32, 14, 0.46, 16, 0.62],
        "symbol-placement": "line",
        "symbol-spacing": ["interpolate", ["linear"], ["zoom"], 12, 8, 16, 13],
        "icon-allow-overlap": true,
        "icon-ignore-placement": true,
        "icon-rotation-alignment": "map",
      },
      paint: { "icon-opacity": 0.95 },
    },
  ];
}
