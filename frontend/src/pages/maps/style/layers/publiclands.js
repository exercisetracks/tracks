// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { COLORS } from "../palette";

// Public-land / ownership overlay from the OSM landuse_builder (`landuse` layer,
// in the unified `overlay` source). Default-on: it is the single source for
// public-land area names (National Forest / Wilderness / BLM / State / NP …),
// which were removed from the always-on POI layer.
//
// The builder emits, per managed area:
//   • a DISSOLVED (Multi)Polygon  → one category-tinted wash + one solid boundary
//   • a single POINT (named areas only) → exactly one label, anchored in the
//     area's largest part (so a scattered forest/BLM holding isn't labelled once
//     per parcel). Unnamed areas get the colour but no label.
// Layers split on geometry type: fill/outline filter Polygon, labels filter Point.
//
// Cartography (GAIA Topo reference): a soft category-hued wash under a gentle,
// translucent boundary BAND (not a hard survey line), with the area name printed
// in a medium, clearly-green category ink — title-case, repeated ALONG the edge
// on both sides. Category identity still comes from COLOUR (BLM gold vs forest
// green vs wilderness teal) so you read the land without a legend, but the edge
// and text are lifted toward the fill so the whole thing reads soft and green
// like GAIA rather than as bold, inky, letter-spaced quad-sheet lettering.

const SRC = { source: "overlay", "source-layer": "landuse" };
const IS_POLYGON = ["==", ["geometry-type"], "Polygon"];
const IS_POINT = ["==", ["geometry-type"], "Point"];
// The center label is the one Point per named area tagged `label`. The layer also
// carries `edge_label` sample points; the SELF boundary labels now come from the
// polygon ring (line-placement), but the `boundary_host` subset of those points
// still drives the mirrored enclosing-area name (see buildPublicLandBoundaryLabels).
const IS_CENTER_LABEL = ["all", IS_POINT, ["==", ["get", "label"], 1]];

// Fill wash by management category (`cat`) — distinct hues, kept translucent so
// terrain/contours/trails read through and overlapping categories (a wilderness
// inside a forest) deepen rather than muddy.
const DEFAULT_FILL = "#9CC6A2";
const FILL_BY_CAT = {
  park:       "#7FC2A0",   // National/State Park — fresh green
  forest:     "#9CCB7E",   // National Forest — leaf, yellow-green
  wilderness: "#3FA097",   // Wilderness — cooler teal-green (kept distinct from forest)
  federal:    "#8FC6A6",   // generic federal protected land — soft green
  blm:        "#E9C45F",   // BLM — signature public-land gold
  state:      "#BFD45F",   // state land — bright olive
  reserve:    "#5FBFB2",   // nature reserve — teal
  military:   "#D98E84",   // military — dusty red
  tribal:     "#E0A35C",   // tribal / reservation — sienna
};

// Deep, saturated shade of each category — the tonal ANCHOR the softer boundary
// band and the green label ink are both derived from (mixed back toward the
// fill), so the edge and the name still read as "this is <that land>" by hue
// without being the hard, inky survey line they used to be.
const DEFAULT_EDGE = "#1C5235";
const EDGE_BY_CAT = {
  park:       "#114B30",   // National Park / Monument — deep green
  forest:     "#2A4C16",   // National Forest — deep forest green
  wilderness: "#0B3F44",   // Wilderness — deep teal (distinct from forest's warm green)
  federal:    "#1C5235",   // generic federal protected land — deep green
  blm:        "#6E4E0B",   // BLM — dark amber (keeps the gold identity)
  state:      "#41500D",   // state land / state park — dark olive (was too light)
  reserve:    "#0F5249",   // nature reserve / open space — deep teal
  military:   "#5E241C",   // military — dark oxblood
  tribal:     "#5E380F",   // tribal / reservation — dark sienna
};

function mixHex(a, b, t) {
  const pa = parseInt(a.slice(1), 16), pb = parseInt(b.slice(1), 16);
  const lerp = (shift) => {
    const av = (pa >> shift) & 255, bv = (pb >> shift) & 255;
    return Math.round(av + (bv - av) * t);
  };
  return `#${[lerp(16), lerp(8), lerp(0)].map((v) => v.toString(16).padStart(2, "0")).join("")}`;
}

function matchByCat(byCat, fallback) {
  return ["match", ["get", "cat"], ...Object.entries(byCat).flat(), fallback];
}

// Derive a per-category colour by mixing the deep EDGE anchor back toward its
// own FILL wash — small t = still dark/inky, larger t = softer/lighter/greener.
function mixByCat(t) {
  return matchByCat(
    Object.fromEntries(Object.keys(FILL_BY_CAT).map(
      (cat) => [cat, mixHex(EDGE_BY_CAT[cat], FILL_BY_CAT[cat], t)])),
    mixHex(DEFAULT_EDGE, DEFAULT_FILL, t),
  );
}

