// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { COLORS } from "../palette";

// USGS vegetation / wetland cover from the OSM area_builder (`areas` layer).
//  * wood / grass / glacier  → solid fills (woodland green persists to high zoom,
//    fixing the basemap landcover drop-out at z8)
//  * marsh / swamp / mangrove / orchard / vineyard / sand / gravel / scrub →
//    seamless `fill-pattern` tiles from the USGS sprite (tufts, dots, stipple).
// Pattern tiles are coloured on a transparent ground, so they read as a quad's
// hand-stippled symbology over the base earth; wetlands get a faint tint under
// the tufts. Only present inside downloaded regions (z9-15).

const SRC = { source: "overlay", "source-layer": "areas" };

// Each entry: kind (== `areas.kind` value) → sprite pattern name + fallback tint.
// Most kinds reuse the sprite of the same name; coastal/submerged kinds map onto
// the mud/reef sprites and carry a blue tidal ground (TINT below).
const PATTERN_KINDS = [
  { kind: "marsh", pattern: "marsh", tint: "#DCE9E2" },
  { kind: "swamp", pattern: "swamp", tint: "#D6E5DD" },
  { kind: "mangrove", pattern: "mangrove", tint: "#D2E3DA" },
  { kind: "orchard", pattern: "orchard", tint: "#E2EFD4" },
  { kind: "vineyard", pattern: "vineyard", tint: "#E4EFD6" },
  { kind: "sand", pattern: "sand", tint: COLORS.landcover.sand },
  { kind: "gravel", pattern: "gravel", tint: COLORS.landcover.rock },
  { kind: "scrub", pattern: "scrub", tint: COLORS.landcover.scrub },
  // Coastal / submerged
  { kind: "tidalflat", pattern: "mud", tint: COLORS.landcover.tidal },
  { kind: "reef", pattern: "reef", tint: COLORS.landcover.tidal },
];

function patternLayer({ kind, pattern, tint }) {
  return {
    id: `veg_${kind}`,
    ...SRC,
    type: "fill",
    minzoom: 9,
    filter: ["==", ["get", "kind"], kind],
    paint: {
      "fill-pattern": pattern,
      "fill-color": tint || "rgba(0,0,0,0)",   // fallback if pattern missing
      "fill-opacity": ["interpolate", ["linear"], ["zoom"], 9, 0, 11, 0.85, 15, 0.95],
    },
  };
}

export function buildVegetation() {
  return [
    // ── Solid cover: woodland green / grass / glacier ─────────────────────────
    {
      id: "veg_fill",
      ...SRC,
      type: "fill",
      minzoom: 9,
      filter: ["in", ["get", "kind"], ["literal", ["wood", "grass", "glacier"]]],
      paint: {
        "fill-color": ["match", ["get", "kind"],
          "wood", COLORS.landcover.wood,
          "grass", COLORS.landcover.grass,
          "glacier", COLORS.landcover.glacier,
          COLORS.landcover.wood,
        ],
        "fill-opacity": ["interpolate", ["linear"], ["zoom"], 9, 0.4, 12, 0.5, 15, 0.55],
      },
    },
    // ── Faint wetland tint beneath the marsh/swamp tufts ──────────────────────
    {
      id: "veg_wetland_tint",
      ...SRC,
      type: "fill",
      minzoom: 9,
      filter: ["in", ["get", "kind"], ["literal", ["marsh", "swamp", "mangrove"]]],
      paint: {
        "fill-color": COLORS.landcover.wetland,
        "fill-opacity": ["interpolate", ["linear"], ["zoom"], 9, 0, 11, 0.3, 15, 0.35],
      },
    },
    // ── Blue tidal ground beneath submerged / coastal flats + reefs ───────────
    // USGS prints submerged areas, foreshore flats and reefs over a pale blue
    // ground (vs the white land ground of inland marsh) — the "slightly
    // different colour" that signals water-covered terrain.
    {
      id: "veg_tidal_tint",
      ...SRC,
      type: "fill",
      minzoom: 7,
      filter: ["in", ["get", "kind"], ["literal", ["tidalflat", "reef"]]],
      paint: {
        "fill-color": COLORS.landcover.tidal,
        "fill-opacity": ["interpolate", ["linear"], ["zoom"], 7, 0, 9, 0.4, 15, 0.5],
      },
    },
    // ── Feathered edges ───────────────────────────────────────────────────────
    // USGS area symbology fades at its boundary rather than stopping at a hard
    // polygon line. A blurred line drawn along the ring in the fill's own colour
    // gives that soft vignette. Gentle for solid woodland, stronger for the
    // wetland/coastal tints so swamp edges dissolve into the terrain.
    {
      id: "veg_feather_veg",
      ...SRC,
      type: "line",
      minzoom: 9,
      filter: ["in", ["get", "kind"], ["literal", ["wood", "grass", "glacier"]]],
      paint: {
        "line-color": ["match", ["get", "kind"],
          "wood", COLORS.landcover.wood,
          "grass", COLORS.landcover.grass,
          "glacier", COLORS.landcover.glacier,
          COLORS.landcover.wood,
        ],
        "line-blur": ["interpolate", ["linear"], ["zoom"], 9, 2, 15, 5],
        "line-width": ["interpolate", ["linear"], ["zoom"], 9, 1.5, 15, 4],
        "line-opacity": 0.35,
      },
    },
    {
      id: "veg_feather_wet",
      ...SRC,
      type: "line",
      minzoom: 9,
      filter: ["in", ["get", "kind"],
        ["literal", ["marsh", "swamp", "mangrove", "tidalflat", "reef"]]],
      paint: {
        "line-color": ["match", ["get", "kind"],
          ["tidalflat", "reef"], COLORS.landcover.tidal,
          COLORS.landcover.wetland,
        ],
        "line-blur": ["interpolate", ["linear"], ["zoom"], 9, 3, 15, 9],
        "line-width": ["interpolate", ["linear"], ["zoom"], 9, 2, 15, 7],
        "line-opacity": ["interpolate", ["linear"], ["zoom"], 9, 0.3, 12, 0.5],
      },
    },
    // ── Pattern fills (one layer per kind for max renderer compatibility) ─────
    ...PATTERN_KINDS.map(patternLayer),
  ];
}
