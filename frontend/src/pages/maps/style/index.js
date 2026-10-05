// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { buildSources, buildDemSources } from "./sources";
import { buildBackground } from "./layers/background";
// Included only under `includeDem` — in the browser the DEM sources are added
// at runtime once master_dem.pmtiles is confirmed present. See buildStyle.
import { buildHillshade } from "./layers/hillshade";
import { buildLandcover } from "./layers/landcover";
import { buildLanduse } from "./layers/landuse";
import { buildPublicLands, buildPublicLandLabels, buildPublicLandBoundaryLabels } from "./layers/publiclands";
import { buildVegetation } from "./layers/vegetation";
import { buildWater } from "./layers/water";
import { buildRoads } from "./layers/roads";
import { buildInfrastructure } from "./layers/infrastructure";
import { buildOsmTracks } from "./layers/tracks";
import { buildTrails } from "./layers/trails";
import { buildOsmTrails } from "./layers/osmTrails";
import { buildLongTrailsBase, buildLongTrailsTop } from "./layers/longTrails";
import { buildBuildings } from "./layers/buildings";
import { buildBoundaries } from "./layers/boundaries";
import { buildLabels } from "./layers/labels";
import { buildContours } from "./layers/contours";
import { buildActivityTracks } from "./layers/activityTracks";
import { buildCustomTracks } from "./layers/customTracks";
import { buildSmoke, buildWildfires } from "./layers/wildfires";

// ── Systematic fade-in ────────────────────────────────────────────────────────
// Many utility fills/lines/circles simply switch on at their `minzoom` with a
// flat opacity, so they "pop" as you zoom in. withFadeIn() sweeps the assembled
// layer list and, for any fill/line/circle/fill-extrusion that has a zoom floor
// AND a plain (or unset) opacity, ramps that opacity 0→base over the first ~half
// zoom level so the feature materialises instead of snapping in. Layers whose
// opacity is already an expression — a deliberate zoom fade, or a feature-state
// hover/selection glow — are left exactly as tuned; this only softens the ones
// nobody hand-tuned. (Symbols are skipped: MapLibre already cross-fades label/
// icon placement, and their text/icon opacities are individually tuned.)
const FADE_ZOOM = 0.5;
const FADE_OPACITY_PROPS = {
  fill: ["fill-opacity"],
  line: ["line-opacity"],
  circle: ["circle-opacity", "circle-stroke-opacity"],
  "fill-extrusion": ["fill-extrusion-opacity"],
};

function withFadeIn(layers) {
  return layers.map((layer) => {
    const props = FADE_OPACITY_PROPS[layer.type];
    const mz = layer.minzoom;
    if (!props || mz == null || mz < 3) return layer;
    const paint = { ...(layer.paint || {}) };
    let changed = false;
    for (const prop of props) {
      const cur = paint[prop];
      // Only touch an unset or constant opacity — never rewrite a tuned
      // expression (existing zoom fade, hover glow, data-driven alpha).
      if (cur !== undefined && typeof cur !== "number") continue;
      const base = cur === undefined ? 1 : cur;
      if (base <= 0) continue; // invisible hit/hover layer — nothing to fade
      paint[prop] = ["interpolate", ["linear"], ["zoom"], mz, 0, mz + FADE_ZOOM, base];
      changed = true;
    }
    return changed ? { ...layer, paint } : layer;
  });
}

// ── Stable symbol placement ──────────────────────────────────────────────────
// By default MapLibre runs viewport collision detection on every symbol layer:
// as you pan/zoom, labels and icons fight for space and get culled, re-placed,
// and faded in/out — the map "reflows" constantly. withStablePlacement() sweeps
// every POINT-placed symbol layer and switches it to deterministic placement:
// allow-overlap + ignore-placement disables the collision engine, so a symbol's
// visibility depends only on the data — never on what else happens to be in the
// viewport. Density is handled deterministically instead: dense layers gate
// each feature on its baked importance grade (see ./zoomGate.js, and
// utils/labelRank.js for the POI grid ranking), so the same features are
// visible at a given zoom no matter where you pan. Sort keys still control draw
// order; the *-optional flags are deleted because they only mean anything under
// collision. Pairs with fadeDuration: 0 on the Map (useMapInit) so nothing
// fades — symbols are simply there or not.
//
// LINE-placed labels (road/trail/water names, contour figures) are exempt and
// keep their tuned collision settings: their repeats are pinned to the line
// geometry, so culling only ever slides a name along its own line — while
// WITHOUT collision every segment's repeat prints and stacks up at junctions
// (the downtown "street-name soup"). Kept collision-culled as the lesser evil.
function withStablePlacement(layers) {
  return layers.map((layer) => {
    if (layer.type !== "symbol") return layer;
    const layout = { ...(layer.layout || {}) };
    if (String(layout["symbol-placement"] || "point").startsWith("line")) return layer;
    layout["text-allow-overlap"] = true;
    layout["text-ignore-placement"] = true;
    layout["icon-allow-overlap"] = true;
    layout["icon-ignore-placement"] = true;
    delete layout["text-optional"];
    delete layout["icon-optional"];
    return { ...layer, layout };
  });
}