const FILL = matchByCat(FILL_BY_CAT, DEFAULT_FILL);

// GAIA-style cartography (reference screenshot):
//   • The boundary is NOT a hard dark stroke but a SOFT, translucent, medium-
//     tone band in the category hue — EDGE lifted halfway to the fill (0.5) so
//     it reads as a gentle highlighter along the edge, blurred at the margins.
//   • The name is the hero: a clearly-GREEN (category-hued) MID-tone ink, drawn
//     title-case ALONG the line on both sides — EDGE lifted ~0.3 toward the
//     fill so it's unmistakably green (not near-black) yet still holds a halo'd
//     contrast over terrain. Both the center label and the along-line boundary
//     tags share this one ink so the whole system reads as one GAIA-like green.
const BAND = mixByCat(0.5);
const LABEL_INK = mixByCat(0.3);

// Fill + boundary — placed low in the stack (above basemap landcover, below
// roads/trails) so it tints the terrain without burying the line network.
export function buildPublicLands() {
  return [
    {
      id: "publiclands_fill",
      ...SRC,
      type: "fill",
      // Big public-land polygons (≥50km²) are graded down to z6 by the builder
      // (landuse_builder._area_min_zoom) so the wash can fade in several zoom
      // steps before the z9 detail band, rather than popping in all at once.
      minzoom: 6,
      filter: IS_POLYGON,
      paint: {
        "fill-color": FILL,
        // Hold a readable wash across the overview (you're reading extent, not
        // terrain), then from z8.5 fade well down so the relief/contours/trails
        // inside the public land read clearly once you've zoomed in to use them.
        "fill-opacity": ["interpolate", ["linear"], ["zoom"],
          6, 0, 6.6, 0.22, 8.5, 0.22, 11, 0.10, 15, 0.09],
      },
    },
    {
      id: "publiclands_outline",
      ...SRC,
      type: "line",
      minzoom: 6,
      filter: IS_POLYGON,
      layout: { "line-join": "round", "line-cap": "round" },
      paint: {
        // GAIA reference: a soft, medium-tone, translucent band in the category
        // hue — a wider, gently-blurred highlighter along the edge, NOT a bold
        // dark survey stroke. The band colour is EDGE lifted halfway to the fill.
        "line-color": BAND,
        "line-width": ["interpolate", ["linear"], ["zoom"], 6, 1.0, 11, 2.4, 14, 3.4],
        "line-opacity": ["interpolate", ["linear"], ["zoom"], 6, 0, 6.6, 0.55, 12, 0.75],
        // Gently soft margins — present and readable as a line, but never the hard
        // hairline of a survey stroke (GAIA's edge is soft but clearly a line).
        "line-blur": ["interpolate", ["linear"], ["zoom"], 6, 0.6, 14, 0.5],
      },
    },
  ];
}

// Area-name labels — one POINT per named area (geometry filter), placed with the
// other labels (after them so place/POI names win collisions). GAIA look: the
// name is a title-case, MEDIUM-GREEN (category-hued) label — same green as the
// along-line boundary tags below so the whole public-land system reads as one
// piece. This is the low/mid-zoom name (boundary tags only kick in at z12); it
// carries the area before you're close enough for the along-the-edge repeats.
export function buildPublicLandLabels() {
  return [
    {
      id: "publiclands_labels",
      ...SRC,
      type: "symbol",
      // Matches publiclands_fill's z6 floor so a big area's name can appear
      // as soon as its wash does; symbol-sort-key (min_zoom) keeps only the
      // few huge z6-graded areas contending for space at that zoom.
      minzoom: 6,
      // Starts filtered to NOTHING: with collision disabled style-wide
      // (withStablePlacement), density is owned by usePublicLandLabelRank,
      // which grid-ranks the loaded areas (importance-first, town names as
      // blockers) and swaps in a granted-name filter per integer zoom — the
      // baked min_zoom grade alone can't thin same-grade adjacent areas.
      filter: ["all", IS_CENTER_LABEL, false],
      layout: {
        "symbol-placement": "point",
        "text-field": ["coalesce", ["get", "name"], ["get", "land_type"]],
        "text-font": ["Noto Sans Regular"],
        "text-size": ["interpolate", ["linear"], ["zoom"], 7, 11, 11, 13, 14, 15.5],
        // GAIA prints the name in title case (as-tagged), not the old upright
        // letter-spaced caps — a hair of tracking keeps it airy without shouting.
        "text-letter-spacing": 0.02,
        "text-max-width": 8,
        "text-padding": 6,
        // Lower min_zoom = bigger/more important area = wins collisions.
        "symbol-sort-key": ["get", "min_zoom"],
      },
      paint: {
        "text-color": LABEL_INK,
        "text-halo-color": COLORS.background,
        "text-halo-width": 2.0,
        "text-halo-blur": 0.4,
        "text-opacity": ["interpolate", ["linear"], ["zoom"], 6, 0, 6.6, 0.95],
      },
    },
  ];
}

