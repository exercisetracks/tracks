// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { COLORS } from "../palette";
import { routeIconExpression, routeColorExpression } from "../../trailLogos";
import { gateByMinZoom } from "../zoomGate";

// ── Long Trails (CDT/PCT/AT/CT/ADT…) ──────────────────────────────────────────
// The region-independent long-route overview (master_routes), tiled as a single
// `routes` layer. All layers here are visibility:none by default and grouped
// under the "Long Trails" toggle. Every route line shares ROUTE_MIN_ZOOM (z4) in
// the tiles, so the whole network fades in TOGETHER like the interstates instead
// of trails popping in one after another.
const ROUTES_SRC = { source: "routes_osm", "source-layer": "routes" };

// Line features only (exclude the section/badge Point markers, which also carry
// route=1 so the line layers must not pick them up as text/labels).
const IS_LINE = ["all", ["==", ["get", "route"], 1], ["!", ["has", "marker"]]];

// Smooth fade-in at z3 so long trails materialise like highways, not pop in (one
// zoom sooner than the rest of the map detail). The per-feature min_zoom in the
// tiles is ≤ z3, so the feature already exists when the opacity ramp starts.
const fade = (lo, hi, max = 1) => ["interpolate", ["linear"], ["zoom"], lo, 0, hi, max];

// Vertical fan-out where several long trails share one tread (PCT + Tahoe Rim):
// each parent carries a stable `slot` (0..4) → offset the badge so shields stack
// separately instead of piling into one blob (offset is in icon-size units).
const SLOT_OFFSET = ["match", ["to-number", ["coalesce", ["get", "slot"], 2]],
  0, ["literal", [0, -30]],
  1, ["literal", [0, -15]],
  3, ["literal", [0, 15]],
  4, ["literal", [0, 30]],
  ["literal", [0, 0]]];

// Corridor under the trail ways — a translucent route-coloured band so the line
// pops. Returned separately so it can sit BELOW the OSM trail ways in the stack.
export function buildLongTrailsBase() {
  return [
    {
      id: "osm_trails_route_highlight",
      ...ROUTES_SRC,
      type: "line",
      minzoom: 3,
      filter: IS_LINE,
      layout: { "line-cap": "round", "line-join": "round", "visibility": "none" },
      paint: {
        "line-color": routeColorExpression(),
        "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 6, 3, 9, 5, 12, 9, 15, 14],
        // Fade in with the rest at z3, then hold a subtle corridor opacity.
        "line-opacity": ["interpolate", ["linear"], ["zoom"], 3, 0, 4.5, 0.24, 12, 0.26, 15, 0.28],
        "line-blur": 0.6,
      },
    },
  ];
}

