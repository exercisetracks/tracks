// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Visvalingam–Whyatt line simplification with precomputed point importances.
//
// Each interior point gets an "effective area" (m²): the triangle area at which it
// would be dropped, made monotonic (running max) so the result is a clean nested
// hierarchy — dropping the least-significant points first, which preserves overall
// shape far better than naive point-dropping. Importances are computed ONCE per
// track; any tolerance afterwards is just an O(n) threshold filter, so a tolerance
// slider stays smooth even on very long tracks. Endpoints always survive and kept
// points retain their elevation.

const R = 6371000; // earth radius, metres
const RAD = Math.PI / 180;

function project(coords) {
  const latCos = Math.cos((coords[Math.floor(coords.length / 2)][1] || 0) * RAD);
  return coords.map((c) => [c[0] * RAD * R * latCos, c[1] * RAD * R]);
}

function triArea(a, b, c) {
  return Math.abs((b[0] - a[0]) * (c[1] - a[1]) - (c[0] - a[0]) * (b[1] - a[1])) / 2;
}

// { weights, sorted }: weights[i] = effective area (m²) for point i (Infinity at the
// endpoints); sorted = ascending finite interior weights (for percentile mapping).
export function computeImportance(coords) {
  const n = coords?.length || 0;
  const weights = new Float64Array(n);
  if (n <= 2) { for (let i = 0; i < n; i++) weights[i] = Infinity; return { weights, sorted: [] }; }

  const pts = project(coords);
  const prev = new Int32Array(n);
  const next = new Int32Array(n);
  const area = new Float64Array(n);
  const version = new Int32Array(n);
  weights[0] = weights[n - 1] = Infinity;

  // Binary min-heap of [area, index, version] with lazy invalidation: when a
  // point's area is recomputed we bump its version and push a fresh entry; stale
  // entries are skipped on pop.
  const heap = [];
  const hpush = (it) => {
    heap.push(it);
    let i = heap.length - 1;
    while (i > 0) { const p = (i - 1) >> 1; if (heap[p][0] <= heap[i][0]) break; [heap[p], heap[i]] = [heap[i], heap[p]]; i = p; }
  };
  const hpop = () => {
    const top = heap[0];
    const last = heap.pop();
    if (heap.length) {
      heap[0] = last; let i = 0; const m = heap.length;
      for (;;) {
        let s = i; const l = 2 * i + 1; const r = 2 * i + 2;
        if (l < m && heap[l][0] < heap[s][0]) s = l;
        if (r < m && heap[r][0] < heap[s][0]) s = r;
        if (s === i) break; [heap[s], heap[i]] = [heap[i], heap[s]]; i = s;
      }
    }
    return top;
  };

  for (let i = 1; i < n - 1; i++) {
    prev[i] = i - 1; next[i] = i + 1;
    area[i] = triArea(pts[i - 1], pts[i], pts[i + 1]);
    hpush([area[i], i, 0]);
  }

  let runningMax = 0;
  while (heap.length) {
    const [a, i, v] = hpop();
    if (v !== version[i]) continue;             // stale entry
    runningMax = Math.max(runningMax, a);
    weights[i] = runningMax;
    const p = prev[i];
    const nx = next[i];
    next[p] = nx; prev[nx] = p;
    for (const j of [p, nx]) {
      if (j > 0 && j < n - 1) {
        area[j] = triArea(pts[prev[j]], pts[j], pts[next[j]]);
        version[j]++;
        hpush([area[j], j, version[j]]);
      }
    }
  }

  const sorted = [];
  for (let i = 1; i < n - 1; i++) if (Number.isFinite(weights[i])) sorted.push(weights[i]);
  sorted.sort((x, y) => x - y);
  return { weights, sorted };
}

// Map a 0–100 slider to an area threshold from the sorted weights. 0 ⇒ original
// (keep everything); higher drops that percentile of least-important points.
export function areaForPercent(sorted, pct) {
  if (!sorted || sorted.length === 0 || pct <= 0) return 0;
  if (pct >= 100) return Infinity;
  return sorted[Math.min(sorted.length - 1, Math.floor((pct / 100) * sorted.length))];
}

export function simplifyByImportance(coords, weights, minArea) {
  if (!coords || coords.length < 3 || minArea <= 0) return coords ? coords.slice() : [];
  const out = [];
  for (let i = 0; i < coords.length; i++) if (weights[i] >= minArea) out.push(coords[i]);
  // Never collapse below the two endpoints.
  return out.length >= 2 ? out : [coords[0], coords[coords.length - 1]];
}

// Quick haversine length (km) of a [lng,lat,...] polyline.
export function trackLengthKm(coords) {
  let m = 0;
  for (let i = 1; i < (coords?.length || 0); i++) m += segMeters(coords[i - 1], coords[i]);
  return m / 1000;
}

function segMeters(a, b) {
  const dLat = (b[1] - a[1]) * RAD;
  const dLon = (b[0] - a[0]) * RAD;
  const la1 = a[1] * RAD;
  const la2 = b[1] * RAD;
  const sd = Math.sin(dLat / 2) ** 2 + Math.cos(la1) * Math.cos(la2) * Math.sin(dLon / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(sd), Math.sqrt(1 - sd));
}

// Build the ElevationProfileCard shape ({points:[{d_km, ele_m}]}) from a
// [lng,lat,ele] polyline — used to redraw the profile live during simplification.
export function coordsToProfile(coords) {
  const points = [];
  let m = 0;
  for (let i = 0; i < (coords?.length || 0); i++) {
    if (i > 0) m += segMeters(coords[i - 1], coords[i]);
    points.push({ d_km: m / 1000, ele_m: coords[i][2] != null ? coords[i][2] : null });
  }
  return { points };
}
