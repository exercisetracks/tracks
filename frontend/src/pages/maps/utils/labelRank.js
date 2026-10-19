// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The world-aligned label grid. It ranked the POI GeoJSON source here until the
// places became tiles ranked on the server (backend app.services.poi_tiles, a
// port of what follows); usePublicLandLabelRank still claims cells with it.
//
// How the grid ranks, as the server now does it: The DB's per-feature min_zoom grades importance, but in a
// town every shop shares the same grade, so gating on it alone still stacks
// dozens of names at high zoom. This assigns each feature the zoom at which it
// actually gets to draw its icon (`icon_zoom`) and its name (`label_zoom`):
// features claim cells in a WORLD-ALIGNED grid sized to a typical icon/label
// footprint per zoom, in importance order — a feature becomes visible at the
// first zoom where its cell is free, and keeps its cells claimed at every
// deeper zoom so later features flow into the space AROUND it rather than on
// top of it. Because the grid is anchored to the world (not the viewport) and
// the ordering is a stable sort, the same features win at a given zoom no
// matter where the map is panned or which bbox the fetch covered: zooming in
// densifies around the winners; panning changes nothing. Features that never
// find a free cell by MAX_Z stay hidden — an honest "wouldn't fit legibly".
const WORLD_PX = 512; // MapLibre tile size: world width in px at z0

// Web-mercator grid cell of a point at zoom z, cellPx px per cell edge.
// Exported for usePublicLandLabelRank, which runs the same world-grid claim
// over tile-sourced area names.
export function cellKey(lng, lat, z, cellPx) {
  const cells = (WORLD_PX * 2 ** z) / cellPx;
  const x = Math.floor(((lng + 180) / 360) * cells);
  const s = Math.sin((lat * Math.PI) / 180);
  const y = Math.floor((0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * cells);
  return `${x}:${y}`;
}
