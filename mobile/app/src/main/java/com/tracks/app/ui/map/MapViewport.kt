// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import android.content.Context
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng

/**
 * Where the map was last looking, remembered across leaving the tab and
 * coming back.
 *
 * Plain `SharedPreferences` rather than anything tied to the process: the
 * point is surviving a normal close or a switch to another tab, not
 * surviving a swipe-away — and a preference is gone the moment the app's
 * data is, same as every other small setting in this app, which is exactly
 * the amount of persistence this needs and no more.
 *
 * Written on every camera idle rather than once on the way out. The
 * composable that would otherwise save on dispose is not reliably disposed
 * by a home-button close — Compose keeps the composition alive through
 * `onStop` — so the only save point that is never missed is the one that
 * fires the moment a pan or zoom actually settles.
 */
object MapViewport {
    private const val PREFS = "tracks_map_viewport"
    private const val KEY_LAT = "lat"
    private const val KEY_LNG = "lng"
    private const val KEY_ZOOM = "zoom"
    private const val KEY_BEARING = "bearing"
    private const val KEY_TILT = "tilt"

    fun save(context: Context, position: CameraPosition) {
        val target = position.target ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAT, target.latitude.toString())
            .putString(KEY_LNG, target.longitude.toString())
            .putString(KEY_ZOOM, position.zoom.toString())
            .putString(KEY_BEARING, position.bearing.toString())
            .putString(KEY_TILT, position.tilt.toString())
            .apply()
    }

    /** Null the first time the map is ever opened — there is nothing to restore yet. */
    fun restore(context: Context): CameraPosition? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lat = prefs.getString(KEY_LAT, null)?.toDoubleOrNull() ?: return null
        val lng = prefs.getString(KEY_LNG, null)?.toDoubleOrNull() ?: return null
        val zoom = prefs.getString(KEY_ZOOM, null)?.toDoubleOrNull() ?: return null
        val bearing = prefs.getString(KEY_BEARING, null)?.toDoubleOrNull() ?: 0.0
        val tilt = prefs.getString(KEY_TILT, null)?.toDoubleOrNull() ?: 0.0
        return CameraPosition.Builder()
            .target(LatLng(lat, lng))
            .zoom(zoom)
            .bearing(bearing)
            .tilt(tilt)
            .build()
    }
}