/**
 * @param version   tile cache-busting string (an archive mtime).
 * @param includeDem  carry the elevation sources and their hillshade layers in
 *   the document rather than leaving them to be added at runtime. The browser
 *   leaves it off — it cannot know whether a DEM exists until the version
 *   endpoint answers, and naming an absent archive costs a session of 404s. The
 *   served style turns it on, because the backend drops sources with no archive
 *   on disk along with their layers, which is the same decision made earlier and
 *   with better information. A native client cannot patch its own style after
 *   load, so for the phone this is the only way relief arrives at all.
 */
export function buildStyle(version = "", { includeDem = false } = {}) {
  const sources = includeDem
    ? { ...buildSources(version), ...buildDemSources(version) }
    : buildSources(version);
  const layers = [
    ...buildBackground(),
    ...buildWater(),
    ...buildLandcover(),
    ...buildLanduse(),
    // OSM vegetation/wetland cover (USGS pattern fills) above the basemap
    // landcover/landuse, below roads.
    ...buildVegetation(),
    // Public-land / ownership wash (National Forest, BLM, NP, wilderness, state,
    // military, tribal) ABOVE the vegetation stack, below roads/trails. It must
    // sit over veg: the alpine bare-rock/scree/glacier fills ramp to ~90%
    // opacity by z11 and were erasing the translucent wash exactly on the high
    // peaks ("mountains clipping through the wilderness tint"). The wash is
    // ≤22% alpha, so the veg patterns still read through it.
    ...buildPublicLands(),
    // Relief goes ON TOP of every area fill and under all the line work, which
    // is where landcover.js always said it was ("a soft tint under the
    // hillshade"). Under the fills it is invisible at the zooms that matter:
    // landcover_overview alone is a 55% wash from z8.5 to z13, and the
    // vegetation and public-land layers add more on top of that. Absent unless
    // includeDem; see ensureDemSource for the browser's path to the same place.
    ...(includeDem ? buildHillshade() : []),
    ...buildRoads(),
    // Infrastructure lines (railroads, power, pipelines, dams, levees) above
    // roads so USGS crossties/pylons read over the road network.
    ...buildInfrastructure(),
    ...buildContours(),
    // Unpaved drivable roads (graded service double-dash + 4×4 track), decoupled
    // from the foot/bike/horse trails — from the OSM overlay only (downloaded regions).
    ...buildOsmTracks(),
    // Trails render above tracks/contours so the singletrack hero line stays on top.
    // Basemap path layers are the global fallback; OSM rich trails (downloaded
    // regions) render on top with surface/visibility-aware styling.
    ...buildTrails(),
    // Long-trail highlight corridor sits UNDER the trail ways so the translucent
    // route-coloured band lifts the line without washing over it.
    ...buildLongTrailsBase(),
    ...buildOsmTrails(),
    // Long-trail line, hover glow, names, anchored badges + section markers on top.
    ...buildLongTrailsTop(),
    ...buildBuildings(),
    ...buildBoundaries(),
    // Smoke plumes (live opt-in overlay) under the labels so place/road names
    // stay readable through the wash.
    ...buildSmoke(),
    ...buildLabels(),
    // Public-land area names — after the main labels so place/POI names win.
    ...buildPublicLandLabels(),
    // Small along-the-line tags naming each area right at its own boundary.
    ...buildPublicLandBoundaryLabels(),
    // Past-activity tracks (heatmap) under the user's custom tracks.
    ...buildActivityTracks(),
    // User custom tracks (courses) above labels so the coloured line + selection
    // glow always read on top (starts hidden; My Tracks toggle).
    ...buildCustomTracks(),
    // Live wildfires on the very top — an active fire outranks everything.
    ...buildWildfires(),
    // The graticule + UTM grid is NOT a style layer — it's painted as a canvas
    // HUD over the map by useGridOverlay (Grid toggle), so it isn't listed here.
  ];

  return {
    version: 8,
    glyphs: "/api/fonts/{fontstack}/{range}.pbf",
    sprite: "/api/sprite/usgs",
    transition: { duration: 300, delay: 0 },
    sources,
    layers: withStablePlacement(withFadeIn(layers)),
  };
}
