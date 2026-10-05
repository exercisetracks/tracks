// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// useLayerToggles — manages which map layer groups are visible. LAYER_GROUPS
// below declares each toggle (hillshade, trails, POIs, …) and the concrete
// MapLibre layer ids it controls; the hook tracks the on/off state and applies
// it via setLayoutProperty (wrapped in try/catch so toggling a not-yet-added
// layer, e.g. hillshade before DEM download, is a safe no-op).
import { useState, useCallback } from "react";

const LAYER_GROUPS = [
  {
    id: "hillshade",
    label: "Hillshade",
    layers: ["hillshade", "hillshade_overview"],
    default: true,
    // Layer is added dynamically once DEM tiles are downloaded; setLayoutProperty
    // inside setVisibility wraps in try/catch, so this is safely a no-op until then.
  },
  {
    id: "water",
    label: "Water",
    layers: [
      "water_polygons", "water_rivers",
      "water_osm_rivers", "water_osm_canals", "water_osm_streams",
      "water_osm_ditches", "water_osm_intermittent", "water_osm_labels",
    ],
    default: true,
  },
  {
    id: "roads",
    label: "Roads",
    layers: [
      "roads_tunnels_casing", "roads_tunnels_fill",
      "roads_light_casing", "roads_light_fill",
      // OSM overlay paved light-duty roads (early-fading, downloaded regions).
      "roads_osm_light_casing", "roads_osm_light_fill",
      "roads_secondary_casing", "roads_secondary_fill",
      "roads_primary_casing", "roads_primary_fill", "roads_primary_median",
      "roads_rail",
      "roads_bridges_casing", "roads_bridges_fill",
      // Unpaved drivable roads (decoupled from trails) — graded service + 4×4 track,
      // from the OSM overlay only. Grouped with Roads to match the legend.
      "osm_tracks_track_casing", "osm_tracks_track",
      "osm_tracks_service_casing", "osm_tracks_service",
    ],
    default: true,
  },
  {
    id: "trails",
    label: "Trails",
    layers: [
      "trails_casing", "trails_path", "trails_footway",
      "trails_bridleway", "trails_cycleway", "trails_steps", "trails_other",
      "trails_labels",
      "osm_trails_casing", "osm_trails_bridge",
      "osm_trails_unpaved", "osm_trails_paved",
      "osm_trails_faint", "osm_trails_difficult", "osm_trails_steps",
      "osm_trails_oneway", "osm_trails_labels",
    ],
    default: true,
  },
  {
    id: "long_trails",
    label: "Long Trails",
    // Off by default. Enables the long-distance-route EMPHASIS (CDT/PCT/AT/CT…):
    // the per-trail coloured line + highlight corridor, the route names, and the
    // section markers. The emblem BADGES are intentionally NOT here — they stay
    // visible (collision-culled, so sparse) even with the layer off, marking
    // where the long trails run without the coloured-line treatment.
    layers: ["osm_trails_route_highlight", "osm_trails_route_line",
             "osm_trails_route_labels",
             "osm_trails_section_markers", "osm_trails_route_hover",
             "osm_trails_route_hit"],
    default: false,
  },
  {
    id: "custom_tracks",
    label: "My Tracks",
    // The user's saved courses + any device-discovered ones. On by default so a
    // freshly-saved track appears immediately; data is fed by useCustomTracks.
    layers: ["custom_tracks_hit", "custom_tracks_hover", "custom_tracks_casing",
             "custom_tracks_line", "custom_tracks_line_external", "custom_tracks_arrows"],
    default: true,
  },
  {
    id: "activity_tracks",
    label: "Activity Tracks",
    // Past activities as theme-coloured lines (data fed by useActivityTracks).
    layers: ["activity_tracks_hit", "activity_tracks_hover", "activity_tracks_line", "activity_tracks_arrows"],
    default: true,
  },
  {
    id: "landcover",
    label: "Landcover",
    layers: ["landcover"],
    default: true,
  },
  // "Parks & Landuse" toggle removed: the basemap landuse wash it controlled was
  // deleted (it dimmed water + hillshade — see layers/landuse.js). The remaining
  // `landuse` layer is invisible (query-only) and needs no toggle.
  {
    id: "publiclands",
    label: "Public Lands",
    // Everything the OSM public-land overlay draws: the wash, the boundary band,
    // the center name, AND the along-line boundary-label layer — so toggling this
    // off removes ALL of it (the boundary tags used to linger).
    layers: ["publiclands_fill", "publiclands_outline", "publiclands_labels",
             "publiclands_boundary_labels"],
    // On by default: the public-land area names (NF/Wilderness/BLM/…) are now shown
    // ONLY here (removed from the always-on POI layer), so this is the single,
    // readable source for them. Only present in downloaded areas.
    default: true,
    section: "highres", // overlay landuse layer — only present in downloaded areas
  },
  {
    id: "buildings",
    label: "Buildings",
    layers: ["buildings"],
    default: true,
    section: "highres", // basemap z14+ — only present in downloaded areas
  },
  {
    id: "boundaries",
    label: "Boundaries",
    layers: ["boundaries_national", "boundaries_state", "boundaries_county", "boundaries_city"],
    default: true,
  },
  {
    id: "labels",
    label: "Labels",
    layers: ["labels_water_ocean", "labels_water_lakes", "labels_water_seas", "labels_place_country", "labels_place_region", "labels_place_locality", "labels_place_county", "labels_roads_major"],
    default: true,
  },
  {
    id: "poi_db_icons",
    label: "POI Icons",
    layers: ["poi_db_icons", "poi_water_icons"],
    default: true,
  },
  {
    id: "contours",
    label: "Contours",
    layers: ["contours_index", "contours_intermediate", "contours_labels"],
    default: true,
    section: "highres", // master_contours — regional DEM only
  },
  {
    id: "vegetation",
    label: "Vegetation",
    layers: [
      "veg_fill", "veg_wetland_tint", "veg_tidal_tint",
      "veg_feather_veg", "veg_feather_wet",
      "veg_marsh", "veg_swamp", "veg_mangrove", "veg_orchard",
      "veg_vineyard", "veg_sand", "veg_gravel", "veg_scrub",
      "veg_tidalflat", "veg_reef",
    ],
    default: true,
    section: "highres", // areas_osm — only present in downloaded areas
  },
  {
    id: "infrastructure",
    label: "Infrastructure",
    layers: [
      "infra_power_minor", "infra_power_line", "infra_power_pylons",
      "infra_pipeline", "infra_pipeline_label",
      "infra_dam", "infra_levee", "infra_levee_ticks",
      "infra_rail_minor", "infra_rail_casing", "infra_rail_line", "infra_rail_ties",
    ],
    default: true,
    section: "highres", // infra_osm — only present in downloaded areas
  },
  {
    id: "wildfires",
    label: "Wildfires & Smoke",
    // One toggle for the whole live-fire picture: NIFC/CWFIS incident points +
    // perimeters AND the NOAA smoke plumes (useWildfires). Off by default and
    // fetches only when BOTH this toggle and the account-level wildfire opt-in
    // are on — the LayerPanel disables the toggle until the setting is enabled.
    layers: ["wildfire_perimeter_fill", "wildfire_perimeter_line",
             "wildfire_points_glow", "wildfire_points_core", "wildfire_labels",
             "smoke_fill", "smoke_outline"],
    default: false,
    section: "live",
  },
  {
    id: "grid",
    label: "Grid & Graticule",
    // No MapLibre layers: the grid is a canvas HUD (useGridOverlay) that reads
    // this toggle's state directly. Empty `layers` keeps the setVisibility loop a
    // no-op while the panel still shows the toggle.
    layers: [],
    default: false,
  },
];

