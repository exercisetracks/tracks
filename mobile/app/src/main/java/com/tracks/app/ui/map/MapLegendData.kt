// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import androidx.compose.ui.graphics.Color

/**
 * The USGS quad legend, as data.
 *
 * A port of the browser's `MapLegend.jsx`, symbol for symbol. The phone used to
 * show a hand-picked dozen entries — "Trail or path", "Water", "Public land" —
 * which was a summary of a legend rather than one: the map draws eight
 * distinguishable trail types and this named one of them, so a purple line and
 * a brown line both read as "trail" and the difference between a hiking path
 * and a motorised one was unavailable to anyone who had not memorised it.
 *
 * The colours are the style's own, copied from `style/palette.js` the same way
 * the browser's legend imports them. They have to match the cartography exactly
 * or the legend is worse than nothing, so they live here as one table rather
 * than being scattered through the composables that draw them.
 */

// ── palette, mirroring frontend/src/pages/maps/style/palette.js ──────────────
internal object LegendPalette {
    val highwayFill = Color(0xFFE05C4B)
    val highwayCasing = Color(0xFFB03A2B)
    val secondaryFill = Color(0xFFE8A33D)
    val secondaryCasing = Color(0xFFB87A22)
    val lightFill = Color(0xFFF2E4C8)
    val lightCasing = Color(0xFF9A8A6A)
    val service = Color(0xFF9A8A6A)

    val trailInk = Color(0xFF43301D)
    val trailHalo = Color(0xFFF5EFC8)
    val track = Color(0xFFA98352)
    val horse = Color(0xFF8B5A2B)
    val bike = Color(0xFF2E7D5B)
    val moto = Color(0xFFB03A2B)
    val steps = Color(0xFF6B4A2A)
    val difficult = Color(0xFF8E44AD)
    val routeLine = Color(0xFF6D28D9)

    val national = Color(0xFF6B5B7B)
    val state = Color(0xFF7A6A8A)
    val county = Color(0xFF8A7A9A)
    val city = Color(0xFF9A8AAA)

    val contour = Color(0xFFB08B5E)
    val stream = Color(0xFF5E8CB0)

    val rail = Color(0xFF444444)
    val power = Color(0xFF6B6B6B)
    val powerMinor = Color(0xFF8A8A8A)
    val pipeline = Color(0xFF7A6A5A)
    val dam = Color(0xFF555555)
    val levee = Color(0xFF8A7A5A)

    val wood = Color(0xFFD3E3C4)
    val grass = Color(0xFFE4EBD2)
    val glacier = Color(0xFFE8F4F8)
    val scrub = Color(0xFFDDE7C8)
    val sand = Color(0xFFF2E9D0)
    val rock = Color(0xFFE0DCD4)
    val tidal = Color(0xFFDCE4E0)

    val wildfireCore = Color(0xFFE8452C)
    val wildfireGlow = Color(0xFFF97316)
    val wildfirePerimeter = Color(0xFFC2410C)
}

/** A point symbol: the sprite name the map uses, and what it means. */
internal data class LegendIcon(val sprite: String, val label: String)

