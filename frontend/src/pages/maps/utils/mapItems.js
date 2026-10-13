// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Tracks, places and collections: the rules the library, the editor and the
// detail panels share. Ports of the phone's (TrackLibrarySheet.kt,
// TrackHandles.kt, MapChrome.kt), so the same item reads the same on both.

/** The collection everything is in until it is filed elsewhere. */
export const DEFAULT_COLLECTION = "Tracks";

/**
 * Saturated enough to read over terrain, and apart from each other. No
 * near-black or near-white: one vanishes on a dark theme, the other on a light.
 */
export const TRACK_COLORS = [
  "#2563eb", "#dc2626", "#ea580c", "#ca8a04",
  "#16a34a", "#0891b2", "#7c3aed", "#db2777",
];

export const randomTrackColor = () => TRACK_COLORS[Math.floor(Math.random() * TRACK_COLORS.length)];

/**
 * A place's colour when nobody has chosen one: ink, because the symbol is drawn
 * *in* it — an unstyled place should read as a mark, not as highlighted. The
 * server's default too.
 */
export const DEFAULT_PLACE_COLOR = "#0f172a";

/**
 * The symbols a place can take — the map's own sprite names, so a saved place
 * looks like it belongs to the map it is drawn on. "marker" is no sprite: the
 * plain pin.
 */
export const PLACE_ICONS = [
  "marker", "peak", "viewpoint", "campground", "shelter", "alpine_hut",
  "drinking_water", "spring", "trailhead", "parking", "picnic", "toilets",
  "fuel", "restaurant", "cafe", "information", "park", "beach",
];

/**
 * Names in the order a person reads them: case aside, and a run of digits by
 * its value, so "Trail 2" comes before "Trail 10" rather than after "Trail 19".
 */
export function naturalCompare(a, b) {
  a = a ?? ""; b = b ?? "";
  let i = 0, j = 0;
  const digit = (c) => c >= "0" && c <= "9";
  while (i < a.length && j < b.length) {
    if (digit(a[i]) && digit(b[j])) {
      let ea = i; while (ea < a.length && digit(a[ea])) ea++;
      let eb = j; while (eb < b.length && digit(b[eb])) eb++;
      const na = a.slice(i, ea).replace(/^0+/, "");
      const nb = b.slice(j, eb).replace(/^0+/, "");
      // A longer (zero-stripped) run is a bigger number; equal lengths compare digit by digit.
      if (na.length !== nb.length) return na.length - nb.length;
      if (na !== nb) return na < nb ? -1 : 1;
      i = ea; j = eb;
    } else {
      const ca = a[i].toLowerCase(), cb = b[j].toLowerCase();
      if (ca !== cb) return ca < cb ? -1 : 1;
      i++; j++;
    }
  }
  return (a.length - i) - (b.length - j);
}

/** How the library is ordered. */
export const SORTS = [
  { key: "name", label: "A–Z" },
  { key: "newest", label: "Newest" },
];

export function sortItems(list, sort) {
  if (sort === "newest") {
    // Ids are handed out in creation order.
    return [...list].sort((x, y) => y.id - x.id);
  }
  return [...list].sort((x, y) => naturalCompare(x.name, y.name));
}

/**
 * Where a track stands with the watch, in the words somebody checks before
 * leaving. The server's vocabulary (`pending_upload`, `on_device`) is precise
 * and unreadable; this translates it once.
 */
export function trackDeviceLabel(t) {
  switch (t?.device_status) {
    case "on_device": return "on the watch";
    case "pending_upload": return "waiting for the watch";
    case "pending_remove": return "coming off";
    // Pulled off a watch at some point: a copy, not a statement about what
    // the watch holds now.
    case "external": return "imported from a watch";
    default: return "not on the watch";
  }
}

export function placeDeviceLabel(w) {
  if (w?.on_watch && w?.load_to_device) return "on the watch";
  if (w?.on_watch) return "coming off";
  if (w?.load_to_device) return "waiting for the watch";
  return "not on the watch";
}

/**
 * Whether the watch has confirmed it holds this — read from its own
 * collection, not assumed from a transfer. A track still coming off counts,
 * because it is still there.
 */
export const trackOnWatch = (t) => t?.device_status === "on_device" || t?.device_status === "pending_remove";
export const placeOnWatch = (w) => !!w?.on_watch;

/** Whether Tracks *wants* it on the watch — which of To watch / Off watch to offer. */
export const wantedOnWatch = (item) => !!item?.load_to_device;

const plural = (n, noun) => `${n} ${noun}${n === 1 ? "" : "s"}`;

/** "3 tracks · 2 places", for a collection's heading and the summary line. */
export const countLabel = (tracks, places) => `${plural(tracks, "track")} · ${plural(places, "place")}`;

