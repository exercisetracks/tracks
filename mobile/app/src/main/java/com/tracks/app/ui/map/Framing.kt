// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import com.tracks.core.api.MAX_LATITUDE
import com.tracks.core.api.MAX_LONGITUDE

/**
 * Point the camera at some points, without overshooting the data.
 *
 * Every map in the app opens by fitting bounds, and the naive fit has two
 * failure modes that both look like a broken map rather than a bad camera.
 *
 * **Zero extent.** [LatLngBounds] refuses a box with no width or height, and a
 * track can legitimately be one place: a treadmill run with a single GPS fix
 * repeated, or a bouldering session in one spot. So the box is nudged open,
 * then clamped — padding a track that genuinely reaches a pole or the
 * antimeridian would push the bound out of range and throw for the opposite
 * reason.
 *
 * **Overshoot.** A fit is only as good as the data underneath it. A 40 m track
 * fits at around zoom 20, and the deepest tiles anyone has are z15 in a
 * downloaded region — so the camera opens five levels past the last real detail,
 * on a stretched fragment of one tile with no road, label or coastline in frame.
 * The track is technically centred and the screen says nothing. [maxZoom] caps
 * that: the fit still frames the whole track, it just stops closing in once the
 * surroundings have stopped arriving.
 *
 * Note this is a cap on the *opening* camera only. Pinching in further is
 * expected and works — MapLibre overzooms the deepest tile it holds, which
 * re-renders vector geometry crisply at any magnification, so long as the style
 * is honest about where its data stops (see `services/tile_coverage.py`).
 */
internal fun frameCamera(
    map: MapLibreMap,
    positions: List<LatLng>,
    paddingPx: Int,
    maxZoom: Double = CLOSEST_USEFUL_ZOOM,
) {
    if (positions.isEmpty()) return

    val north = positions.maxOf { it.latitude }
    val south = positions.minOf { it.latitude }
    val east = positions.maxOf { it.longitude }
    val west = positions.minOf { it.longitude }

    val bounds = LatLngBounds.Builder()
        .include(
            LatLng(
                (north + NUDGE).coerceAtMost(MAX_LATITUDE),
                (east + NUDGE).coerceAtMost(MAX_LONGITUDE),
            )
        )
        .include(
            LatLng(
                (south - NUDGE).coerceAtLeast(-MAX_LATITUDE),
                (west - NUDGE).coerceAtLeast(-MAX_LONGITUDE),
            )
        )
        .build()

    // The bounds update first, and unconditionally.
    //
    // `getCameraForLatLngBounds` would give the zoom to inspect before moving,
    // which reads better and is a trap: it solves against the viewport *now*,
    // and returns null when the map has not been measured yet. Asking it first
    // and giving up on null is how the dashboard's card ended up framing
    // nothing — its map is laid out later than the others, so the camera stayed
    // at its default 0,0 and the whole card rendered as the open Atlantic. The
    // bounds update is deferred until there is a viewport to apply it to, which
    // is exactly the property that matters here.
    map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, paddingPx))

    // Then capped, once the camera reflects the fit. A no-op when the move was
    // deferred — the zoom is still the default, which is nowhere near the cap.
    if (map.cameraPosition.zoom > maxZoom) {
        map.moveCamera(CameraUpdateFactory.zoomTo(maxZoom))
    }
}

/**
 * As close as opening a map is ever worth.
 *
 * Borrowed from the web app, which caps its camera at 16 outright
 * (`useMapInit.js`). Framing a phone map deeper than the browser can display at
 * all is a good sign the fit has left the data behind — and at 16 a short track
 * still arrives with a few streets around it, which is what makes it
 * recognisable as a place rather than a squiggle.
 */
private const val CLOSEST_USEFUL_ZOOM = 16.0

/** Enough to open a degenerate box, small enough not to move a real one. */
private const val NUDGE = 0.005