internal val LEGEND_POINTS: List<Pair<String, List<LegendIcon>>> = listOf(
    "Recreation" to listOf(
        LegendIcon("campground", "Campground"), LegendIcon("picnic", "Picnic area"),
        LegendIcon("trailhead", "Trailhead"), LegendIcon("winter_rec", "Winter recreation"),
        LegendIcon("viewpoint", "Viewpoint"), LegendIcon("shelter", "Shelter"),
        LegendIcon("alpine_hut", "Hut / cabin"), LegendIcon("information", "Information"),
    ),
    "Water sources" to listOf(
        LegendIcon("spring", "Spring / seep"), LegendIcon("well", "Well"),
        LegendIcon("drinking_water", "Drinking water"),
        LegendIcon("gaging_station", "Gaging station"),
        LegendIcon("falls", "Falls"), LegendIcon("boat_ramp", "Boat ramp"),
    ),
    "Culture" to listOf(
        LegendIcon("ranger_station", "Ranger station"),
        LegendIcon("place_of_worship", "House of worship"),
        LegendIcon("school", "School"), LegendIcon("cemetery", "Cemetery"),
        LegendIcon("tower", "Tower"), LegendIcon("substation", "Substation"),
        LegendIcon("aerodrome", "Airport"),
    ),
    "Mines & relief" to listOf(
        LegendIcon("mine", "Mine"), LegendIcon("mine_shaft", "Mine shaft"),
        LegendIcon("quarry", "Quarry"), LegendIcon("cave", "Cave entrance"),
        LegendIcon("benchmark", "Benchmark (BM)"),
        LegendIcon("spot_elevation", "Spot elevation"),
        LegendIcon("monument", "Location monument"),
    ),
    "Parks & land" to listOf(
        LegendIcon("park", "Park / preserve"), LegendIcon("forest", "Forest"),
    ),
    "Services" to listOf(
        LegendIcon("toilets", "Restroom"), LegendIcon("parking", "Parking"),
        LegendIcon("fuel", "Fuel"), LegendIcon("hospital", "Hospital"),
    ),
    "Food & culture" to listOf(
        LegendIcon("restaurant", "Restaurant"), LegendIcon("cafe", "Café"),
        LegendIcon("fast_food", "Fast food"), LegendIcon("bar", "Bar / pub"),
        LegendIcon("supermarket", "Supermarket"),
        LegendIcon("convenience", "Convenience store"),
        LegendIcon("library", "Library"), LegendIcon("museum", "Museum"),
        LegendIcon("theatre", "Theater"),
    ),
)

/**
 * A line symbol.
 *
 * [casing] is the halo drawn under a line on the map — every trail has one, and
 * without it the legend swatch is a different shape from the thing on screen.
 */
internal data class LegendLineSpec(
    val label: String,
    val color: Color,
    val width: Float = 2f,
    val dash: FloatArray? = null,
    val casing: Color? = null,
    val casingWidth: Float = 0f,
    /** A second parallel line, for the double-track service-road convention. */
    val doubled: Boolean = false,
)

private val P = LegendPalette