// The long-trail emblem badges are NOT part of any toggle (always visible),
// but their minimum zoom follows the Long Trails toggle: with the coloured
// route line ON badges may appear from z4 (they sit on a visible line); with
// it OFF they wait for the real trail network at z9, so a shield never floats
// over terrain with no rendered trail under it.
const BADGE_LAYER = "osm_trails_route_icons";
function setBadgeZoom(m, longTrailsOn) {
  try {
    m.setLayerZoomRange(BADGE_LAYER, longTrailsOn ? 4 : 9, 24);
  } catch { /* layer not added yet */ }
}

export function useLayerToggles(map) {
  const [toggles, setToggles] = useState(() => {
    const state = {};
    LAYER_GROUPS.forEach((g) => {
      state[g.id] = g.default;
    });
    return state;
  });

  const setVisibility = useCallback(
    (groupId, visible) => {
      if (!map) return;
      const group = LAYER_GROUPS.find((g) => g.id === groupId);
      if (!group) return;
      const value = visible ? "visible" : "none";
      group.layers.forEach((layerId) => {
        try {
          map.setLayoutProperty(layerId, "visibility", value);
        } catch {}
      });
      if (groupId === "long_trails") setBadgeZoom(map, visible);
      setToggles((prev) => ({ ...prev, [groupId]: visible }));
    },
    [map]
  );

  const applyInitial = useCallback(
    (m) => {
      if (!m) return;
      LAYER_GROUPS.forEach((g) => {
        const value = g.default ? "visible" : "none";
        g.layers.forEach((layerId) => {
          try {
            m.setLayoutProperty(layerId, "visibility", value);
          } catch {}
        });
        if (g.id === "long_trails") setBadgeZoom(m, g.default);
      });
    },
    []
  );

  return { groups: LAYER_GROUPS, toggles, setVisibility, applyInitial };
}
