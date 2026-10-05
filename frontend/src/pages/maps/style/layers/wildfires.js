// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { COLORS } from "../palette";

// ── Live wildfire + smoke overlays (opt-in) ───────────────────────────────────
// Data is fed by useWildfires from the backend proxy (NIFC incident points +
// perimeters, NOAA HMS smoke plumes) into the three geojson sources declared in
// sources.js. Both layer groups default to hidden — the Layers panel toggles
// them, and the panel only enables the toggles once `wildfire_enabled` is on.

const W = COLORS.wildfire;

// Incident-dot radius graded by fire size. sqrt(acres) keeps the visual AREA
// roughly proportional to the burned area: 10 ac → small dot, 100k ac → big.
const SIZE_BY_ACRES = ["interpolate", ["linear"],
  ["sqrt", ["max", ["coalesce", ["get", "acres"], 0], 1]],
  1, 3, 10, 4.5, 100, 8, 320, 13];
// …then eased up as you zoom in so dots don't crowd the overview.
const zoomScaled = (factorLow, factorHigh) => ["interpolate", ["linear"], ["zoom"],
  4, ["*", factorLow, SIZE_BY_ACRES],
  10, ["*", factorHigh, SIZE_BY_ACRES]];

// Smoke plumes sit UNDER the place/road labels so geography stays readable
// through the wash; density (NOAA analyst-classified) grades the opacity.
export function buildSmoke() {
  return [
    {
      id: "smoke_fill",
      source: "smoke_plumes",
      type: "fill",
      paint: {
        "fill-color": W.smoke,
        "fill-opacity": ["match", ["get", "density"],
          "heavy", 0.32, "medium", 0.20, 0.11],
      },
    },
    {
      id: "smoke_outline",
      source: "smoke_plumes",
      type: "line",
      paint: {
        "line-color": W.smoke,
        "line-width": 1,
        "line-dasharray": [3, 2.5],
        "line-opacity": 0.4,
      },
    },
  ];
}

// Fires render on the very top of the stack — an active fire outranks
// everything else on the map.
export function buildWildfires() {
  return [
    // Burned/burning footprint.
    {
      id: "wildfire_perimeter_fill",
      source: "wildfire_perimeters",
      type: "fill",
      paint: {
        "fill-color": W.perimeterFill,
        "fill-opacity": 0.18,
      },
    },
    {
      id: "wildfire_perimeter_line",
      source: "wildfire_perimeters",
      type: "line",
      layout: { "line-join": "round" },
      paint: {
        "line-color": W.perimeter,
        "line-width": ["interpolate", ["linear"], ["zoom"], 6, 1.2, 12, 2.4],
        "line-opacity": 0.85,
      },
    },

    // Incident point: soft glow sized by acreage + a solid core. The glow
    // doubles as the click target for the fire detail panel.
    {
      id: "wildfire_points_glow",
      source: "wildfire_points",
      type: "circle",
      paint: {
        "circle-color": W.glow,
        "circle-radius": zoomScaled(1.6, 2.6),
        "circle-opacity": 0.3,
        "circle-blur": 0.6,
      },
    },
    {
      id: "wildfire_points_core",
      source: "wildfire_points",
      type: "circle",
      paint: {
        "circle-color": W.core,
        "circle-radius": zoomScaled(0.7, 1.0),
        "circle-stroke-color": "#FFFFFF",
        "circle-stroke-width": 1.4,
      },
    },

    // Name + "1,234 ac · 45% contained" (preformatted server-side as `label`).
    // Sorted so the biggest fires win label collisions at overview zooms.
    {
      id: "wildfire_labels",
      source: "wildfire_points",
      type: "symbol",
      minzoom: 5,
      layout: {
        "text-field": ["get", "label"],
        "text-font": ["Noto Sans Regular"],
        "text-size": ["interpolate", ["linear"], ["zoom"], 5, 10, 10, 12],
        "text-anchor": "top",
        "text-offset": [0, 1.1],
        "text-max-width": 12,
        "symbol-sort-key": ["-", 0, ["coalesce", ["get", "acres"], 0]],
      },
      paint: {
        "text-color": W.label,
        "text-halo-color": COLORS.background,
        "text-halo-width": 1.5,
      },
    },
  ];
}
