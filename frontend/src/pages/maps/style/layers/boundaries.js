// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { COLORS } from "../palette";

// USGS political-boundary symbology: black lines with a class-specific dash
// signature, heaviest for national and finest for city/township. The basemap
// `boundaries` source-layer carries `kind` (country / region / county) and
// `kind_detail` = admin_level (2 national, 4 state, 6 county, 7-8 civil/city).
// One layer per class so each gets its own dash + weight (USGS draws them
// distinctly), replacing the old single generic dashed line.

const SRC = { source: "basemap", "source-layer": "boundaries" };

const isNational = ["any", ["==", ["get", "kind"], "country"], ["<=", ["get", "kind_detail"], 2]];
const isState    = ["any", ["==", ["get", "kind"], "region"], ["==", ["get", "kind_detail"], 4]];
const isCounty   = ["any", ["==", ["get", "kind"], "county"], ["==", ["get", "kind_detail"], 6]];
const isCity     = [">=", ["get", "kind_detail"], 7];

export function buildBoundaries() {
  return [
    // ── National (heavy dash-dot-dot) ─────────────────────────────────────────
    {
      id: "boundaries_national",
      ...SRC,
      type: "line",
      minzoom: 1,
      filter: isNational,
      layout: { "line-join": "round" },
      paint: {
        "line-color": COLORS.boundary.national,
        "line-width": ["interpolate", ["exponential", 1.2], ["zoom"], 2, 0.9, 6, 1.6, 12, 2.4],
        "line-dasharray": [6, 1.5, 1, 1.5, 1, 1.5],
        "line-opacity": ["interpolate", ["linear"], ["zoom"], 1, 0.5, 4, 0.85],
      },
    },
    // ── State / territorial (dash-dot) ────────────────────────────────────────
    {
      id: "boundaries_state",
      ...SRC,
      type: "line",
      minzoom: 3,
      filter: isState,
      layout: { "line-join": "round" },
      paint: {
        "line-color": COLORS.boundary.state,
        "line-width": ["interpolate", ["exponential", 1.2], ["zoom"], 3, 0.6, 7, 1.1, 12, 1.8],
        "line-dasharray": [5, 1.5, 1, 1.5],
        "line-opacity": ["interpolate", ["linear"], ["zoom"], 3, 0.4, 5, 0.75],
      },
    },
    // ── County (dash-dot-dot, finer) ──────────────────────────────────────────
    {
      id: "boundaries_county",
      ...SRC,
      type: "line",
      minzoom: 6,
      filter: isCounty,
      layout: { "line-join": "round" },
      paint: {
        "line-color": COLORS.boundary.county,
        "line-width": ["interpolate", ["exponential", 1.2], ["zoom"], 6, 0.5, 9, 0.9, 14, 1.4],
        "line-dasharray": [4, 1.5, 0.8, 1.5, 0.8, 1.5],
        "line-opacity": ["interpolate", ["linear"], ["zoom"], 6, 0, 7, 0.7],
      },
    },
    // ── Civil township / incorporated city (fine dash) ────────────────────────
    {
      id: "boundaries_city",
      ...SRC,
      type: "line",
      minzoom: 9,
      filter: isCity,
      layout: { "line-join": "round" },
      paint: {
        "line-color": COLORS.boundary.city,
        "line-width": ["interpolate", ["exponential", 1.2], ["zoom"], 9, 0.5, 12, 0.9, 15, 1.3],
        "line-dasharray": [2, 2],
        "line-opacity": ["interpolate", ["linear"], ["zoom"], 9, 0, 10, 0.7],
      },
    },
  ];
}
