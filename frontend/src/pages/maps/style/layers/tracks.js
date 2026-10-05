// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { COLORS } from "../palette";

// ── Unpaved / backcountry drivable roads ──────────────────────────────────────
// The two-track 4×4 network + graded service roads — DECOUPLED from the foot/bike/
// horse trails module. These are roads (you drive them), so they read as a road
// hierarchy distinct from the paved roads (roads.js) and the singletrack trails
// (trails.js):
//
//   service road  = graded gravel/dirt road (drivable in a car) — a SINGLE grey
//                   dash. Appears a half step BEFORE the 4×4 track.
//   4×4 / track   = rough two-track / forest road — the classic USGS DOUBLE-track:
//                   two parallel dashed dark-sienna rails with the terrain showing
//                   between them (line-gap-width opens the centre).
//
// The single-vs-double dash + grey-vs-sienna keeps the two cleanly distinct, and
// the double-track reads as the iconic jeep-trail symbol rather than the old
// brown-dash-on-a-white-band ("brown white thing").
//
// Sourced ONLY from the OSM overlay (`overlay`/trails layer): it carries
// kind=service|track graded from real OSM tags (tracktype/4wd_only/surface), so
// service roads read genuinely distinct from the 4×4
// tracks. The old coarse basemap fallback was removed — it double-drew over this
// (grey dual-track over the brown 4×4) — so 4×4/service roads, like trails/water,
// are present only inside downloaded regions.

// Both unpaved-road classes hold back until ~z13: at z9-12 they buried the map
// (especially in towns, where every gravel alley/yard road is kind=service) —
// the paved road network and the trails carry the overview, and the unpaved
// grid fades in only once you're close enough to actually drive it.
// Service roads lead the 4×4 tracks by a half step, as before.
const TRACK_FADE = ["interpolate", ["linear"], ["zoom"],
  13, 0, 13.5, 0.6, 14.5, 0.85, 16, 1];
const SERVICE_FADE = ["interpolate", ["linear"], ["zoom"], 12.5, 0, 13, 0.9, 16, 1];

const OSM = { source: "overlay", "source-layer": "trails" };

const isFaint = ["in", ["get", "trail_visibility"], ["literal", ["bad", "horrible", "no"]]];

// Gap between the two rails of the 4×4 double-track (the terrain shows through it).
const TRACK_GAP = ["interpolate", ["exponential", 1.5], ["zoom"], 10, 1.4, 12, 2.0, 14, 2.8, 16, 4.2];

// ── OSM rich unpaved roads (downloaded regions) ───────────────────────────────
export function buildOsmTracks() {
  return [
    // Graded service road — a SINGLE grey dash, cased. Leads the 4×4 tracks.
    {
      id: "osm_tracks_service_casing",
      ...OSM,
      type: "line",
      minzoom: 12.5,
      filter: ["==", ["get", "kind"], "service"],
      layout: { "line-cap": "round", "line-join": "round" },
      paint: {
        "line-color": COLORS.roads.serviceCasing,
        "line-width": ["interpolate", ["exponential", 1.6], ["zoom"], 11, 2.2, 13, 3.0, 16, 5.2],
        "line-opacity": ["interpolate", ["linear"], ["zoom"], 12.5, 0, 13, 0.5, 16, 0.55],
      },
    },
    {
      id: "osm_tracks_service",
      ...OSM,
      type: "line",
      minzoom: 12.5,
      filter: ["==", ["get", "kind"], "service"],
      layout: { "line-cap": "butt", "line-join": "round" },
      paint: {
        "line-color": COLORS.roads.service,
        "line-width": ["interpolate", ["exponential", 1.6], ["zoom"], 11, 0.9, 13, 1.3, 16, 2.2],
        "line-dasharray": [3.5, 2],
        "line-opacity": SERVICE_FADE,
      },
    },

    // 4×4 / forest road — classic USGS DOUBLE-track. A faint pale halo (drawn as
    // a matching double stroke) lifts it off hillshade, then two parallel dashed
    // dark-sienna rails sit on top with the terrain showing between them. Drawn
    // last so the hero backcountry road reads on top of the graded service roads.
    {
      id: "osm_tracks_track_casing",
      ...OSM,
      type: "line",
      minzoom: 12.5,
      filter: ["all", ["==", ["get", "kind"], "track"], ["!", isFaint]],
      layout: { "line-cap": "butt", "line-join": "round" },
      paint: {
        "line-color": COLORS.background,
        "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 10, 1.3, 12, 1.7, 14, 2.2, 16, 2.8],
        "line-gap-width": TRACK_GAP,
        "line-opacity": ["interpolate", ["linear"], ["zoom"], 13, 0, 13.5, 0.45, 16, 0.5],
      },
    },
    {
      id: "osm_tracks_track",
      ...OSM,
      type: "line",
      minzoom: 12.5,
      filter: ["all", ["==", ["get", "kind"], "track"], ["!", isFaint]],
      layout: { "line-cap": "butt", "line-join": "round" },
      paint: {
        "line-color": COLORS.trails.trackInk,
        "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 10, 0.6, 12, 0.9, 14, 1.1, 16, 1.5],
        "line-gap-width": TRACK_GAP,
        "line-dasharray": [3, 2.2],
        "line-opacity": TRACK_FADE,
      },
    },
  ];
}
