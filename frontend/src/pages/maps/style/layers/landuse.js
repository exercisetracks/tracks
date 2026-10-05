// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Basemap landuse polygons — the visible green "Parks & Landuse" wash was REMOVED.
//
// Those two fills (`landuse` + the overzoomed `landuse_overview`) sat ABOVE water
// and hillshade in the layer order, so their translucent green dimmed lake blues
// and muddied the relief across whole national forests — toggling them off made
// the map read "perfect". The broad terrain green now comes solely from the
// `landcover` layer, and NAMED public lands from the `publiclands` overlay (with
// its own boundary band + labels), so nothing legible is lost.
//
// The base `landuse` polygon layer is KEPT but fully transparent (fill-opacity 0)
// for ONE reason: usePointInfo's click-to-identify reads the land type at the
// clicked point from THIS layer via queryRenderedFeatures — and opacity-0 fills
// stay queryable (only `visibility:none` would drop them from the query index).
// So clicking outside a downloaded region still reports "National Forest / Park /
// Grassland / …". No `fill-color` is needed (it never paints); classifyLand reads
// the feature's own `kind`. The overzoom layer is gone entirely (never queried).
export function buildLanduse() {
  return [
    {
      id: "landuse",
      source: "basemap",
      "source-layer": "landuse",
      type: "fill",
      minzoom: 5,
      maxzoom: 17,
      // Invisible: present purely so usePointInfo can query land type at a click.
      paint: { "fill-opacity": 0 },
    },
  ];
}
