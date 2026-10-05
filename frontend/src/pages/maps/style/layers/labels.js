// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Label layers for the self-hosted map style. buildLabels() returns the
// MapLibre symbol-layer definitions (POI / place / water / contour text) plus
// the shared label expressions used across them — notably NAME_OR_KIND, which
// falls back to a friendly per-kind label when an OSM feature has no `name`.
// Colours come from ../palette so text stays in sync with the rest of the style;
// the kind→label map mirrors backend routes/poi.py `_kind_label`.
import { COLORS } from "../palette";
import { gateByMinZoom } from "../zoomGate";

// Friendly fallback label per kind, so every POI icon carries text even when OSM
// has no `name` (an unnamed viewpoint reads "Viewpoint", a bare shaft "Mine
// Shaft", etc.). Mirrors backend routes/poi.py `_kind_label`.
const KIND_LABELS = {
  peak: "Peak", camp_site: "Campground", campground: "Campground",
  spring: "Spring", hot_spring: "Hot Spring", well: "Well",
  drinking_water: "Drinking Water", mine: "Mine", mine_shaft: "Mine Shaft",
  quarry: "Quarry", cave: "Cave", falls: "Falls", viewpoint: "Viewpoint",
  trailhead: "Trailhead", ranger_station: "Ranger Station",
  picnic_site: "Picnic Area", shelter: "Shelter", wilderness_hut: "Hut",
  alpine_hut: "Alpine Hut", information: "Information", boat_ramp: "Boat Ramp",
  winter_rec: "Winter Recreation", monument: "Monument", tower: "Tower",
  substation: "Substation", power_plant: "Power Plant",
  gaging_station: "Gaging Station", pumping_plant: "Pumping Plant",
  tank: "Tank", aerodrome: "Airport", school: "School",
  place_of_worship: "Church", cemetery: "Cemetery", hospital: "Hospital",
  fuel: "Fuel", parking: "Parking", toilets: "Restroom",
  nature_reserve: "Nature Reserve", protected_area: "Protected Area",
  gap: "Gap", arroyo: "Arroyo", rapids: "Rapids", island: "Island",
  canal: "Canal", range: "Range",
};

// MapLibre expression: the feature's name, or its kind's friendly label if blank.
const KIND_LABEL_MATCH = ["match", ["get", "kind"],
  ...Object.entries(KIND_LABELS).flat(), ""];
const NAME_OR_KIND = ["case",
  ["all", ["has", "name"], ["!=", ["get", "name"], ""]], ["get", "name"],
  KIND_LABEL_MATCH,
];

// ── Importance-scaled sizing ──────────────────────────────────────────────────
// Scale a label/icon size by BOTH zoom and a per-feature importance property
// (population_rank for places, min_zoom for POIs) so major places read large and
// minor ones small. MapLibre forbids nesting a `["zoom"]` expression inside
// arithmetic, so we can't just multiply two ramps — instead we build a top-level
// zoom interpolate whose VALUE at each zoom stop is itself a data-driven
// interpolate (base × importance-multiplier baked into the stops).
//   zoomStops: [[zoom, baseSize], …]   dataStops: [[propValue, multiplier], …]
function sizeByZoomAnd(prop, def, zoomStops, dataStops) {
  const zi = ["interpolate", ["linear"], ["zoom"]];
  for (const [z, base] of zoomStops) {
    const di = ["interpolate", ["linear"], ["coalesce", ["get", prop], def]];
    for (const [v, mul] of dataStops) di.push(v, Math.round(base * mul * 100) / 100);
    zi.push(z, di);
  }
  return zi;
}