// Boundary labels: the area name repeated periodically ALONG its own edge — the
// classic quad-sheet "whose land this is right at the line" convention, curving
// with the boundary so it's obvious which line each name belongs to.

// Boundary labels from the baked sample POINTS (landuse_builder dissolve_groups),
// ONE layer that draws the whole GAIA duet: each area's own name on the INSIDE of
// its border (self, inward `side`) AND, for a nested area, its enclosing area's
// name mirrored to the OUTSIDE (host, outward `side` + `boundary_host`). The baked
// `side` already resolves inward-vs-outward per sample, so a single offset
// expression places each correctly — a Wilderness reads "Glacier Peak Wilderness"
// inside and "Okanogan-Wenatchee National Forest" outside, across the same line.
//
// Rotated to each sample's baked bearing rather than curved along the line: the
// host mirror can't come from line-placement (the enclosing forest's ring isn't
// AT the child's border — it's a separate, far-away polygon), and with big
// forests present, line-placing every polygon's own ring both fights the nested
// name for the shared edge and litters the rectangular region-clip edges. Curving
// would need the backend to emit the boundary as line segments tagged self/host.
// Self and host share the SAME anchor (host is baked on the child's ring at the
// self sample points), so they'd stack and collide — one gets culled, and it's
// always the enclosing forest's HOST name that loses, leaving nested wildernesses
// with no outside label. A wider push (each name well clear of the line, hence
// well clear of its opposite twin) + tiny padding lets BOTH survive.
// Asymmetric perpendicular offsets: an area's OWN name (self, inward) is pushed
// firmly INSIDE its boundary so it reads as clearly belonging to that land rather
// than straddling/overshooting the line (the "labels extend beyond the area"
// problem). The mirrored enclosing name (host, outward) only needs to clear the
// line, so it sits closer — which also keeps the self/host pair from stacking.
const SELF_OFFSET_EMS = 1.95;
const HOST_OFFSET_EMS = 1.2;
export function buildPublicLandBoundaryLabels() {
  return [
    {
      id: "publiclands_boundary_labels",
      ...SRC,
      type: "symbol",
      // Kick in a half-step later so the boundary spans enough screen pixels for
      // the name to sit cleanly inside rather than crowding the edge. Below this
      // the always-inside centered label (publiclands_labels) carries the name.
      minzoom: 12.5,
      filter: ["all", IS_POINT, ["==", ["get", "edge_label"], 1]],
      layout: {
        "text-field": ["coalesce", ["get", "name"], ["get", "land_type"]],
        "text-font": ["Noto Sans Regular"],
        // A hair smaller than before so the along-edge tags are quieter than the
        // centered area name and less prone to overshooting the boundary.
        "text-size": ["interpolate", ["linear"], ["zoom"], 8, 11, 16, 13.5],
        "text-letter-spacing": 0.01,
        // Baked `side`: self=inward, host=outward. Self is pushed well inside;
        // host clears the line just enough to print outside.
        "text-offset": ["case", ["==", ["get", "side"], 1],
                        ["literal", [0, -SELF_OFFSET_EMS]],
                        ["literal", [0, HOST_OFFSET_EMS]]],
        // Bearing precomputed per sample (already flipped to never render upside
        // down — see _bearing_for_text); "map" locks it to the line's direction.
        "text-rotate": ["get", "bearing"],
        "text-rotation-alignment": "map",
        "text-pitch-alignment": "map",
        // Thins the dense along-edge repeats by ~a third: consecutive same-side
        // labels sit close along the ring, so a wider collision box drops every
        // few. The self/host pair are separated PERPENDICULAR to the line by
        // ~3em (SELF+HOST offsets), well clear of this padding, so the outside
        // host mirror still survives.
        "text-padding": 5,
        "text-optional": true,
        // Bigger/more important areas (lower min_zoom) win collisions.
        "symbol-sort-key": ["get", "min_zoom"],
      },
      paint: {
        // Same medium-green category ink as the center label, so the name reads
        // identically whether centered or running along the edge (GAIA look).
        "text-color": LABEL_INK,
        "text-halo-color": COLORS.background,
        "text-halo-width": 1.8,
        "text-opacity": ["interpolate", ["linear"], ["zoom"], 12.5, 0, 13.1, 0.95],
      },
    },
  ];
}
