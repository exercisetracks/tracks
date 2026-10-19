// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import android.graphics.PointF
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * The ground a finger is on in 3D, not the sea-level point behind it.
 *
 * MapLibre turns a screen point into a coordinate by meeting the camera's ray
 * with the flat, sea-level plane — the terrain branch the phone draws with
 * ([Terrain3D]) does not change that. With the camera tilted over raised
 * ground, the ray meets the hillside long before it reaches sea level, so the
 * coordinate MapLibre reports is beyond the one under the finger: on screen,
 * every tap landed some way *above* where it was made, further the higher the
 * ground and the steeper the tilt.
 *
 * The ray is walked back here instead. Everything on it is a fraction `t` of
 * the way from the camera's footprint `C` to the sea-level hit `S`, and its
 * height there is `(1 - t)` of the camera's altitude; the ground is where
 * that height meets the terrain, found in a few secant steps for any slope the
 * ray does not graze — which on a 60° maximum tilt is every slope worth
 * walking.
 *
 * Heights come from [OfflineDem][com.tracks.app.map.OfflineDem], the same
 * Terrarium tiles the terrain is drawn from (saved packs first). Where it has
 * none the sea-level point stands, as before — wrong by the same amount, but
 * never worse.
 */
internal object TerrainTap {

    /** MapLibre Native's camera field of view (`util::DEFAULT_FOV`). */
    private const val FOV = 0.6435011087932844

    /** Below this the camera looks almost straight down and the offset is negligible. */
    private const val MIN_TILT = 2.0

    private const val ROUNDS = 8

    /** Settled once a round moves the guess less than this, in metres. */
    private const val SETTLED_M = 0.5

    /**
     * The camera, as the walk needs it: its footprint on the ground and its
     * height above sea level, in metres.
     */
    data class Camera(val footprint: LatLng, val altitudeM: Double)

    /**
     * Where [map]'s camera is, from what the public API exposes: the
     * projection, the view height and the pitch. Null when it is looking
     * straight down or terrain is off, when there is nothing to correct.
     */
    fun camera(map: MapLibreMap): Camera? {
        if (map.style?.terrain == null) return null
        val position = map.cameraPosition
        val tilt = position.tilt
        val center = position.target ?: return null
        if (tilt < MIN_TILT) return null
        val projection = map.projection
        val mid = projection.toScreenLocation(center)
        // Metres per screen pixel at the centre, measured along the screen's
        // horizontal (which tilt does not foreshorten) rather than taken from
        // getMetersPerPixelAtLatitude, whose pixel need not be this one.
        val east = projection.fromScreenLocation(PointF(mid.x + PROBE_PX, mid.y))
        val metresPerPx = distanceM(center, east) / PROBE_PX
        if (metresPerPx <= 0.0 || map.height <= 0f) return null
        // MapLibre's cameraToCenterDistance: half the view height over tan(fov/2).
        val toCenterM = 0.5 * map.height / tan(FOV / 2) * metresPerPx
        val pitch = tilt * PI / 180
        return Camera(
            footprint = destination(center, position.bearing + 180, toCenterM * sin(pitch)),
            altitudeM = toCenterM * cos(pitch),
        )
    }

    /**
     * The ground under the ray from [camera] through the sea-level point
     * [seaLevel], given terrain heights from [elevation] (metres, null where
     * unknown).
     */
    suspend fun ground(seaLevel: LatLng, camera: Camera, elevation: suspend (LatLng) -> Double?): LatLng {
        // How far the ray at `t` is above the ground there: zero at the answer.
        // Solved by the secant method from the sea-level hit (t = 1) — the
        // plain "move to where the ray is that high" step halves its error per
        // round on a hillside and was still metres out after eight.
        suspend fun above(t: Double): Double? =
            elevation(between(camera.footprint, seaLevel, t))?.let { camera.altitudeM * (1 - t) - it }
        val span = distanceM(camera.footprint, seaLevel).takeIf { it > 0 } ?: return seaLevel
        var t0 = 1.0
        var f0 = above(t0) ?: return seaLevel
        // The first step is the plain one: where the ray is as high as the
        // ground under the sea-level hit.
        var t1 = (1 + f0 / camera.altitudeM).coerceIn(MIN_T, MAX_T)
        repeat(ROUNDS) {
            val f1 = above(t1) ?: return between(camera.footprint, seaLevel, t0)
            if (abs(f1 - f0) < 1e-9) return between(camera.footprint, seaLevel, t1)
            // Clamped short of the camera: ground above it cannot be under the finger.
            val t2 = (t1 - f1 * (t1 - t0) / (f1 - f0)).coerceIn(MIN_T, MAX_T)
            t0 = t1; f0 = f1; t1 = t2
            if (abs(t1 - t0) * span < SETTLED_M) return between(camera.footprint, seaLevel, t1)
        }
        return between(camera.footprint, seaLevel, t1)
    }

    private const val MIN_T = 0.05
    private const val MAX_T = 1.5

    private const val PROBE_PX = 100f
    private const val EARTH_M = 6_371_008.8

    private fun between(from: LatLng, to: LatLng, t: Double) = LatLng(
        from.latitude + (to.latitude - from.latitude) * t,
        from.longitude + (to.longitude - from.longitude) * t,
    )

    /** Equirectangular: exact enough over the few kilometres a view spans. */
    internal fun distanceM(a: LatLng, b: LatLng): Double {
        val lat = (a.latitude + b.latitude) / 2 * PI / 180
        val dx = (b.longitude - a.longitude) * PI / 180 * cos(lat) * EARTH_M
        val dy = (b.latitude - a.latitude) * PI / 180 * EARTH_M
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    internal fun destination(from: LatLng, bearingDeg: Double, metres: Double): LatLng {
        val b = bearingDeg * PI / 180
        val dLat = metres * cos(b) / EARTH_M * 180 / PI
        val cosLat = cos(from.latitude * PI / 180).let { if (abs(it) < 1e-9) 1e-9 else it }
        val dLng = metres * sin(b) / (EARTH_M * cosLat) * 180 / PI
        return LatLng(from.latitude + dLat, from.longitude + dLng)
    }
}
