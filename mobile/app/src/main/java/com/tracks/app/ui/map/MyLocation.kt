// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.engine.LocationEngineRequest
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

/**
 * How closely the map is following you.
 *
 * The three states a phone map has, in the order tapping the button cycles
 * through them — the same progression Google Maps uses, because it is the one
 * people already have in their fingers.
 */
enum class FollowMode {
    /** The dot is drawn if we have a fix; the camera is yours. */
    Off,

    /** Camera keeps you centred, north stays up. */
    Follow,

    /**
     * Camera keeps you centred and rotates so the way you are facing is up.
     *
     * The mode that matters on foot: matching what is in front of you to what
     * is on the screen is most of what a map is for at a trail junction.
     */
    FollowWithHeading;

    fun next(): FollowMode = when (this) {
        Off -> Follow
        Follow -> FollowWithHeading
        FollowWithHeading -> Off
    }

    /**
     * How hard to ask the GNSS chip for a fix in this mode.
     *
     * MapLibre's default is one high-accuracy fix per second for as long as the
     * dot is on screen, and the dot is on screen the whole time the map is —
     * following or not. On a phone that has to last a trip rather than a day,
     * that is the single most expensive thing this screen does, and most of it
     * buys nothing: the camera is not moving with the fixes, so the only job
     * left is a dot that has to look like it is in the right place.
     *
     * So the ask follows what the fix is actually for.
     */
    val locationRequest: LocationEngineRequest
        get() = when (this) {
            // Nobody is watching the dot move. A fix every fifteen seconds, and
            // only if you have gone somewhere: `setDisplacement` means a phone
            // sitting on a rock asks for nothing at all, and `setMaxWaitTime`
            // lets the OS hold fixes and deliver them in a batch, so the
            // application processor wakes once instead of several times.
            Off -> LocationEngineRequest.Builder(IDLE_INTERVAL_MS)
                .setPriority(LocationEngineRequest.PRIORITY_BALANCED_POWER_ACCURACY)
                .setFastestInterval(IDLE_FASTEST_MS)
                .setMaxWaitTime(IDLE_BATCH_MS)
                .setDisplacement(IDLE_DISPLACEMENT_M)
                .build()

            // The camera is tracking you now, so the fixes are the animation.
            // Still not one a second: MapLibre interpolates between them, and at
            // walking pace two seconds is under three metres of ground.
            Follow, FollowWithHeading -> LocationEngineRequest.Builder(FOLLOW_INTERVAL_MS)
                .setPriority(LocationEngineRequest.PRIORITY_HIGH_ACCURACY)
                .setFastestInterval(FOLLOW_FASTEST_MS)
                .build()
        }

    private companion object {
        const val IDLE_INTERVAL_MS = 15_000L
        const val IDLE_FASTEST_MS = 5_000L
        const val IDLE_BATCH_MS = 30_000L
        const val IDLE_DISPLACEMENT_M = 10f

        const val FOLLOW_INTERVAL_MS = 2_000L
        const val FOLLOW_FASTEST_MS = 1_000L
    }

    /** MapLibre's camera mode for this state. */
    val cameraMode: Int
        get() = when (this) {
            Off -> CameraMode.NONE
            Follow -> CameraMode.TRACKING
            FollowWithHeading -> CameraMode.TRACKING_COMPASS
        }

    /**
     * How the dot itself is drawn.
     *
     * [RenderMode.COMPASS] points the dot's arrow at magnetic north's opposite
     * — the direction the *device* is facing — which is what you want when the
     * map is not itself rotating. Once the map rotates with you
     * ([FollowWithHeading]) the arrow would be pinned to the top of the screen
     * and say nothing, so the plain dot is used instead.
     */
    val renderMode: Int
        get() = when (this) {
            Off -> RenderMode.NORMAL
            Follow -> RenderMode.COMPASS
            FollowWithHeading -> RenderMode.NORMAL
        }
}

