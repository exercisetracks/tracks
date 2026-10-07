// Fail if the generated map style artifact has drifted from its source.
//
// `backend/app/static/map_style.json` is what a native client renders from, and
// it is a build product of the same modules the web app imports directly. That
// is the point — one implementation of the map. But a checked-in build product
// goes stale silently: someone tweaks a layer, the web map updates on the next
// dev reload, and the phone keeps rendering last month's style with nothing to
// indicate anything is wrong.
//
// Deliberately a plain script rather than a vitest case. The frontend container
// mounts only `frontend/`, so a test running inside it cannot see the backend
// tree at all; this runs anywhere the whole repo is checked out, which is what
// CI has.
//
// Usage: node scripts/check-map-style.mjs   (or: npm run check:map-style)

import "./extensionless-resolver.mjs";

import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const ARTIFACT = resolve(HERE, "../../backend/app/static/map_style.json");
const VERSION_PLACEHOLDER = "__TILE_VERSION__";

const { buildStyle } = await import("../src/pages/maps/style/index.js");
const { buildBasemapStyle } = await import("../src/components/map/basemapStyle.js");
const { alignTileUrls } = await import("./align-tile-urls.mjs");

const fail = (msg) => {
  console.error(`check-map-style: ${msg}`);
  process.exit(1);
};

let committed;
try {
  committed = readFileSync(ARTIFACT, "utf8");
} catch {
  fail(`artifact missing at ${ARTIFACT} — run \`npm run build:map-style\``);
}

// includeDem, as build-map-style.mjs passes it — without it this compared
// against a style the build never writes and reported every artifact stale.
const fresh = JSON.stringify(buildStyle(VERSION_PLACEHOLDER, { includeDem: true })) + "\n";

if (committed !== fresh) {
  fail("map_style.json is stale — run `npm run build:map-style` and commit the result");
}

if (!committed.includes(VERSION_PLACEHOLDER)) {
  // If buildSources stopped threading `version` through, cache busting would
  // quietly stop working for every client instead of failing loudly.
  fail("version placeholder missing — buildSources is no longer threading `version` through");
}

// The backend rewrites origin-relative paths to absolute URLs for native
// clients by prefixing `"/api`. A hard-coded origin, or an /api path not
// starting right at the quote, slips past that and leaves a phone fetching a
// URL it cannot resolve.
const style = JSON.parse(committed);
const urls = [
  style.glyphs,
  style.sprite,
  ...Object.values(style.sources).flatMap((s) => s.tiles || []),
].filter(Boolean);

// The backdrops the phone's dashboard heatmap renders: the same staleness and
// the same URL rule apply.
for (const theme of ["light", "dark"]) {
  const path = resolve(HERE, `../../backend/app/static/backdrop_style_${theme}.json`);
  let text;
  try {
    text = readFileSync(path, "utf8");
  } catch {
    fail(`backdrop artifact missing at ${path} — run \`npm run build:map-style\``);
  }
  const planning = JSON.parse(committed);
  if (text !== JSON.stringify(alignTileUrls(buildBasemapStyle(theme), planning)) + "\n") {
    fail(`backdrop_style_${theme}.json is stale — run \`npm run build:map-style\` and commit the result`);
  }
  const b = JSON.parse(text);
  urls.push(b.glyphs, ...Object.values(b.sources).flatMap((s) => s.tiles || []));
}

const bad = urls.filter((u) => !u.startsWith("/api/"));
if (bad.length) {
  fail(`style references non-/api URLs the base_url rewrite cannot reach: ${bad.join(", ")}`);
}

console.log(
  `check-map-style: up to date (${style.layers.length} layers, ${urls.length} URLs checked)`
);