// Route line, hover glow, names, anchored emblem badges and section markers —
// drawn ABOVE the OSM trail ways.
export function buildLongTrailsTop() {
  return [
    // ── Invisible wide hit target — makes long trails easy to click/hover ──────
    // A transparent fat line under the route line; queryRenderedFeatures + the
    // mousemove hover both hit-test it, so the clickable corridor is ~20px wide
    // instead of the 2-3px painted line. Carries the same feat_key (promoteId).
    {
      id: "osm_trails_route_hit",
      ...ROUTES_SRC,
      type: "line",
      minzoom: 3,
      filter: IS_LINE,
      layout: { "line-cap": "round", "line-join": "round", "visibility": "none" },
      paint: {
        "line-color": "#000000",
        "line-opacity": 0,
        "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 4, 14, 9, 18, 12, 22, 16, 28],
      },
    },

    // ── Hover glow (feature-state driven — no per-mousemove setFilter) ─────────
    // useRouteHover sets {hover:true} on the feature-state of the hovered section
    // (all its way-features share a promoted `feat_key`), so the whole section
    // lights up on the GPU. A soft white halo, tighter than the old glow.
    {
      id: "osm_trails_route_hover",
      ...ROUTES_SRC,
      type: "line",
      minzoom: 3,
      filter: IS_LINE,
      layout: { "line-cap": "round", "line-join": "round", "visibility": "none" },
      paint: {
        "line-color": "#FFFFFF",
        "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 6, 5, 9, 8, 12, 12, 16, 17],
        "line-opacity": ["case", ["boolean", ["feature-state", "hover"], false], 0.5, 0],
        "line-blur": 1.0,
      },
    },

    // ── Named route line — the bold long-distance line in the trail's colour ──
    {
      id: "osm_trails_route_line",
      ...ROUTES_SRC,
      type: "line",
      minzoom: 3,
      filter: IS_LINE,
      layout: { "line-cap": "round", "line-join": "round", "visibility": "none" },
      paint: {
        "line-color": routeColorExpression(),
        "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 3, 0.9, 6, 1.4, 9, 2.0, 12, 2.6, 16, 3.2],
        "line-opacity": fade(3, 4, 0.9),
      },
    },

    // ── Route names ───────────────────────────────────────────────────────────
    {
      id: "osm_trails_route_labels",
      ...ROUTES_SRC,
      type: "symbol",
      minzoom: 7,
      filter: ["all", IS_LINE, ["has", "name"]],
      layout: {
        "visibility": "none",
        "text-field": ["get", "name"],
        "text-font": ["Noto Sans Regular"],
        "text-size": ["interpolate", ["linear"], ["zoom"], 9, 11, 14, 14.5],
        "symbol-placement": "line",
        "symbol-spacing": 350,
        "text-max-angle": 40,
        "text-letter-spacing": 0.04,
        "text-padding": 6,
      },
      paint: {
        "text-color": COLORS.trails.routeLabel,
        "text-halo-color": COLORS.background,
        "text-halo-width": 1.8,
        "text-opacity": fade(7, 7.5),
      },
    },

    // ── Long-trail emblem badges — ANCHORED point markers, collision-culled ───
    // Baked as Point features at fixed ~12 km intervals along each trail with a
    // tiered per-badge min_zoom, so badges densify as you zoom but never drift
    // (point placement, not line placement). Collision culling (allow-overlap
    // OFF + generous icon-padding) keeps the on-screen badge count reasonable at
    // every zoom — MapLibre drops badges that would overlap an already-placed one,
    // so a continent of trails at low zoom shows a sparse, legible set of shields.
    {
      id: "osm_trails_route_icons",
      ...ROUTES_SRC,
      type: "symbol",
      // minzoom is DYNAMIC (useLayerToggles.setBadgeZoom): 4 with the Long
      // Trails coloured line on (badges accompany a visible line from z3), 9
      // with it off — so a shield never floats over terrain with no rendered
      // trail under it (the trail network itself only draws from z9).
      minzoom: 9,
      filter: ["==", ["get", "marker"], "badge"],
      // Always visible (not part of the Long Trails toggle): the sparse emblem
      // shields mark where the long trails run even when the coloured-line
      // emphasis is off. Clicking a badge still opens the route detail.
      layout: {
        // Deterministic density: a badge renders only once the camera reaches
        // its baked zoom tier (sparse skeleton first), so zooming in spawns new
        // shields between the existing ones and panning never reshuffles them.
        // Collision is disabled style-wide (see withStablePlacement) — the tier
        // gate is the only thinning. (The def of 11 keeps a pre-`tier` archive
        // rendering sanely: untiered badges appear from z11.)
        "icon-image": gateByMinZoom("tier", 11, routeIconExpression(), { from: 3 }),
        "icon-size": ["interpolate", ["linear"], ["zoom"], 5, 0.5, 9, 0.78, 13, 1.05],
        "icon-offset": SLOT_OFFSET,
        // Draw order within the layer: skeleton tiers first, then by the stable
        // per-badge index.
        "symbol-sort-key": ["+",
          ["*", ["coalesce", ["get", "tier"], 11], 100000],
          ["coalesce", ["get", "bidx"], 0]],
      },
      paint: { "icon-opacity": fade(4, 5) },
    },

    // ── Section boundary markers — one node per section (click → detail) ───────
    {
      id: "osm_trails_section_markers",
      ...ROUTES_SRC,
      type: "circle",
      minzoom: 10,
      filter: ["all", ["==", ["get", "marker"], "section"], ["==", ["geometry-type"], "Point"]],
      layout: { "visibility": "none" },
      paint: {
        "circle-radius": ["interpolate", ["linear"], ["zoom"], 10, 2.6, 14, 5.5],
        "circle-color": "#FFFFFF",
        "circle-stroke-color": routeColorExpression(),
        "circle-stroke-width": ["interpolate", ["linear"], ["zoom"], 10, 1.3, 14, 2.2],
        "circle-opacity": ["interpolate", ["linear"], ["zoom"], 10, 0, 10.5, 0.95],
      },
    },
  ];
}
