// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

/**
 * What the user can turn on and off, and which style layers each switch owns.
 *
 * A port of the browser's `LAYER_GROUPS` (`hooks/useLayerToggles.js`), layer id
 * for layer id. Copied rather than derived, and rather than grouped by a naming
 * convention: the ids do cluster by prefix, but not along the lines a person
 * thinks in. "Roads" owns `osm_tracks_*` because a 4x4 track is something you
 * drive, while `osm_trails_*` is not; "Trails" spans both the basemap's
 * `trails_*` and the region overlay's `osm_trails_*` because the user does not
 * know or care which archive a path came from. A prefix rule would get both
 * wrong, and would drift silently as layers are added.
 *
 * Not shared through the served style either, though it could be. The style is
 * cartography — how a layer is *drawn* — and this is a product decision about
 * what is worth offering as a switch; the web app keeps them apart for the same
 * reason. The cost is that a new layer needs adding in two places, which is what
 * [com.tracks.app.ui.map.LayerGroup] existing as a list rather than a rule makes
 * cheap to audit.
 *
 * Layers named here that the style does not carry are ignored rather than an
 * error, and that is load-bearing: the served style is trimmed to the tile
 * archives that exist (`services/tile_coverage.py`), so before a region is
 * downloaded the contour and overlay layers genuinely are not there.
 */
data class LayerGroup(
    val id: String,
    val label: String,
    val onByDefault: Boolean,
    val layers: List<String>,
    /**
     * Whether this is worth a switch.
     *
     * Most of them are not, and the sheet used to offer all eighteen. Nobody
     * opens a map to turn the water off — roads, rivers, contours, labels and
     * landcover are what a map *is*, and a switch for each buried the handful
     * that people genuinely flip under a wall of ones they never would. The
     * base layers are still groups, still named here, and still drawn; they
     * simply do not get asked about.
     *
     * What survives as a toggle has something in common: it is an overlay
     * answering a question the map does not always need to answer. Is there a
     * fire near me. Whose land is this. Where do my own tracks run.
     */
    val toggleable: Boolean = false,
)

/** The ones the sheet offers, in the order it offers them. */
val TOGGLEABLE_GROUPS: List<LayerGroup> get() = LAYER_GROUPS.filter { it.toggleable }

