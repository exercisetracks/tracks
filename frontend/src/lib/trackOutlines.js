// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Route outlines for the activity list's thumbnails: fetched one row at a
// time, kept for good.
//
// ## One at a time, behind the table
//
// The list is the page; the thumbnails are decoration on it. So each row asks
// for its own outline (`/activities/{id}/outline`) only once it is on screen,
// and the requests go through a queue that lets two run at once. The table
// renders with no thumbnails at all, and they fill in one by one as they
// arrive. One request for every track (`/tracks-geojson`) would have held all
// of them back until the slowest was parsed, and fetched tracks for rows
// nobody scrolls to.
//
// ## Cached in localStorage — which is safe only because of what an outline is
//
// A track never changes once recorded, so an outline fetched once is good
// for ever, and the second visit draws every thumbnail with no request at all.
// An outline is the shape normalised into a unit square, with no coordinates
// and no scale (backend calculators/track_outline.py); it is the server's
// encrypted-at-rest location that it does not reveal. Real tracks must never
// be cached here. The key carries the start time and distance as well as the
// id, so an activity that is trimmed or re-imported is fetched again.
// A null (no GPS) is cached too, so an indoor session is asked about once.

import { api } from "../api/client";

const STORE_KEY = "tracks.outlines.v1";
// Enough for years of activities at ~1 KB each, inside localStorage's ~5 MB.
const MAX_ENTRIES = 2000;
const CONCURRENCY = 2;

let memo = null;           // Map key → outline | null
let saveTimer = null;
const inflight = new Map(); // key → Promise
const queue = [];
let running = 0;

function load() {
  if (memo) return memo;
  memo = new Map();
  try {
    const raw = localStorage.getItem(STORE_KEY);
    if (raw) for (const [k, v] of JSON.parse(raw)) memo.set(k, v);
  } catch { /* a private window or cleared storage: start empty */ }
  return memo;
}

function persistSoon() {
  // Batched: a page of thumbnails arriving one by one is one write, not twenty.
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    try {
      const entries = [...memo.entries()].slice(-MAX_ENTRIES);
      localStorage.setItem(STORE_KEY, JSON.stringify(entries));
    } catch { /* over quota or blocked: the in-memory copy still serves */ }
  }, 500);
}

/** The cache key for an activity row. */
export function outlineKey(a) {
  return `${a.id}:${a.started_at ?? ""}:${Math.round(a.distance_meters ?? 0)}`;
}

/** The cached outline, `null` for "known to have none", `undefined` if unknown. */
export function cachedOutline(key) {
  const m = load();
  return m.has(key) ? m.get(key) : undefined;
}

function pump() {
  while (running < CONCURRENCY && queue.length) {
    const job = queue.shift();
    running++;
    job().finally(() => { running--; pump(); });
  }
}

/** Fetch an outline through the queue; resolves to the outline or null. */
export function fetchOutline(id, key) {
  const known = cachedOutline(key);
  if (known !== undefined) return Promise.resolve(known);
  if (inflight.has(key)) return inflight.get(key);
  const p = new Promise((resolve) => {
    queue.push(() =>
      api.getActivityOutline(id)
        .then((shape) => {
          const value = shape && shape.points?.length >= 2 ? shape : null;
          // Re-inserted so the most recently fetched survive the trim.
          memo.delete(key);
          memo.set(key, value);
          persistSoon();
          resolve(value);
        })
        // A failed fetch is not cached: the server may just have been busy.
        .catch(() => resolve(null))
        .finally(() => inflight.delete(key)),
    );
    pump();
  });
  inflight.set(key, p);
  return p;
}

/** For tests: forget everything. */
export function _resetOutlines() {
  memo = null;
  queue.length = 0;
  inflight.clear();
  running = 0;
}
