// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Central colour palette for the entire map style.
// USGS 7.5-minute topographic quadrangle colour scheme.

export const COLORS = {
  // ── Base ──
  background:        "#F8F6F0",
  earth:             "#F8F6F0",

  // ── Water ──
  water:             "#B5D6E0",
  waterOutline:      "#9CC0CC",
  streamLine:        "#5E8CB0",   // USGS-style medium blue for streams/rivers/canals
  streamLabel:       "#2C567E",   // deep quad-sheet blue — high contrast on terrain

  // ── Landcover ──
  landcover: {
    forest:          "#BDD6AC",
    wood:            "#BDD6AC",
    grassland:       "#E3EDD2",
    grass:           "#E3EDD2",
    farmland:        "#F0EDE0",
    scrub:           "#E5E0C8",
    barren:          "#EDEAE0",
    glacier:         "#F0F4FF",
    urban_area:      "#E8E4DA",
    wetland:         "#C2D4BD",
    tidal:           "#C9E2EE",   // submerged / tidal-flat blue ground
    sand:            "#F2EDDF",
    rock:            "#E9E4D9",
  },

  // ── Landuse ──
  landuse: {
    park:            "#D0E4BF",
    national_park:   "#CDE0BA",
    nature_reserve:  "#C5DCB2",
    cemetery:        "#D5DFC9",
    hospital:        "#EADEDD",
    school:          "#E8E6DF",
    industrial:      "#E6E1DB",
    residential:     "#EAE6DE",
    military:        "#E8E4DC",
    commercial:      "#E8E4DD",
    pedestrian:      "#EAE8E3",
    aerodrome:       "#ECEAE5",
    pier:            "#ECEAE5",
    wetland:         "#C2D4BD",
    sand:            "#F2EDDF",
    bare_rock:       "#E9E4D9",
    glacier:         "#F0F4FF",
    wood:            "#BDD6AC",
    grass:           "#E3EDD2",
    meadow:          "#DAE8CB",
    scrub:           "#E5E0C8",
    farmland:        "#F0EDE0",
    forest:          "#BDD6AC",
    grassland:       "#E3EDD2",
    golf_course:     "#D4E6C4",
    recreation_ground: "#D8E7CB",
    pitch:           "#DDE8D2",
    zoo:             "#D2E4C4",
    barren:          "#EDEAE0",
    urban_area:      "#E8E4DA",
  },

  // ── Roads ──
  // USGS road classes: primary & secondary highways in red, light-duty roads as
  // a thin grey-cased cream line, unimproved/4WD as a grey dash. (The old Gaia
  // yellow major/minor fills are kept as aliases so tunnel/bridge layers that
  // still reference them don't break.)
  roads: {
    // Primary highway (motorway/trunk/primary) — solid red cased
    highwayCasing:   "#C21807",
    highwayFill:     "#F6B7B7",
    // Secondary highway (secondary) — red, lighter/thinner
    secondaryCasing: "#C94B3B",
    secondaryFill:   "#F8CFC9",
    // Light-duty road (tertiary/unclassified/residential) — topo yellow, white-cased
    lightCasing:     "#FFFFFF",
    lightFill:       "#E8B53C",
    // Unimproved / 4WD / service — grey dash
    unimproved:      "#6E665C",
    // Graded service / unpaved drivable road — USGS "unimproved road" double-dash.
    // A neutral warm grey, distinct from the yellow paved roads and the brown 4×4 track.
    service:         "#5E564C",
    serviceCasing:   "#FBF9F3",   // soft halo lifting the double-dash off terrain
    path:            "#666666",
    rail:            "#444039",
    tunnelDash:      "#BBBBBB",
    // Back-compat aliases (tunnels/bridges fall back to these)
    majorCasing:     "#C94B3B",
    majorFill:       "#F8CFC9",
    minorCasing:     "#7A736A",
    minorFill:       "#FBF9F3",
  },

  // ── Trails ──
  // Two-axis cartography (Gaia Topo × USGS quad):
  //   HUE   = highest permitted use   →  who can travel here
  //   TEXTURE/WEIGHT = physical class →  what it physically is
  // Gaia's convention: foot=charcoal, horse=green, bike=red, motor=purple, with
  // vehicle-width two-tracks shown as a USGS brown road. Colours are muted to sit
  // on the cream/green/brown terrain and stay distinct from the bright-red roads.
  trails: {
    // Hue by permitted use (Gaia-style: vibrant so use reads at a glance)
    foot:       "#1C1A17",  // foot only (hiking) — near-black, the hero line
    horse:      "#2E9C46",  // horse allowed (equestrian) — vibrant Gaia green
    bike:       "#DE3B1F",  // bike allowed (MTB) — vibrant Gaia red, distinct from road red
    moto:       "#8A46B4",  // motorized allowed (OHV/moto) — purple
    track:      "#8A5A2B",  // two-track / forest / 4x4 road — USGS dirt-road brown (width kept wider)
    trackInk:   "#5A3A1C",  // classic USGS 4×4 DOUBLE-track ink — dark sienna, the two parallel dashed rails
    steps:      "#5A4632",  // stairs — brown
    other:      "#555048",  // unclassified path — dark grey-brown

    // Structure
    casing:     "#FBF9F3",  // soft halo lifting trails off hillshade/landcover
    // Gaia-style pale-yellow glow drawn (blurred) under every dashed trail so
    // the line floats off green/cream terrain — subtle, not a highlighter.
    halo:       "#F4E9A6",
    bridge:     "#2A2620",  // bridge deck edge ticks
    difficult:  "#B5331F",  // technical/exposed (sac_scale ≥ demanding) warning red

    // Named long-distance routes (CDT/PCT/AT/CT…).
    // routeLine is always on (fades in like an interstate at z3, kept subtle);
    // routeHighlight is the optional, default-OFF "Long Trails" corridor in a
    // distinct rose so toggling it makes every long trail pop without clashing
    // with the foot/horse/bike/moto hues.
    routeLine:      "#B5421E",  // the route line itself — warm burnt orange
    routeHighlight: "#D85F9C",  // translucent rose corridor (toggle highlight)
    routeLabel:     "#8A2E12",  // route name ink

    label:      "#2A2620",  // trail name / ref label ink

    // Back-compat aliases (older code paths referencing these keys)
    path:       "#1C1A17",
    footway:    "#3A3833",
    bridleway:  "#2E9C46",
    cycleway:   "#DE3B1F",
  },

  // ── Wildfire & smoke (live opt-in overlay; NIFC / NOAA data) ──
  wildfire: {
    perimeter:     "#B81B06",   // perimeter outline — alarm red
    perimeterFill: "#E8442A",   // translucent burned-area wash
    glow:          "#FF7A33",   // soft outer glow around the incident point
    core:          "#D7301F",   // incident dot
    label:         "#7A1607",   // fire name / stats ink
    smoke:         "#6E645A",   // smoke plume wash — warm grey, opacity by density
  },

  // ── Buildings ──
  // USGS renders structures as solid dark blocks (near-black) at large scale.
  buildings:         "#4A443B",
  buildingsOutline:  "#2A2620",

  // ── Boundaries ──
  // USGS prints political boundaries in black with class-specific dash
  // signatures (national heaviest → city finest). Federal/park land gets a
  // tinted band instead (see landuse / vegetation).
  boundaries:        "#3A352E",   // generic / fallback
  boundary: {
    national:  "#2A2620",   // heavy dash-dot-dot
    state:     "#3A352E",   // dash-dot
    county:    "#4A443B",   // dash-dot-dot, finer
    city:      "#5A5248",   // dotted / fine dash (civil township & incorporated city)
    federal:   "#B0673E",   // federally administered park/reservation band (pinkish-brown)
  },

  // ── Infrastructure (railroads, power, pipelines, dams, levees) ──
  infra: {
    rail:       "#2A2620",   // railroad ink (crosstie line)
    railCasing: "#FBF9F3",   // halo lifting rail off terrain
    railMinor:  "#5A5248",   // tram/subway/light rail
    power:      "#5A5248",   // transmission line
    powerMinor: "#7A736A",
    pipeline:   "#6E5A44",   // pipeline ink
    dam:        "#2A2620",   // dam / weir (heavy)
    levee:      "#8A5A2B",   // levee embankment (USGS brown)
    label:      "#4A443B",
  },

  // ── Labels ──
  labels: {
    water:           "#446688",
    road:            "#333333",
    place:           "#222222",
    poi:             "#333333",
    country:         "#222222",
    // State/province names — a muted warm grey so the large state label reads as
    // background context (an atlas-style state name) without competing with the
    // near-black city labels for attention.
    region:          "#5B5346",
    // USGS topographic contour brown (VanDyke/sienna).
    contour:         "#9C5B26",
  },

};
