// Emit the MapLibre style as a static JSON document the backend can serve.
//
// The style is ~3400 lines of JavaScript across 20 layer builders
// (src/pages/maps/style/). A native client needs the same style, and there are
// only two ways to give it one: translate the builders into Python, or execute
// the builders we already have and publish the result.
//
// Translating would mean two implementations of the same map — every future
// layer written twice, every palette tweak applied twice, and a slow drift
// between what the web shows and what the phone shows. So this script runs the
// real builders under Node and writes their output. There is exactly one
// implementation of the style, and it stays the one the web app imports
// directly.
//
// The tile cache-busting version is a runtime value (the mtime of each PMTiles
// archive, which changes when a region is merged), so it can't be baked in at
// build time. It's emitted as a placeholder the backend substitutes per
// request — see backend/app/routes/map_style.py.
//
// It also writes the two backdrop styles (light and dark) that sit behind GPS
// overlays — the web dashboard's heatmap, the activity maps — from
// src/components/map/basemapStyle.js, beside the main one. The phone's
// dashboard heatmap renders those rather than the 124-layer planning style,
// for the same reason the browser does: it is a backdrop for colourful tracks,
// and the planning style has no dark palette at all.
//
// Usage: node scripts/build-map-style.mjs [outfile]

import "./extensionless-resolver.mjs";

import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve as resolvePath } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const DEFAULT_OUT = resolvePath(HERE, "../../backend/app/static/map_style.json");

// Substituted by the backend at request time. Chosen to survive JSON encoding
// and to be obviously not-a-real-value if one ever leaks to a client.
const VERSION_PLACEHOLDER = "__TILE_VERSION__";

const { buildStyle } = await import("../src/pages/maps/style/index.js");
const { buildBasemapStyle } = await import("../src/components/map/basemapStyle.js");
const { alignTileUrls } = await import("./align-tile-urls.mjs");

// includeDem, unlike the browser: a native client cannot add a source to its own
// style after load the way useRegionDownload does, so relief has to be in the
// document or it never arrives. Naming an archive that may not be on disk is
// safe here and only here — the backend drops sources it cannot serve, layers
// and all, before this document reaches anyone (map_style._fit_to_available_tiles).
const style = buildStyle(VERSION_PLACEHOLDER, { includeDem: true });

if (!style?.layers?.length) {
  console.error("build-map-style: buildStyle() produced no layers");
  process.exit(1);
}
if (!JSON.stringify(style).includes(VERSION_PLACEHOLDER)) {
  // If the placeholder vanished, buildSources stopped threading `version`
  // through and every client would silently lose cache busting.
  console.error("build-map-style: version placeholder missing from output");
  process.exit(1);
}

const out = process.argv[2] ? resolvePath(process.argv[2]) : DEFAULT_OUT;
mkdirSync(dirname(out), { recursive: true });
writeFileSync(out, JSON.stringify(style, null, 0) + "\n");

console.log(
  `build-map-style: ${style.layers.length} layers, ` +
  `${Object.keys(style.sources).length} sources -> ${out}`
);

// Named after the main artifact so a custom outfile keeps them together.
for (const theme of ["light", "dark"]) {
  const backdrop = alignTileUrls(buildBasemapStyle(theme), style);
  if (!backdrop?.layers?.length) {
    console.error(`build-map-style: buildBasemapStyle(${theme}) produced no layers`);
    process.exit(1);
  }
  const backdropOut = out.replace(/map_style\.json$|\.json$/, `backdrop_style_${theme}.json`);
  writeFileSync(backdropOut, JSON.stringify(backdrop, null, 0) + "\n");
  console.log(`build-map-style: backdrop (${theme}), ${backdrop.layers.length} layers -> ${backdropOut}`);
}
