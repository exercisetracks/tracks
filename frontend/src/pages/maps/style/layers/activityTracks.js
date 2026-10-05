// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Past-activity tracks (the map-page "activity heatmap") rendered as queryable
// GeoJSON lines fed by useActivityTracks. Drawn thick in the user's theme accent
// (set at runtime by the hook — these literals are just a fallback) with a low
// opacity so overlapping routes still darken into a heatmap-like density. A wide
// invisible hit line makes them easy to click (→ point-info "nearby" selection),
// and direction chevrons show the way of travel. All start hidden; grouped under
// the "Activity Tracks" toggle.
const SRC = { source: "activity_tracks" };
const FALLBACK = "#10b981";

export function buildActivityTracks() {
  return [
    {
      id: "activity_tracks_hit",
      ...SRC,
      type: "line",
      layout: { "line-cap": "round", "line-join": "round", "visibility": "none" },
      paint: {
        "line-color": "#000000",
        "line-opacity": 0,
        "line-width": ["interpolate", ["linear"], ["zoom"], 8, 9, 14, 16],
      },
    },
    // Hover halo (feature-state driven) — lit when hovering the line on the map OR
    // a row in the point-info "Nearby activities" list. Sits under the line so the
    // white glow reads around it.
    {
      id: "activity_tracks_hover",
      ...SRC,
      type: "line",
      layout: { "line-cap": "round", "line-join": "round", "visibility": "none" },
      paint: {
        // Amber when selected or chosen for a merge, otherwise the white hover halo.
        "line-color": ["case",
          ["any", ["boolean", ["feature-state", "merge"], false], ["boolean", ["feature-state", "selected"], false]], "#f59e0b",
          "#ffffff"],
        "line-width": ["interpolate", ["exponential", 1.4], ["zoom"], 6, 6, 12, 11, 16, 15],
        "line-opacity": ["case",
          ["any", ["boolean", ["feature-state", "merge"], false], ["boolean", ["feature-state", "selected"], false]], 0.85,
          ["boolean", ["feature-state", "hover"], false], 0.75, 0],
        "line-blur": 1.0,
      },
    },
    {
      id: "activity_tracks_line",
      ...SRC,
      type: "line",
      layout: { "line-cap": "round", "line-join": "round", "visibility": "none" },
      paint: {
        "line-color": FALLBACK,
        // Brighten the hovered track so it stands out from the rest.
        "line-opacity": ["case", ["boolean", ["feature-state", "hover"], false], 1, 0.55],
        // ~20% thinner than before (2.5/3.5/5/6 → 2/2.8/4/4.8).
        "line-width": ["interpolate", ["exponential", 1.4], ["zoom"], 6, 2, 10, 2.8, 14, 4, 16, 4.8],
      },
    },
    {
      id: "activity_tracks_arrows",
      ...SRC,
      type: "symbol",
      minzoom: 11,
      layout: {
        "visibility": "none",
        "symbol-placement": "line",
        "symbol-spacing": ["interpolate", ["linear"], ["zoom"], 11, 75, 15, 115],
        "text-field": ">",
        "text-font": ["Noto Sans Regular"],
        "text-size": ["interpolate", ["linear"], ["zoom"], 11, 16, 15, 21],
        "text-keep-upright": false,
        "text-rotation-alignment": "map",
        "text-pitch-alignment": "viewport",
        "text-allow-overlap": true,
        "text-ignore-placement": true,
      },
      paint: {
        // White chevrons + dark halo for contrast on the accent-coloured line.
        "text-color": "#ffffff",
        "text-halo-color": "rgba(0,0,0,0.6)",
        "text-halo-width": 1.8,
      },
    },
    // Single-activity preview line (when turning an activity into a track). Not in
    // the toggle group — always present, empty until useActivityPreview feeds it.
    {
      id: "activity_preview_line",
      source: "activity_preview",
      type: "line",
      layout: { "line-cap": "round", "line-join": "round" },
      paint: {
        "line-color": FALLBACK,
        "line-width": ["interpolate", ["exponential", 1.4], ["zoom"], 6, 3, 12, 5, 16, 7],
        "line-opacity": 0.95,
        "line-dasharray": [1.6, 1],
      },
    },
  ];
}
