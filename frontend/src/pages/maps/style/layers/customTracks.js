// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// User custom tracks (courses) — rendered from the `custom_tracks` geojson source
// (fed by useCustomTracks). Patterned on the long-trail layers: a wide invisible
// hit line for easy click/hover, a feature-state hover glow, a white casing, and
// the per-track coloured line (`["get","color"]`). The selected track is widened
// via the `selected` feature-state. All start hidden and are grouped under the
// "Custom Tracks" toggle in useLayerToggles.
const SRC = { source: "custom_tracks" };

const LINE_COLOR = ["coalesce", ["get", "color"], "#2563eb"];
const LINE_WIDTH = ["interpolate", ["exponential", 1.5], ["zoom"],
  5, ["case", ["boolean", ["feature-state", "selected"], false], 4, 2],
  12, ["case", ["boolean", ["feature-state", "selected"], false], 6, 3.5],
  16, ["case", ["boolean", ["feature-state", "selected"], false], 8, 5]];

export function buildCustomTracks() {
  return [
    // Wide transparent hit target — makes thin tracks easy to click/hover.
    {
      id: "custom_tracks_hit",
      ...SRC,
      type: "line",
      layout: { "line-cap": "round", "line-join": "round", "visibility": "none" },
      paint: {
        "line-color": "#000000",
        "line-opacity": 0,
        "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 5, 12, 12, 20, 16, 26],
      },
    },
    // Hover / selected glow (feature-state driven).
    {
      id: "custom_tracks_hover",
      ...SRC,
      type: "line",
      layout: { "line-cap": "round", "line-join": "round", "visibility": "none" },
      paint: {
        // Amber when chosen for a merge, otherwise the white hover/selected glow.
        "line-color": ["case", ["boolean", ["feature-state", "merge"], false], "#f59e0b", "#ffffff"],
        "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 6, 6, 12, 12, 16, 18],
        "line-opacity": ["case",
          ["boolean", ["feature-state", "merge"], false], 0.85,
          ["boolean", ["feature-state", "selected"], false], 0.7,
          ["boolean", ["feature-state", "hover"], false], 0.45,
          0],
        "line-blur": 1.0,
      },
    },
    // White casing under the coloured line for contrast on any basemap.
    {
      id: "custom_tracks_casing",
      ...SRC,
      type: "line",
      layout: { "line-cap": "round", "line-join": "round", "visibility": "none" },
      paint: {
        "line-color": "#ffffff",
        "line-opacity": 0.85,
        "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 5, 4, 12, 7, 16, 9],
      },
    },
    // The coloured track line (per-feature colour). line-dasharray can't be a data
    // expression in MapLibre, so external (device-discovered) tracks get their own
    // dashed layer; managed tracks render solid. Both share the width/colour logic.
    {
      id: "custom_tracks_line",
      ...SRC,
      type: "line",
      filter: ["!=", ["get", "is_external"], true],
      layout: { "line-cap": "round", "line-join": "round", "visibility": "none" },
      paint: { "line-color": LINE_COLOR, "line-width": LINE_WIDTH, "line-opacity": 0.96 },
    },
    {
      id: "custom_tracks_line_external",
      ...SRC,
      type: "line",
      filter: ["==", ["get", "is_external"], true],
      layout: { "line-cap": "round", "line-join": "round", "visibility": "none" },
      paint: {
        "line-color": LINE_COLOR,
        "line-width": LINE_WIDTH,
        "line-opacity": 0.9,
        "line-dasharray": [2, 1.4],
      },
    },
    // Direction-of-travel chevrons spaced along each track (rotate with the line).
    {
      id: "custom_tracks_arrows",
      ...SRC,
      type: "symbol",
      layout: {
        "visibility": "none",
        "symbol-placement": "line",
        "symbol-spacing": ["interpolate", ["linear"], ["zoom"], 8, 60, 14, 95],
        "text-field": ">",
        "text-font": ["Noto Sans Regular"],
        "text-size": ["interpolate", ["linear"], ["zoom"], 8, 17, 14, 23],
        "text-keep-upright": false,
        "text-rotation-alignment": "map",
        "text-pitch-alignment": "viewport",
        "text-allow-overlap": true,
        "text-ignore-placement": true,
        "text-padding": 2,
      },
      paint: {
        // White chevrons with a dark halo read clearly on any track colour.
        "text-color": "#ffffff",
        "text-halo-color": "rgba(0,0,0,0.6)",
        "text-halo-width": 1.8,
      },
    },
  ];
}