// ── Lines and handles ───────────────────────────────────────────────────────

/** Enough to shape a long route, few enough to edit by hand. */
export const MAX_HANDLES = 60;

/** The coarsest the simplify slider goes, in metres of allowed deviation. */
export const SIMPLIFY_MAX_M = 60;

/** Distance from p to the segment a–b, in metres, on a local flat projection. `[lng, lat]`. */
function offsetMetres(p, a, b) {
  const k = Math.cos((a[1] * Math.PI) / 180) * 111_320;
  const x = (q) => (q[0] - a[0]) * k;
  const y = (q) => (q[1] - a[1]) * 110_540;
  const bx = x(b), by = y(b), px = x(p), py = y(p);
  const len2 = bx * bx + by * by;
  const t = len2 === 0 ? 0 : Math.max(0, Math.min(1, (px * bx + py * by) / len2));
  return Math.hypot(px - t * bx, py - t * by);
}

/** Ramer–Douglas–Peucker: which vertices of `[lng, lat, …]` points survive at a tolerance in metres. */
function keepMask(points, toleranceM) {
  const keep = new Array(points.length).fill(false);
  if (!points.length) return keep;
  keep[0] = true;
  keep[points.length - 1] = true;
  const stack = [[0, points.length - 1]];
  while (stack.length) {
    const [a, b] = stack.pop();
    let far = -1, farDist = toleranceM;
    for (let i = a + 1; i < b; i++) {
      const d = offsetMetres(points[i], points[a], points[b]);
      if (d > farDist) { farDist = d; far = i; }
    }
    if (far >= 0) {
      keep[far] = true;
      stack.push([a, far], [far, b]);
    }
  }
  return keep;
}

/**
 * Handles for a line that has none: simplified until editable, starting at
 * 10 m so a short route keeps its shape where it matters. Each handle is a
 * vertex of the line exactly, which is what lets [handleIndices] find it.
 */
export function handlesFromLine(line) {
  const pts = (line || []).filter((c) => c && c[0] != null && c[1] != null);
  if (pts.length <= 2) return pts.map((c) => [c[0], c[1]]);
  let tol = 10;
  let mask = keepMask(pts, tol);
  while (mask.filter(Boolean).length > MAX_HANDLES) {
    tol *= 1.5;
    mask = keepMask(pts, tol);
  }
  return pts.filter((_, i) => mask[i]).map((c) => [c[0], c[1]]);
}

/** A line with detail under `toleranceM` taken out; every kept vertex exactly as it was, elevation included. */
export function reduceLine(line, toleranceM) {
  const pts = (line || []).filter((c) => c && c[0] != null && c[1] != null);
  if (pts.length <= 2 || toleranceM <= 0) return pts;
  const mask = keepMask(pts, toleranceM);
  return pts.filter((_, i) => mask[i]);
}

/** Where each handle sits in `line`, as [handle, index] — handles taken from the line match a vertex exactly. */
export function handleIndices(handles, line) {
  let from = 0;
  const out = [];
  for (const h of handles) {
    let found = -1;
    for (let k = from; k < line.length; k++) {
      if (line[k][0] === h[0] && line[k][1] === h[1]) { found = k; break; }
    }
    if (found < 0) continue;
    from = found;
    out.push([h, found]);
  }
  return out;
}

/**
 * The line through `handles` without routing: between two original handles
 * that are still where they were, the original line, detail and elevations
 * kept; anywhere else, a straight join. So a track whose middle handle moved
 * is redrawn there and nowhere else.
 */
export function stitchLine(handles, baseLine, baseHandles) {
  const indexOf = (p) => {
    const hit = (baseHandles || []).find(([h]) => h[0] === p[0] && h[1] === p[1]);
    return hit ? hit[1] : null;
  };
  const order = (idx) => (baseHandles || []).findIndex(([, i]) => i === idx);
  const out = [];
  handles.forEach((here, k) => {
    if (k === 0) { out.push([here[0], here[1]]); return; }
    const from = indexOf(handles[k - 1]);
    const to = indexOf(here);
    if (baseLine && from != null && to != null && to > from && order(from) + 1 === order(to)) {
      out.push(...baseLine.slice(from + 1, to + 1));
    } else {
      out.push([here[0], here[1]]);
    }
  });
  return out;
}

export function haversineKm(a, b) {
  const R = 6371;
  const dLat = ((b[1] - a[1]) * Math.PI) / 180;
  const dLon = ((b[0] - a[0]) * Math.PI) / 180;
  const la1 = (a[1] * Math.PI) / 180;
  const la2 = (b[1] * Math.PI) / 180;
  const sd = Math.sin(dLat / 2) ** 2 + Math.cos(la1) * Math.cos(la2) * Math.sin(dLon / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(sd), Math.sqrt(1 - sd));
}