export function buildLabels() {
  return [
    {
      id: "labels_water_ocean",
      source: "basemap",
      "source-layer": "water",
      type: "symbol",
      minzoom: 0,
      filter: ["==", ["get", "kind"], "ocean"],
      layout: {
        "text-field": ["get", "name"],
        "text-font": ["Noto Sans Italic"],
        "text-size": ["interpolate", ["linear"], ["zoom"], 2, 10, 10, 16],
        "text-letter-spacing": 0.1,
        "text-max-width": 8,
        "symbol-placement": "point",
      },
      paint: {
        "text-color": COLORS.labels.water,
        "text-halo-color": COLORS.background,
        "text-halo-width": 1,
        "text-opacity": ["interpolate", ["linear"], ["zoom"], 0, 0, 0.5, 1],
      },
    },
    {
      id: "labels_water_lakes",
      source: "basemap",
      "source-layer": "water",
      type: "symbol",
      minzoom: 5,
      filter: ["in", ["get", "kind"], ["literal", ["lake", "reservoir"]]],
      layout: {
        "text-field": ["get", "name"],
        "text-font": ["Noto Sans Italic"],
        "text-size": ["interpolate", ["linear"], ["zoom"], 5, 9, 14, 14],
        "text-max-width": 7,
      },
      paint: {
        "text-color": COLORS.labels.water,
        "text-halo-color": COLORS.background,
        "text-halo-width": 1,
        "text-opacity": ["interpolate", ["linear"], ["zoom"], 5, 0, 5.5, 1],
      },
    },
    {
      id: "labels_water_seas",
      source: "basemap",
      "source-layer": "places",
      type: "symbol",
      minzoom: 2,
      filter: ["in", ["get", "kind"], ["literal", ["sea", "bay"]]],
      layout: {
        "text-field": ["get", "name"],
        "text-font": ["Noto Sans Italic"],
        "text-size": ["interpolate", ["linear"], ["zoom"], 2, 9, 8, 14],
        "text-max-width": 8,
        "symbol-placement": "point",
      },
      paint: {
        "text-color": COLORS.labels.water,
        "text-halo-color": COLORS.background,
        "text-halo-width": 1,
        "text-opacity": ["interpolate", ["linear"], ["zoom"], 2, 0, 2.5, 1],
      },
    },
    {
      id: "labels_place_country",
      source: "basemap",
      "source-layer": "places",
      type: "symbol",
      minzoom: 2,
      maxzoom: 8,
      filter: ["==", ["get", "kind"], "country"],
      layout: {
        "text-field": ["get", "name"],
        "text-font": ["Noto Sans Regular"],
        // Zoom-driven base scaled by how populous the country is, so the US/China
        // read larger than a microstate at the same zoom (see importance system).
        "text-size": sizeByZoomAnd("population_rank", 14,
          [[2, 11], [6, 17]], [[10, 0.85], [18, 1.15]]),
        "text-transform": "uppercase",
        "text-letter-spacing": 0.2,
        "text-max-width": 8,
      },
      paint: {
        "text-color": COLORS.labels.country,
        "text-halo-color": COLORS.background,
        "text-halo-width": 1.5,
        "text-opacity": ["interpolate", ["linear"], ["zoom"], 2, 0, 2.5, 1],
      },
    },
    // ── States / provinces (kind=region) ─────────────────────────────────────
    // Broken out of the locality layer (where they shared the small city ramp and
    // read tiny). A big, airy, muted atlas-style state name that carries the
    // overview from z4 and hands off to city/POI names by z9.
    {
      id: "labels_place_region",
      source: "basemap",
      "source-layer": "places",
      type: "symbol",
      minzoom: 4,
      maxzoom: 9,
      filter: ["==", ["get", "kind"], "region"],
      layout: {
        "text-field": ["get", "name"],
        "text-font": ["Noto Sans Regular"],
        // Much larger base (13→20) than the old shared 8→13 ramp, nudged by state
        // population so Texas/California sit a touch above Wyoming.
        "text-size": sizeByZoomAnd("population_rank", 13,
          [[4, 13], [6, 16], [9, 20]], [[11, 0.92], [15, 1.12]]),
        "text-transform": "uppercase",
        "text-letter-spacing": 0.14,
        "text-max-width": 7,
        "symbol-sort-key": ["get", "min_zoom"],
      },
      paint: {
        "text-color": COLORS.labels.region,
        "text-halo-color": COLORS.background,
        "text-halo-width": 2.0,
        "text-opacity": ["interpolate", ["linear"], ["zoom"], 4, 0, 4.6, 1],
      },
    },
    {
      id: "labels_place_locality",
      source: "basemap",
      "source-layer": "places",
      type: "symbol",
      minzoom: 5,
      filter: ["==", ["get", "kind"], "locality"],
      layout: {
        // Deterministic density: a town's name renders only once the camera
        // passes its baked min_zoom grade (metros first, hamlets last) —
        // collision is disabled style-wide (withStablePlacement), so this gate
        // replaces the culling without the pan reshuffle.
        "text-field": gateByMinZoom("min_zoom", 0, ["get", "name"], { from: 5 }),
        "text-font": ["Noto Sans Regular"],
        // Scaled by population_rank so a metropolis (rank ~14) reads clearly
        // larger than a small town (rank ~6) at the same zoom — the old flat
        // 8→13 ramp made every city the same tiny size.
        "text-size": sizeByZoomAnd("population_rank", 6,
          [[5, 11], [8, 13], [12, 15], [16, 16.5]], [[4, 0.8], [9, 1.0], [14, 1.4]]),
        "text-max-width": 8,
        // Lower min_zoom (more important place) wins collisions.
        "symbol-sort-key": ["get", "min_zoom"],
      },
      paint: {
        "text-color": COLORS.labels.place,
        "text-halo-color": COLORS.background,
        "text-halo-width": 1.5,
        "text-opacity": ["interpolate", ["linear"], ["zoom"], 5, 0, 5.5, 1],
      },
    },
    {
      id: "labels_place_county",
      source: "basemap",
      "source-layer": "places",
      type: "symbol",
      minzoom: 7,
      filter: ["==", ["get", "kind"], "county"],
      layout: {
        "text-field": ["get", "name"],
        "text-font": ["Noto Sans Regular"],
        "text-size": ["interpolate", ["linear"], ["zoom"], 7, 8, 10, 11],
        "text-transform": "uppercase",
        "text-letter-spacing": 0.1,
        "text-max-width": 8,
      },
      paint: {
        "text-color": COLORS.labels.place,
        "text-halo-color": COLORS.background,
        "text-halo-width": 1.5,
        "text-opacity": ["interpolate", ["linear"], ["zoom"], 7, 0, 7.5, 1],
      },
    },
    {
      id: "labels_roads_major",
      source: "basemap",
      "source-layer": "roads",
      type: "symbol",
      minzoom: 12,
      filter: ["in", ["get", "kind"], ["literal", ["highway", "major_road"]]],
      layout: {
        "text-field": ["get", "name"],
        "text-font": ["Noto Sans Regular"],
        "text-size": 10,
        "symbol-placement": "line",
      },
      paint: {
        "text-color": COLORS.labels.road,
        "text-halo-color": COLORS.background,
        "text-halo-width": 2,
        // Fade road labels in over a quarter zoom (12 → 12.25) like the road lines.
        "text-opacity": ["interpolate", ["linear"], ["zoom"], 12, 0, 12.25, 1],
      },
    },
    // POI icons and labels from poi_search DB (rendered via usePoiFeatures GeoJSON source).
    // Uses USGS quad-sheet style: raw symbol icons with name/elevation labels below.
    {
      id: "poi_db_icons",
      source: "poi_features",
      type: "symbol",
      // Exclude place-label kinds — they are already rendered by the basemap's
      // labels_place_locality/labels_place_county layers with correct coordinates.
      // Showing them here would produce duplicate labels at wrong positions.
      // Water sources (spring/well/drinking_water) are excluded too — they get a
      // dedicated always-on-top layer (poi_water_icons) so hikers never lose them
      // to icon-collision in dense areas.
      // Public-land AREA kinds (National Forest=forest, Wilderness/State Park=
      // nature_reserve, protected_area, national_park) are excluded — they now show
      // ONLY via the default-on Public Lands layer (one clean dissolved label per
      // area), so they don't linger as POI labels when Public Lands is toggled off.
      // `park` (small leisure parks) and `information` kiosks stay as real POIs.
      // `trailhead` is excluded here too — it gets a dedicated always-on-top
      // layer (poi_trailhead_icons) so trailheads are never culled by icon
      // collision, the same treatment water sources get.
      filter: ["!", ["in", ["get", "kind"], ["literal", [
        "locality", "neighbourhood", "macrohood", "microhood",
        "administrative", "country", "region", "county", "borough", "pseudo",
        "spring", "well", "drinking_water", "trailhead",
        "national_park", "protected_area", "nature_reserve", "forest", "wilderness",
      ]]]],
      layout: {
        // Deterministic density: usePoiFeatures bakes an `icon_zoom` grade into
        // every feature (utils/labelRank.js) — the zoom at which the icon wins a
        // slot in a world-aligned grid, importance-first. Collision is disabled
        // style-wide (withStablePlacement), so this gate is the only thinning,
        // and unlike collision it never reshuffles as you pan.
        "icon-image": gateByMinZoom("icon_zoom", 5, ["match", ["get", "kind"],
          "peak",           "peak",
          "camp_site",      "campground",
          "campground",     "campground",
          "wilderness_hut", "alpine_hut",
          "alpine_hut",     "alpine_hut",
          "mine",           "mine",
          "mine_shaft",     "mine_shaft",
          "quarry",         "quarry",
          "cave",           "cave",
          "spring",         "spring",
          "hot_spring",     "spring",
          "well",           "well",
          "tank",           "tank",
          "gaging_station", "gaging_station",
          "pumping_plant",  "pumping_plant",
          "tower",          "tower",
          "substation",     "substation",
          "power_plant",    "substation",
          "falls",          "falls",
          "boat_ramp",      "boat_ramp",
          "winter_rec",     "winter_rec",
          "benchmark",      "benchmark",
          "spot_elevation", "spot_elevation",
          "monument",       "monument",
          "viewpoint",      "viewpoint",
          "information",    "information",
          "picnic_site",    "picnic",
          "shelter",        "shelter",
          "ranger_station", "ranger_station",
          "parking",        "parking",
          "cemetery",       "cemetery",
          "place_of_worship", "place_of_worship",
          "trailhead",      "trailhead",
          "park",           "park",
          "national_park",  "park",
          "nature_reserve", "park",
          "protected_area", "park",
          "forest",         "forest",
          "locality",       "townspot",
          "aerodrome",      "aerodrome",
          "fuel",           "fuel",
          "hospital",       "hospital",
          "school",         "school",
          "restaurant",     "restaurant",
          "cafe",           "cafe",
          "fast_food",      "fast_food",
          "bar",            "bar",
          "pub",            "bar",
          "supermarket",    "supermarket",
          "convenience",    "convenience",
          "drinking_water", "drinking_water",
          "toilets",        "toilets",
          "library",        "library",
          "museum",         "museum",
          "theatre",        "theatre",
          ""
        ], { from: 4 }),
        // Icons grow gently with zoom for a hand-drawn topo feel, and are scaled
        // by importance (baked min_zoom, lower = more prominent) so a major peak
        // or landmark draws a larger icon than an incidental POI.
        "icon-size": sizeByZoomAnd("min_zoom", 13,
          [[8, 0.75], [12, 0.95], [16, 1.2]], [[6, 1.18], [11, 1.0], [15, 0.92]]),
        // Peaks: name above, elevation below. Benchmarks: "BM <ele>". Spot
        // elevations: bare elevation. Everything else: name only — the classic
        // USGS control-point treatment.
        // Peaks: name (or "Peak") above, elevation below. Benchmarks: "BM <ele>".
        // Spot elevations: bare elevation. Everything else: name, falling back to
        // a friendly type label so no icon is ever left unnamed.
        // Labels take up a lot of visual space at overview zooms — hold every
        // POI name back until z12, same threshold as the water/trailhead
        // layers below, so the map reads as icons-only until you're zoomed
        // in enough to want names. From z12 the name renders only once the
        // feature's baked `label_zoom` grid grade (utils/labelRank.js) is
        // reached, so a downtown full of same-importance shops fills in
        // name-by-name as you zoom instead of printing all at once.
        "text-field": gateByMinZoom("label_zoom", 12, (z) => z < 12 ? "" : ["case",
            ["all", ["==", ["get", "kind"], "peak"], ["!=", ["get", "ele_ft"], null]],
            ["format",
              NAME_OR_KIND, {"font-scale": 1.0},
              "\n", {},
              ["concat", ["to-string", ["round", ["get", "ele_ft"]]], " ft"],
              {"font-scale": 0.8}
            ],
            ["all", ["==", ["get", "kind"], "benchmark"], ["!=", ["get", "ele_ft"], null]],
            ["concat", "BM ", ["to-string", ["round", ["get", "ele_ft"]]]],
            ["==", ["get", "kind"], "spot_elevation"],
            ["case", ["!=", ["get", "ele_ft"], null],
              ["concat", ["to-string", ["round", ["get", "ele_ft"]]]], ""],
            NAME_OR_KIND
          ], { from: 11 }),
        // Serif italic is the classic USGS treatment for natural-feature names.
        "text-font": ["Noto Sans Italic"],
        // Scaled by importance (baked min_zoom) so a prominent peak/landmark name
        // reads larger than an incidental POI, matching its larger icon above.
        "text-size": sizeByZoomAnd("min_zoom", 13,
          [[8, 11.5], [12, 12.5], [16, 14.5]], [[6, 1.18], [11, 1.0], [15, 0.9]]),
        "text-anchor": "top",
        "text-offset": [0, 1.1],
        "text-max-width": 8,
        "text-optional": true,
        // Lower min_zoom = more important = render on top when symbols collide.
        "symbol-sort-key": ["get", "min_zoom"],
      },
      paint: {
        // Appear early (z5→6) so named features show up at overview zooms.
        "icon-opacity": ["interpolate", ["linear"], ["zoom"], 5, 0, 6, 1],
        "text-color": COLORS.labels.poi,
        "text-halo-color": COLORS.background,
        "text-halo-width": 1.5,
        // Text itself only exists from z12 (see text-field step above); this
        // just gives it a quick fade-in rather than a hard pop-in.
        "text-opacity": ["interpolate", ["linear"], ["zoom"], 12, 0, 12.5, 1],
      },
    },
    // ── Water sources (spring / well / drinking water) ────────────────────────
    // Drawn last + with icon-allow-overlap so the things hikers care about most
    // never get culled in dense terrain. Slightly larger, blue label ink.
    {
      id: "poi_water_icons",
      source: "poi_features",
      type: "symbol",
      minzoom: 10,
      filter: ["in", ["get", "kind"], ["literal", ["spring", "well", "drinking_water"]]],
      layout: {
        "icon-image": ["match", ["get", "kind"],
          "spring", "spring",
          "well", "well",
          "drinking_water", "drinking_water",
          "spring",
        ],
        "icon-size": ["interpolate", ["linear"], ["zoom"], 10, 0.95, 13, 1.3, 16, 1.6],
        "icon-allow-overlap": true,
        "icon-optional": false,
        "text-field": ["step", ["zoom"], "", 12, NAME_OR_KIND],
        "text-font": ["Noto Sans Italic"],
        "text-size": ["interpolate", ["linear"], ["zoom"], 11, 11.5, 16, 14.5],
        "text-anchor": "top",
        "text-offset": [0, 1.0],
        "text-max-width": 8,
        "text-optional": true,
      },
      paint: {
        "icon-opacity": ["interpolate", ["linear"], ["zoom"], 10, 0, 11, 1],
        "text-color": COLORS.labels.water,
        "text-halo-color": COLORS.background,
        "text-halo-width": 1.5,
        "text-opacity": ["interpolate", ["linear"], ["zoom"], 12, 0, 12.5, 1],
      },
    },
    // ── Trailheads ────────────────────────────────────────────────────────────
    // The single most important navigation POI for a hiker — give it the same
    // always-on-top, overlap-allowed treatment as water sources so trailheads
    // are never dropped to icon collision in dense areas.
    {
      id: "poi_trailhead_icons",
      source: "poi_features",
      type: "symbol",
      minzoom: 10,
      filter: ["==", ["get", "kind"], "trailhead"],
      layout: {
        "icon-image": "trailhead",
        "icon-size": ["interpolate", ["linear"], ["zoom"], 10, 0.85, 13, 1.15, 16, 1.4],
        "icon-allow-overlap": true,
        "icon-optional": false,
        "text-field": ["step", ["zoom"], "", 12, NAME_OR_KIND],
        "text-font": ["Noto Sans Italic"],
        "text-size": ["interpolate", ["linear"], ["zoom"], 11, 11.5, 16, 14.5],
        "text-anchor": "top",
        "text-offset": [0, 1.0],
        "text-max-width": 8,
        "text-optional": true,
      },
      paint: {
        "icon-opacity": ["interpolate", ["linear"], ["zoom"], 10, 0, 11, 1],
        "text-color": COLORS.labels.poi,
        "text-halo-color": COLORS.background,
        "text-halo-width": 1.5,
        "text-opacity": ["interpolate", ["linear"], ["zoom"], 12, 0, 12.5, 1],
      },
    },
  ];
}
