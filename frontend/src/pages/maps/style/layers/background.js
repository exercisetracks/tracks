// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
export function buildBackground() {
  return [
    {
      id: "background",
      type: "background",
      paint: { "background-color": "#F8F6F0" },
    },
    {
      id: "earth",
      source: "basemap",
      "source-layer": "earth",
      type: "fill",
      paint: {
        "fill-color": "#F8F6F0",
      },
    },
  ];
}