internal val LEGEND_LINES: List<Pair<String, List<LegendLineSpec>>> = listOf(
    "Roads" to listOf(
        LegendLineSpec("Primary highway", P.highwayFill, 4f, casing = P.highwayCasing, casingWidth = 6f),
        LegendLineSpec("Secondary highway", P.secondaryFill, 3.5f, casing = P.secondaryCasing, casingWidth = 5f),
        LegendLineSpec("Light-duty road (paved)", P.lightFill, 3.2f, casing = P.lightCasing, casingWidth = 5f),
        LegendLineSpec("Service road (graded)", P.service, 1.3f, dash = floatArrayOf(4f, 3f), doubled = true),
        LegendLineSpec("Two-track / 4WD road", P.track, 2.6f, dash = floatArrayOf(5f, 3f)),
    ),
    // Hue is the highest permitted use, which is the Gaia convention and the
    // one thing about this map that is genuinely unguessable without a legend.
    "Trails" to listOf(
        LegendLineSpec("Hiking trail (foot only)", P.trailInk, 2f, floatArrayOf(3f, 2f), P.trailHalo, 5.5f),
        LegendLineSpec("Horse trail (equestrian)", P.horse, 2f, floatArrayOf(3f, 2f), P.trailHalo, 5.5f),
        LegendLineSpec("Bike-legal trail (MTB)", P.bike, 2f, floatArrayOf(3f, 2f), P.trailHalo, 5.5f),
        LegendLineSpec("Motorized trail (OHV)", P.moto, 2f, floatArrayOf(3f, 2f), P.trailHalo, 5.5f),
        LegendLineSpec("Steps / stairway", P.steps, 3f, floatArrayOf(1f, 2.5f)),
        LegendLineSpec("Difficult / technical", P.difficult, 2f, floatArrayOf(3f, 2f)),
        LegendLineSpec("Long-distance route", P.routeLine, 3f),
    ),
    "Boundaries" to listOf(
        LegendLineSpec("National", P.national, 2f, floatArrayOf(6f, 2f, 1f, 2f)),
        LegendLineSpec("State", P.state, 1.6f, floatArrayOf(5f, 2f, 1f, 2f)),
        LegendLineSpec("County", P.county, 1.4f, floatArrayOf(3f, 2f, 1f, 2f)),
        LegendLineSpec("City / township", P.city, 1.2f, floatArrayOf(1f, 2f)),
    ),
    "Relief" to listOf(
        LegendLineSpec("Contour, index", P.contour, 1.8f),
        LegendLineSpec("Contour, intermediate", P.contour, 0.9f),
    ),
    "Water" to listOf(
        LegendLineSpec("River", P.stream, 2.6f),
        LegendLineSpec("Perennial stream / canal", P.stream, 1.8f),
        LegendLineSpec("Intermittent stream", P.stream, 1.6f, floatArrayOf(4f, 1.5f, 0.5f, 1.5f)),
        LegendLineSpec("Ditch / drain", P.stream, 1.1f),
    ),
    "Railroads & utilities" to listOf(
        LegendLineSpec("Railroad", P.rail, 1.2f, doubled = true),
        LegendLineSpec("Power transmission line", P.power, 1.4f),
        LegendLineSpec("Power distribution line", P.powerMinor, 1.2f, floatArrayOf(0.5f, 2.5f)),
        LegendLineSpec("Pipeline", P.pipeline, 1.4f, floatArrayOf(8f, 3f)),
        LegendLineSpec("Dam / weir", P.dam, 3.6f),
        LegendLineSpec("Levee", P.levee, 2f, floatArrayOf(4f, 2f)),
    ),
    "Yours" to listOf(
        LegendLineSpec("Route you are planning", Color(0xFF2563EB), 5f),
        LegendLineSpec("Past activities", Color(0xFFF97316), 2.5f),
        LegendLineSpec("Downloaded area", Color(0xFF2563EB), 3f, floatArrayOf(4f, 4f)),
    ),
)

/** Area fills, which on the map are flat colour or a repeating sprite pattern. */
internal val LEGEND_AREAS: List<Pair<String, Color>> = listOf(
    "Woodland" to P.wood,
    "Grassland" to P.grass,
    "Glacier / snow" to P.glacier,
    "Scrub" to P.scrub,
    "Sand" to P.sand,
    "Gravel / rock" to P.rock,
    "Marsh" to Color(0xFFDCE9E2),
    "Swamp / bog" to Color(0xFFD6E5DD),
    "Mangrove" to Color(0xFFD2E3DA),
    "Orchard" to Color(0xFFE2EFD4),
    "Vineyard" to Color(0xFFE4EFD6),
    "Tidal / foreshore flat" to P.tidal,
)

/** Must match `style/layers/publiclands.js` FILL exactly. */
internal val LEGEND_PUBLIC_LANDS: List<Pair<String, Color>> = listOf(
    "National Park / Monument" to Color(0xFF7FBE86),
    "National Forest" to Color(0xFFA6D199),
    "Wilderness Area" to Color(0xFF74B187),
    "BLM public land" to Color(0xFFE3CB80),
    "State public land" to Color(0xFFC2D98C),
    "Nature reserve" to Color(0xFF93C7BC),
    "Military land" to Color(0xFFD7968E),
    "Tribal / reservation" to Color(0xFFE0B17E),
)

/** The plume wash at each NOAA density, over legend paper. */
internal val LEGEND_SMOKE: List<Pair<String, Color>> = listOf(
    "Smoke — light" to Color(0x1C6E645A),
    "Smoke — medium" to Color(0x336E645A),
    "Smoke — heavy" to Color(0x526E645A),
)
