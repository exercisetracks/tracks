// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Deterministic label ranking for the POI GeoJSON source — the pan-stable
// replacement for collision culling on poi_db_icons (style/zoomGate.js is the
// style-side half). The DB's per-feature min_zoom grades importance, but in a
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
const MAX_Z = 16; // map's maxZoom — grids beyond it can never be seen
const CHANNELS = [
  // ~footprint of one icon / one short name+halo, in screen px. Bigger cell =
  // sparser channel. Text floor 12 matches the old "names from z12" step.
  { prop: "icon_zoom", cellPx: 56, floor: 4 },
  { prop: "label_zoom", cellPx: 140, floor: 12 },
];

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

// Stable tie-break within an importance grade, so equal-grade neighbours always
// resolve the same way across fetches.
function tieKey(f) {
  return String(f.id ?? f.properties.id ?? f.properties.name ?? f.geometry.coordinates);
}

export function rankPoiLabels(fc) {
  if (!fc || !Array.isArray(fc.features)) return fc;
  const ordered = fc.features
    .filter((f) => f?.geometry?.type === "Point" && f.properties)
    .sort((a, b) => {
      const za = a.properties.min_zoom ?? 13;
      const zb = b.properties.min_zoom ?? 13;
      if (za !== zb) return za - zb;
      const ka = tieKey(a);
      const kb = tieKey(b);
      return ka < kb ? -1 : ka > kb ? 1 : 0;
    });

  for (const { prop, cellPx, floor } of CHANNELS) {
    const taken = new Map(); // zoom -> Set of claimed cell keys
    for (let z = floor; z <= MAX_Z; z++) taken.set(z, new Set());
    for (const f of ordered) {
      const [lng, lat] = f.geometry.coordinates;
      const start = Math.max(floor, Math.ceil(f.properties.min_zoom ?? 13));
      let assigned = MAX_Z + 99; // never fits → gate keeps it hidden
      for (let z = start; z <= MAX_Z; z++) {
        if (!taken.get(z).has(cellKey(lng, lat, z, cellPx))) {
          assigned = z;
          break;
        }
      }
      f.properties[prop] = assigned;
      for (let z = assigned; z <= MAX_Z; z++) {
        taken.get(z).add(cellKey(lng, lat, z, cellPx));
      }
    }
  }
  return fc;
}
