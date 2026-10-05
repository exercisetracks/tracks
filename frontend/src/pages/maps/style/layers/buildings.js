// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { COLORS } from "../palette";

export function buildBuildings() {
  return [
    {
      id: "buildings",
      source: "basemap",
      "source-layer": "buildings",
      type: "fill",
      minzoom: 14.4,
      paint: {
        "fill-color": COLORS.buildings,
        "fill-outline-color": COLORS.buildingsOutline,
        // USGS quad buildings are solid dark blocks. Fade in over a quarter of a
        // zoom level starting at z14.4 (14.4 → 14.65) so they don't pop in.
        "fill-opacity": ["interpolate", ["linear"], ["zoom"], 14.4, 0, 14.65, 0.9, 17, 0.95],
      },
    },
  ];
}
