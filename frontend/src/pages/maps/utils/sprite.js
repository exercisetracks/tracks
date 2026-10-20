// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Where the map's sprite sheet is fetched from, for everything that reads it:
// the map itself, the legend, and the saved-place symbols.
//
// The sheet is a manifest and an image fetched apart, rebuilt in place under
// one name. Cached as immutable, a browser kept one half and fetched the other
// new, and every icon on the desktop map was drawn one slot over — a park with
// the public-works symbol, a peak with the park's. So each URL carries the
// sheet's content hash (`?v=`), and the two halves are only ever cached as a
// pair. The backend's app.routes.sprite has the server side of this.

const BASE = "/api/sprite/usgs";

let versionPromise = null;

/**
 * The sheet's current version, fetched once per page: a string, or null when
 * the server has none yet (still building) or could not be asked. Null still
 * works — the unversioned URL revalidates on every load.
 */
export function spriteVersion() {
  if (!versionPromise) {
    versionPromise = fetch("/api/sprite/version", { cache: "no-store" })
      .then((r) => (r.ok ? r.json() : null))
      .then((j) => j?.version ?? null)
      .catch(() => null);
  }
  return versionPromise;
}

const query = (version) => (version ? `?v=${encodeURIComponent(version)}` : "");

/** The style's `sprite`: MapLibre adds `@2x` and the extension before the query. */
export const spriteBase = (version) => `${BASE}${query(version)}`;

/** One file of the sheet, e.g. `spriteFile(v, "@2x", "png")`. */
export const spriteFile = (version, ratio, ext) => `${BASE}${ratio}.${ext}${query(version)}`;
