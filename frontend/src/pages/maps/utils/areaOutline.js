// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The combined outline of downloaded areas: where boxes overlap, the lines
// inside the overlap are gone, so several areas read as one shape.
//
// Every area is a rectangle, or (from an older version's merges) a union of
// rectangles, so the union is found exactly on a grid: every edge coordinate
// splits the plane into cells, a cell is covered when its centre is inside
// any area, and the outline is each cell edge with covered ground on one side
// and none on the other, joined into runs. The phone draws the same thing
// with the same algorithm (AreaOutline.kt), so the two cannot differ.
//
// Display only: the areas themselves, and what the server stores, are
// untouched.

/** Polygons (arrays of rings of [lng, lat]) for an area: its shape, else its box. */
export function areaPolygons(area) {
  const g = area.geometry;
  if (g?.type === "Polygon") return [g.coordinates];
  if (g?.type === "MultiPolygon") return g.coordinates;
  const [w, s, e, n] = area.bbox;
  return [[[[w, s], [e, s], [e, n], [w, n], [w, s]]]];
}

function inside(polygon, x, y) {
  // Even-odd over every ring, so holes stay holes.
  let hit = false;
  for (const ring of polygon) {
    for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
      const [xi, yi] = ring[i];
      const [xj, yj] = ring[j];
      if ((yi > y) !== (yj > y) && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) hit = !hit;
    }
  }
  return hit;
}

/**
 * The union of `polygons` as `{ lines, cells }`: `lines` is MultiLineString
 * coordinates for the outline, `cells` MultiPolygon coordinates for a fill
 * that never shades an overlap twice.
 */
export function unionOutline(polygons) {
  if (!polygons.length) return { lines: [], cells: [] };
  const xs = [...new Set(polygons.flatMap((p) => p.flatMap((r) => r.map((c) => c[0]))))].sort((a, b) => a - b);
  const ys = [...new Set(polygons.flatMap((p) => p.flatMap((r) => r.map((c) => c[1]))))].sort((a, b) => a - b);
  const nx = xs.length - 1;
  const ny = ys.length - 1;
  const covered = (i, j) => i >= 0 && j >= 0 && i < nx && j < ny && grid[i * ny + j] === 1;
  const grid = new Uint8Array(Math.max(nx, 0) * Math.max(ny, 0));
  const cells = [];
  for (let i = 0; i < nx; i++) {
    const cx = (xs[i] + xs[i + 1]) / 2;
    for (let j = 0; j < ny; j++) {
      const cy = (ys[j] + ys[j + 1]) / 2;
      if (polygons.some((p) => inside(p, cx, cy))) {
        grid[i * ny + j] = 1;
        cells.push([[[xs[i], ys[j]], [xs[i + 1], ys[j]], [xs[i + 1], ys[j + 1]], [xs[i], ys[j + 1]], [xs[i], ys[j]]]]);
      }
    }
  }
  const lines = [];
  // Horizontal edges, joined into runs along each row.
  for (let j = 0; j <= ny; j++) {
    let start = null;
    for (let i = 0; i <= nx; i++) {
      const edge = i < nx && covered(i, j - 1) !== covered(i, j);
      if (edge && start === null) start = i;
      if (!edge && start !== null) { lines.push([[xs[start], ys[j]], [xs[i], ys[j]]]); start = null; }
    }
  }
  // Vertical edges, joined along each column.
  for (let i = 0; i <= nx; i++) {
    let start = null;
    for (let j = 0; j <= ny; j++) {
      const edge = j < ny && covered(i - 1, j) !== covered(i, j);
      if (edge && start === null) start = j;
      if (!edge && start !== null) { lines.push([[xs[i], ys[start]], [xs[i], ys[j]]]); start = null; }
    }
  }
  return { lines, cells };
}