/** Green when flat, yellow around 8 %, red from 15 % — up or down. */
export function gradeColor(grade) {
  const t = Math.min(1, Math.abs(grade) / 0.15);
  const lerp = (c0, c1, f) => c0.map((v, i) => Math.round(v + (c1[i] - v) * f));
  const green = [22, 163, 74], yellow = [234, 179, 8], red = [220, 38, 38];
  const c = t < 0.53 ? lerp(green, yellow, t / 0.53) : lerp(yellow, red, (t - 0.53) / 0.47);
  return `rgb(${c[0]},${c[1]},${c[2]})`;
}

/** A line's elevation profile, `{points: [{d_km, ele_m}]}`, as the detail panels draw it. */
export function profileOf(line) {
  let cum = 0;
  const points = [];
  (line || []).forEach((c, i) => {
    if (i > 0) cum += haversineKm(line[i - 1], c);
    points.push({ d_km: cum, ele_m: c[2] ?? null });
  });
  return { points };
}

/** Climb and descent of a line in metres, from its own elevations. */
export function climbOf(line) {
  let gain = 0, loss = 0;
  for (let i = 1; i < (line || []).length; i++) {
    const a = line[i - 1][2], b = line[i][2];
    if (a == null || b == null) continue;
    if (b > a) gain += b - a; else loss += a - b;
  }
  return { gain: Math.round(gain), loss: Math.round(loss) };
}

export function lineKm(line) {
  let km = 0;
  for (let i = 1; i < (line || []).length; i++) km += haversineKm(line[i - 1], line[i]);
  return km;
}

/** The point `km` along a line, interpolated, as `[lng, lat]`. */
export function pointAlong(line, km) {
  if (!line?.length) return null;
  let cum = 0;
  for (let i = 1; i < line.length; i++) {
    const seg = haversineKm(line[i - 1], line[i]);
    if (cum + seg >= km) {
      const f = seg > 0 ? (km - cum) / seg : 0;
      return [line[i - 1][0] + (line[i][0] - line[i - 1][0]) * f, line[i - 1][1] + (line[i][1] - line[i - 1][1]) * f];
    }
    cum += seg;
  }
  const last = line[line.length - 1];
  return [last[0], last[1]];
}

// ── Handles kept per track, on this browser ─────────────────────────────────
//
// A saved track stores only its line; the clicks it was routed between are
// gone, and a line of several hundred vertices is not something anyone edits
// by hand. So they are kept here, per track — an editing aid, not part of the
// track, as the phone keeps its own. A track without them gets handles by
// simplifying its line and opens with snapping off, so nothing moves until
// someone moves it.

const HANDLES_KEY = "tracks_track_handles";

export function loadHandles(trackId) {
  try {
    const all = JSON.parse(localStorage.getItem(HANDLES_KEY) || "{}");
    const saved = all[trackId];
    return saved?.handles?.length >= 2 ? saved : null;
  } catch { return null; }
}

export function saveHandles(trackId, handles, snapToTrails) {
  try {
    const all = JSON.parse(localStorage.getItem(HANDLES_KEY) || "{}");
    all[trackId] = { handles, snapToTrails };
    localStorage.setItem(HANDLES_KEY, JSON.stringify(all));
  } catch { /* storage full or off: the handles are only an aid */ }
}

export function forgetHandles(trackId) {
  try {
    const all = JSON.parse(localStorage.getItem(HANDLES_KEY) || "{}");
    delete all[trackId];
    localStorage.setItem(HANDLES_KEY, JSON.stringify(all));
  } catch { /* nothing to forget */ }
}

// ── GPX ─────────────────────────────────────────────────────────────────────

const esc = (s) => String(s ?? "").replace(/[<>&"']/g, (c) => ({ "<": "&lt;", ">": "&gt;", "&": "&amp;", '"': "&quot;", "'": "&apos;" }[c]));

/** One saved place as a GPX waypoint file. */
export function placeGpx(w) {
  const ele = w.ele_m != null ? `<ele>${w.ele_m}</ele>` : "";
  return `<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Tracks" xmlns="http://www.topografix.com/GPX/1/1">
  <wpt lat="${w.lat}" lon="${w.lng}">${ele}<name>${esc(w.name)}</name></wpt>
</gpx>
`;
}

export function downloadText(text, filename, mime = "application/gpx+xml") {
  const url = URL.createObjectURL(new Blob([text], { type: mime }));
  const a = document.createElement("a");
  a.href = url; a.download = filename; a.click();
  URL.revokeObjectURL(url);
}

export const fileSafe = (name) => `${(name || "track").replace(/[^a-z0-9]+/gi, "_")}.gpx`;