/**
 * The blue dot, and the camera modes that follow it.
 *
 * MapLibre's own `LocationComponent` rather than a hand-rolled marker: it owns
 * the location engine, the heading sensor, the accuracy ring and the camera
 * transitions, and all four are fiddly to get right — a marker updated from a
 * `LocationListener` looks fine standing still and stutters visibly while
 * walking, because the component interpolates between fixes and a naive marker
 * does not.
 *
 * The component must be re-activated on every style load. A style is a whole
 * new set of sources and layers, and the dot lives in those — so a region
 * download, which changes the served style, would silently take the dot with it.
 */
object MyLocation {

    private const val TAG = "TracksMapLocation"

    /**
     * Where following opens, when the map was further out.
     *
     * Close enough to see the trail you are standing on. Only applied when
     * zooming *in*: someone who has deliberately zoomed to a valley and then
     * hits follow should not be yanked back out or in.
     */
    /**
     * Close enough to see the street, far enough to see where it goes.
     *
     * Was 15, which put the map about two blocks across — you could see the
     * dot and nothing that told you where the dot *was*. The point of pressing
     * this button is usually orientation rather than detail.
     */
    private const val FOLLOW_ZOOM = 14.0

    /**
     * The style the component was last built against, compared by identity.
     *
     * Not a boolean: "activated" and "activated against the document currently
     * on screen" are different questions, and only the second one is useful
     * after a style reload.
     */
    private var activatedFor: Style? = null

    /**
     * The mode whose [FollowMode.locationRequest] the engine is currently running.
     *
     * Tracked so the request is handed over only when it actually changes —
     * setting it re-subscribes to the location engine, and doing that on every
     * recomposition would restart the very thing it is there to keep cheap.
     */
    private var requestedFor: FollowMode? = null

    fun permitted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    val permissions: Array<String> = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    /**
     * Turn the dot on and set how the camera follows it.
     *
     * Safe to call repeatedly and safe to call without permission — it does
     * nothing rather than throwing, so callers do not have to guard every site.
     */
    @SuppressLint("MissingPermission")
    fun apply(context: Context, map: MapLibreMap, style: Style, mode: FollowMode) {
        if (!permitted(context)) return
        if (!style.isFullyLoaded) return

        runCatching {
            val component = map.locationComponent
            // Re-activated when the *style* changes, not only when the
            // component has never been set up. The dot, its accuracy ring and
            // its heading arrow are layers inside the style, so a document
            // swap — which a region download causes — takes them with it, and
            // `isLocationComponentActivated` stays true over the top of a map
            // that is no longer showing anything.
            if (!component.isLocationComponentActivated || activatedFor !== style) {
                component.activateLocationComponent(
                    LocationComponentActivationOptions.builder(context, style)
                        .locationComponentOptions(
                            LocationComponentOptions.builder(context)
                                // Fade the dot out when the map is zoomed far
                                // out: a 10 m accuracy ring at continental zoom
                                // is a dot over an entire state, which is worse
                                // than showing nothing.
                                .enableStaleState(true)
                                .build()
                        )
                        .useDefaultLocationEngine(true)
                        .locationEngineRequest(mode.locationRequest)
                        .build()
                )
                // A fresh component is running whatever was just handed to it,
                // whatever the old one had been asking for.
                requestedFor = mode
            }
            activatedFor = style
            component.isLocationComponentEnabled = true
            component.cameraMode = mode.cameraMode
            component.renderMode = mode.renderMode

            // Tightened when following starts and relaxed the moment it stops —
            // see [FollowMode.locationRequest]. Leaving it at the following rate
            // after the user has let go is where the battery quietly goes.
            if (requestedFor != mode) {
                component.locationEngineRequest = mode.locationRequest
                requestedFor = mode
            }

            // Move to the dot ourselves when following starts.
            //
            // A camera mode only takes effect on the *next* fix, so switching
            // it on over a world view leaves the map where it was until the
            // device happens to report again — which on a stationary phone
            // indoors can be a long time, and reads as the button doing
            // nothing. The last known fix is already in hand, so use it.
            if (mode != FollowMode.Off && map.cameraPosition.zoom < FOLLOW_ZOOM) {
                component.lastKnownLocation?.let { fix ->
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            LatLng(fix.latitude, fix.longitude),
                            FOLLOW_ZOOM,
                        )
                    )
                }
            }
        }.onFailure { Log.w(TAG, "could not enable the location dot", it) }
    }
}
