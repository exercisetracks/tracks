// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Give a backdrop style the planning style's exact tile URLs.
//
// The phone stores offline tiles in MapLibre's database keyed by URL, and it
// downloads them through the planning style. The backdrop asks for the same
// tiles, but the planning style cache-busts some sources with `?v=<version>`
// and the browser-built backdrop does not — so on a phone with the radio off,
// the backdrop's request for a tile that is sitting in the offline store
// missed it by a query string. Matching on the path and taking the planning
// style's URL wholesale makes the two requests the same request.
//
// Used by build-map-style.mjs to write the artifact and by check-map-style.mjs
// to rebuild it for comparison, so the two cannot disagree.

const path = (url) => url.split("?")[0];

export function alignTileUrls(backdrop, planning) {
  const byPath = new Map();
  for (const src of Object.values(planning.sources)) {
    for (const url of src.tiles || []) byPath.set(path(url), url);
  }
  for (const src of Object.values(backdrop.sources)) {
    if (src.tiles) src.tiles = src.tiles.map((url) => byPath.get(path(url)) ?? url);
  }
  return backdrop;
}