val LAYER_GROUPS: List<LayerGroup> = listOf(
    LayerGroup(
        id = "hillshade",
        label = "Hillshade",
        onByDefault = true,
        layers = listOf(
            "hillshade", "hillshade_overview",
        ),
    ),
    LayerGroup(
        id = "water",
        label = "Water",
        onByDefault = true,
        layers = listOf(
            "water_polygons", "water_rivers", "water_osm_rivers",
            "water_osm_canals", "water_osm_streams", "water_osm_ditches",
            "water_osm_intermittent", "water_osm_labels",
        ),
    ),
    LayerGroup(
        id = "roads",
        label = "Roads",
        onByDefault = true,
        layers = listOf(
            "roads_tunnels_casing", "roads_tunnels_fill", "roads_light_casing",
            "roads_light_fill", "roads_osm_light_casing", "roads_osm_light_fill",
            "roads_secondary_casing", "roads_secondary_fill", "roads_primary_casing",
            "roads_primary_fill", "roads_primary_median", "roads_rail",
            "roads_bridges_casing", "roads_bridges_fill", "osm_tracks_track_casing",
            "osm_tracks_track", "osm_tracks_service_casing", "osm_tracks_service",
        ),
    ),
    LayerGroup(
        id = "trails",
        label = "Trails",
        onByDefault = true,
        layers = listOf(
            "trails_casing", "trails_path", "trails_footway",
            "trails_bridleway", "trails_cycleway", "trails_steps",
            "trails_other", "trails_labels", "osm_trails_casing",
            "osm_trails_bridge", "osm_trails_unpaved", "osm_trails_paved",
            "osm_trails_faint", "osm_trails_difficult", "osm_trails_steps",
            "osm_trails_oneway", "osm_trails_labels",
        ),
    ),
    LayerGroup(
        id = "long_trails",
        label = "Long Trails",
        onByDefault = false,
        toggleable = true,
        layers = listOf(
            "osm_trails_route_highlight", "osm_trails_route_line", "osm_trails_route_labels",
            "osm_trails_section_markers", "osm_trails_route_hover", "osm_trails_route_hit",
        ),
    ),
    LayerGroup(
        id = "custom_tracks",
        label = "My Tracks",
        onByDefault = true,
        toggleable = true,
        layers = listOf(
            "custom_tracks_hit", "custom_tracks_hover", "custom_tracks_casing",
            "custom_tracks_line", "custom_tracks_line_external", "custom_tracks_arrows",
        ),
    ),
    LayerGroup(
        id = "activity_tracks",
        label = "Activity Tracks",
        onByDefault = true,
        toggleable = true,
        layers = listOf(
            "activity_tracks_hit", "activity_tracks_hover", "activity_tracks_line",
            "activity_tracks_arrows",
        ),
    ),
    LayerGroup(
        id = "landcover",
        label = "Landcover",
        onByDefault = true,
        layers = listOf(
            "landcover",
        ),
    ),
    LayerGroup(
        id = "publiclands",
        label = "Public Lands",
        onByDefault = true,
        toggleable = true,
        layers = listOf(
            "publiclands_fill", "publiclands_outline", "publiclands_labels",
            "publiclands_boundary_labels",
        ),
    ),
    LayerGroup(
        id = "buildings",
        label = "Buildings",
        onByDefault = true,
        layers = listOf(
            "buildings",
        ),
    ),
    LayerGroup(
        id = "boundaries",
        label = "Boundaries",
        onByDefault = true,
        layers = listOf(
            "boundaries_national", "boundaries_state", "boundaries_county",
            "boundaries_city",
        ),
    ),
    LayerGroup(
        id = "labels",
        label = "Labels",
        onByDefault = true,
        layers = listOf(
            "labels_water_ocean", "labels_water_lakes", "labels_water_seas",
            "labels_place_country", "labels_place_region", "labels_place_locality",
            "labels_place_county", "labels_roads_major",
        ),
    ),
    LayerGroup(
        id = "poi_db_icons",
        label = "POI Icons",
        onByDefault = true,
        layers = listOf(
            "poi_db_icons", "poi_water_icons",
        ),
    ),
    LayerGroup(
        id = "contours",
        label = "Contours",
        onByDefault = true,
        toggleable = true,
        layers = listOf(
            "contours_index", "contours_intermediate", "contours_labels",
        ),
    ),
    LayerGroup(
        id = "vegetation",
        label = "Vegetation",
        onByDefault = true,
        layers = listOf(
            "veg_fill", "veg_wetland_tint", "veg_tidal_tint",
            "veg_feather_veg", "veg_feather_wet", "veg_marsh",
            "veg_swamp", "veg_mangrove", "veg_orchard",
            "veg_vineyard", "veg_sand", "veg_gravel",
            "veg_scrub", "veg_tidalflat", "veg_reef",
        ),
    ),
    LayerGroup(
        id = "infrastructure",
        label = "Infrastructure",
        onByDefault = true,
        layers = listOf(
            "infra_power_minor", "infra_power_line", "infra_power_pylons",
            "infra_pipeline", "infra_pipeline_label", "infra_dam",
            "infra_levee", "infra_levee_ticks", "infra_rail_minor",
            "infra_rail_casing", "infra_rail_line", "infra_rail_ties",
        ),
    ),
    LayerGroup(
        id = "wildfires",
        label = "Wildfires & Smoke",
        onByDefault = false,
        toggleable = true,
        layers = listOf(
            "wildfire_perimeter_fill", "wildfire_perimeter_line", "wildfire_points_glow",
            "wildfire_points_core", "wildfire_labels", "smoke_fill",
            "smoke_outline",
        ),
    ),
)

/**
 * Show or hide every layer a group owns.
 *
 * Each layer is set individually and failures are swallowed per layer, because
 * the group table is a superset of any one style: a group legitimately names
 * layers that only exist once a region has been downloaded, and one missing id
 * must not stop the rest of the group from toggling.
 */
internal fun applyGroupVisibility(
    style: org.maplibre.android.maps.Style,
    group: LayerGroup,
    visible: Boolean,
) {
    val value = if (visible) {
        org.maplibre.android.style.layers.Property.VISIBLE
    } else {
        org.maplibre.android.style.layers.Property.NONE
    }
    group.layers.forEach { id ->
        runCatching {
            style.getLayer(id)?.setProperties(
                org.maplibre.android.style.layers.PropertyFactory.visibility(value)
            )
        }.onFailure { android.util.Log.w("TracksMapLayers", "could not toggle $id", it) }
    }
}
