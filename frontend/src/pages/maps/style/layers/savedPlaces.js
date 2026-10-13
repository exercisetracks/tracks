// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The user's saved places — rendered from the `saved_places` geojson source
// (fed by useMapItems). Each feature names its own image: the place's symbol
// already tinted to its colour, or a pin in its colour (utils/placeIcons.js),
// because the atlas sprites are not SDF and icon-color cannot reach them.
//
// A symbol centres on its spot; a pin stands on its point, so it is anchored
// at the bottom — a pin centred on its coordinate points at ground a pin-height
// away. A saved place is never culled for clutter: somebody put it there. Its
// name may be. Grouped under the "My Tracks" toggle with the tracks.
const SRC = { source: "saved_places" };

export function buildSavedPlaces() {
  return [
    // The selected place's ring, under its mark: centred on a symbol, raised
    // onto a pin's head. Two layers, because circle-translate takes no data
    // expression — and MapLibre answers an invalid style by never loading,
    // which shows as a blank map rather than an error.
    ...[["saved_places_selected", false, [0, 0]], ["saved_places_selected_pin", true, [0, -16]]].map(([id, pin, translate]) => ({
      id,
      ...SRC,
      type: "circle",
      filter: ["==", ["get", "pin"], pin],
      layout: { "visibility": "none" },
      paint: {
        "circle-radius": 17,
        "circle-color": "#ffffff",
        "circle-blur": 0.4,
        "circle-opacity": ["case", ["boolean", ["feature-state", "selected"], false], 0.75,
          ["boolean", ["feature-state", "hover"], false], 0.4, 0],
        "circle-translate": translate,
      },
    })),
    {
      id: "saved_places_icon",
      ...SRC,
      type: "symbol",
      layout: {
        "visibility": "none",
        "icon-image": ["get", "image"],
        "icon-anchor": ["case", ["get", "pin"], "bottom", "center"],
        "icon-allow-overlap": true,
        "icon-ignore-placement": true,
      },
    },
    {
      id: "saved_places_label",
      ...SRC,
      type: "symbol",
      layout: {
        "visibility": "none",
        // Named explicitly: a font stack the glyph endpoint does not serve
        // renders no text at all.
        "text-font": ["Noto Sans Regular"],
        "text-field": ["get", "name"],
        "text-size": 11,
        "text-anchor": "top",
        "text-offset": ["case", ["get", "pin"], ["literal", [0, 0.25]], ["literal", [0, 1.1]]],
        "text-optional": true,
      },
      paint: {
        "text-color": "#1f2937",
        "text-halo-color": "#ffffff",
        "text-halo-width": 1.4,
      },
    },
  ];
}
