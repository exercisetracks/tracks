// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { COLORS } from "../palette";

// USGS 7.5-minute quad contour styling.
// Index contours appear at every 5th interval (500 ft for 100 ft base).
// Labels show only on index contours, placed along the line, in feet.

export function buildContours() {
  return [
    // ── Intermediate contours (thin) ──────────────────────────────────────────
    {
      id: "contours_intermediate",
      source: "contours",
      "source-layer": "contours",
      type: "line",
      minzoom: 9,
      filter: ["==", ["get", "index"], false],
      paint: {
        "line-color": COLORS.labels.contour,
        "line-width": [
          "interpolate", ["exponential", 1.5], ["zoom"],
           9, 0.3,
          11, 0.4,
          13, 0.6,
          15, 0.8,
        ],
        "line-opacity": [
          "interpolate", ["linear"], ["zoom"],
          9, 0.32, 11, 0.5, 13, 0.58,
        ],
      },
    },

    // ── Index contours (thick) ────────────────────────────────────────────────
    {
      id: "contours_index",
      source: "contours",
      "source-layer": "contours",
      type: "line",
      minzoom: 9,
      filter: ["==", ["get", "index"], true],
      paint: {
        "line-color": COLORS.labels.contour,
        "line-width": [
          "interpolate", ["exponential", 1.5], ["zoom"],
           9, 0.7,
          11, 1.0,
          13, 1.4,
          15, 1.8,
        ],
        "line-opacity": [
          "interpolate", ["linear"], ["zoom"],
          9, 0.5, 11, 0.67, 13, 0.78,
        ],
      },
    },

    // ── Elevation labels (index contours only) ─────────────────────────────────
    // USGS-style: the bold (index) contour carries its elevation in feet, repeated
    // along the line so you can read the height of any index contour at a glance.
    // The label sits in the contour brown with a cream halo that "breaks" the line
    // behind the number, mimicking how a quad sheet interrupts the contour to
    // print the value.
    {
      id: "contours_labels",
      source: "contours",
      "source-layer": "contours",
      type: "symbol",
      minzoom: 10,
      filter: ["==", ["get", "index"], true],
      layout: {
        "symbol-placement": "line",
        // Repeat the value often enough that an index contour is labelled wherever
        // it runs; tighten as you zoom in.
        "symbol-spacing": [
          "interpolate", ["linear"], ["zoom"],
          10, 320, 13, 260, 15, 220,
        ],
        "text-field": ["to-string", ["get", "ele_ft"]],
        "text-font": ["Noto Sans Regular"],
        "text-size": [
          "interpolate", ["linear"], ["zoom"],
          10, 9, 13, 11, 15, 12.5,
        ],
        "text-letter-spacing": 0.04,
        // Contours are curvy; a low max-angle drops the 4-digit number entirely
        // (it can't fit on the bend). A high angle lets the label follow the
        // contour the way a quad sheet prints it. (Verified: 30°→0 labels,
        // 110°→~30.) No symbol-avoid-edges — it suppressed labels on these
        // unpadded contour tiles.
        "text-max-angle": 110,
        "text-padding": 2,
      },
      paint: {
        "text-color": COLORS.labels.contour,
        "text-halo-color": COLORS.background,
        "text-halo-width": 2,
        "text-halo-blur": 0.4,
        "text-opacity": [
          "interpolate", ["linear"], ["zoom"],
          10, 0, 10.5, 0.95,
        ],
      },
    },
  ];
}
