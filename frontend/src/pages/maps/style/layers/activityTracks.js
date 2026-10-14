// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Past-activity tracks on the map page, from the GeoJSON useActivityTracks
// feeds. The routes themselves are drawn by the dashboard's heatmap glow
// (HeatmapGlowLayer, added by the hook), where overlapping routes deepen the
// way they do on the dashboard; flat translucent lines piled into one colour
// over anywhere often run. These layers are what is left for the style: a
// wide invisible hit line, so an activity can still be clicked (→ the activity
// panel), and the hover / selected halo. Both start hidden; grouped under the
// "Activity Tracks" toggle.
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
