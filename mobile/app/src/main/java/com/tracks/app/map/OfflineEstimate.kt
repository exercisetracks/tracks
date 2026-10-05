// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.map

import org.maplibre.android.geometry.LatLngBounds
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.tan
import kotlin.math.PI

/**
 * What saving an area onto *this phone* would actually cost.
 *
 * ## Why the server's estimate is not this number
 *
 * `GET /maps/regions/estimate` answers a different question: how many bytes the
 * server must cut out of the planet archives. That is the right number for the
 * server's disk and the wrong one for the phone, because the phone does not
 * receive an archive — it fetches every tile the style references, one HTTP
 * request at a time, across every source and every zoom in the band.
 *
 * The gap between the two is enormous and it is what "the download failed"
 * usually meant. A two-degree box is a few hundred megabytes on the server and
 * upwards of three hundred thousand requests on the phone; a six-degree box is
 * over a million. Neither fails in any way a program can detect — MapLibre
 * keeps going, honestly, for hours — so the progress bar creeps, the user
 * concludes it is broken, deletes it and tries again with a bigger box.
 *
 * The fix is arithmetic, done before anything starts. A region small enough to
 * finish says nothing; a large one says what it will take; an absurd one is
 * refused for the phone and left to the server, which now serves that ground at
 * full detail over the network anyway (see [com.tracks.app.ui.map.Coverage]).
 */
object OfflineEstimate {

    /**
     * Roughly how many resources the phone would fetch for [bounds].
     *
     * Counted from the tile pyramid rather than from the style, deliberately.
     * Walking the real style would be exact and would need the style document,
     * a network fetch and a parser at the one moment this has to answer
     * instantly — while a corner is being dragged. The pyramid is the dominant
     * term by three orders of magnitude anyway: glyphs and sprites are a few
     * dozen resources whatever the area.
     */
    fun resourcesFor(bounds: LatLngBounds): Long {
        // The three sources that span the whole band — the basemap, the OSM
        // overlay and the terrain DEM — plus contours, which live in a much
        // shallower band and are a rounding error next to them.
        val band = pyramid(bounds, MIN_ZOOM, MAX_ZOOM)
        return band * FULL_BAND_SOURCES + pyramid(bounds, CONTOUR_MIN_ZOOM, CONTOUR_MAX_ZOOM)
    }

    /** How the phone should feel about a download of this size. */
    fun weigh(bounds: LatLngBounds): Weight {
        val resources = resourcesFor(bounds)
        return Weight(
            resources = resources,
            level = when {
                resources > CEILING -> Level.BeyondPhone
                resources > HEAVY -> Level.Long
                else -> Level.Fine
            },
        )
    }

    data class Weight(val resources: Long, val level: Level)

    enum class Level {
        /** Minutes. Nothing worth saying about it. */
        Fine,

        /** Worth warning about: tens of minutes to hours, and gigabytes. */
        Long,

        /**
         * Not a thing a phone finishes. The server half still runs — that area
         * is then served at full detail whenever there is signal — but nothing
         * is written to the phone's own store.
         */
        BeyondPhone,
    }

    /**
     * Tiles covering [bounds] from [minZoom] to [maxZoom] inclusive.
     *
     * Web Mercator, the same projection the tile scheme is defined in, so the
     * count is right rather than approximately right at the latitudes people
     * actually live at. A degree of longitude is a degree of longitude
     * everywhere; a degree of latitude is not, and treating it as one
     * undercounts by nearly half at 60°N.
     */
    fun pyramid(bounds: LatLngBounds, minZoom: Int, maxZoom: Int): Long {
        if (maxZoom < minZoom) return 0
        var total = 0L
        for (zoom in minZoom..maxZoom) {
            val n = 1L shl zoom
            val west = xTile(bounds.longitudeWest, n)
            val east = xTile(bounds.longitudeEast, n)
            val north = yTile(bounds.latitudeNorth, n)
            val south = yTile(bounds.latitudeSouth, n)
            val across = abs(east - west) + 1
            val down = abs(south - north) + 1
            total += across * down
        }
        return total
    }

    private fun xTile(longitude: Double, n: Long): Long =
        floor((longitude + 180.0) / 360.0 * n).toLong().coerceIn(0, n - 1)

    /**
     * `asinh(tan(lat))` rather than the `log(tan + sec)` spelling of the same
     * identity: one call, no cancellation near the poles, and the clamp is
     * MERCATOR_LIMIT because the projection has no answer past it.
     */
    private fun yTile(latitude: Double, n: Long): Long {
        val clamped = latitude.coerceIn(-MERCATOR_LIMIT, MERCATOR_LIMIT)
        val radians = clamped * PI / 180.0
        val fraction = (1.0 - asinh(tan(radians)) / PI) / 2.0
        return floor(fraction * n).toLong().coerceIn(0, n - 1)
    }

    /** Matches [OfflineTiles.MIN_ZOOM]/[OfflineTiles.MAX_ZOOM], as whole levels. */
    private const val MIN_ZOOM = 8
    private const val MAX_ZOOM = 15

    /** The contour source's own band, from the style. */
    private const val CONTOUR_MIN_ZOOM = 9
    private const val CONTOUR_MAX_ZOOM = 12

    /** Basemap, overlay, DEM. */
    private const val FULL_BAND_SOURCES = 3

    /**
     * Where a download stops being an errand and becomes an afternoon.
     *
     * About a degree and a quarter square — a large national park,
     * or a day's drive of trailheads. Below it a download is minutes on wifi
     * and nobody needs to be told anything.
     */
    const val HEAVY = 60_000L

    /**
     * Where it stops being worth starting.
     *
     * A whole state is around 1.2 million resources. Even at a hundred requests
     * a second — optimistic over anything but a LAN — that is three hours of
     * the screen awake and several gigabytes, and MapLibre will attempt every
     * one of them without complaint. Refusing is kinder than a progress bar
     * that moves for an afternoon.
     */
    const val CEILING = 400_000L

    /** Web Mercator's own latitude limit. */
    private const val MERCATOR_LIMIT = 85.05112878
}
