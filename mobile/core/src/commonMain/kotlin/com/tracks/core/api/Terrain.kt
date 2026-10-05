// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.api

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * Terrarium-encoded elevation, and the Web Mercator tile math to find a pixel
 * in one.
 *
 * Ported from `elevation_sampler.py`, the server's own DEM sampler — same
 * encoding, same bilinear interpolation, so a point sampled on the phone and
 * the same point sampled by the server agree. The DEM archives themselves are
 * pmtiles the phone never opens directly: it fetches one 512×512 Terrarium
 * tile at a time over the same URL MapLibre already uses for 3D terrain (see
 * [Endpoints.DEM_TILE]) and decodes it with the platform's own image
 * decoder — see `com.tracks.app.map.OfflineDem`, the Android half of this.
 *
 * Kept in `core` rather than beside that class because none of it touches a
 * platform image type: it is pure arithmetic over numbers a caller already
 * has, which is what makes it testable on the JVM with no `Bitmap` in reach.
 */
const val DEM_TILE_PX = 512

/** Elevation in metres from one Terrarium-encoded pixel's R, G, B bytes (each 0-255). */
fun terrariumElevation(r: Int, g: Int, b: Int): Double = r * 256.0 + g + b / 256.0 - 32768.0

/**
 * The fractional Web Mercator tile coordinate for a position at [zoom].
 *
 * `x` increases east and `y` increases south — the same axes a tile URL's
 * `{x}`/`{y}` already use, so `floor(x)`/`floor(y)` is the tile the point
 * falls in and the fractional remainder is the pixel within it.
 */
fun webMercatorTile(lng: Double, lat: Double, zoom: Int): Pair<Double, Double> {
    val n = 2.0.pow(zoom)
    val x = (lng + 180.0) / 360.0 * n
    val sinLat = sin(Math.toRadians(lat)).coerceIn(-0.9999, 0.9999)
    val y = (0.5 - ln((1 + sinLat) / (1 - sinLat)) / (4 * PI)) * n
    return x to y
}

/**
 * Bilinear elevation at a fractional pixel `(px, py)` within a [size]×[size]
 * grid, reading one elevation at a time through [at].
 *
 * A function rather than an array so the caller decides how a pixel is read
 * — off a decoded `Bitmap` on the phone, off a plain array in a test — and
 * only reads the four corners it actually needs rather than decoding a whole
 * tile up front. Null from [at] (this DEM's own "no data" sentinel, applied
 * by the caller — see [terrariumElevation]'s use in `OfflineDem`) propagates
 * rather than being treated as sea level: a hole in the data is not zero
 * metres, it is an answer this sampler does not have.
 */
fun bilinearElevation(at: (x: Int, y: Int) -> Double?, px: Double, py: Double, size: Int): Double? {
    val x0 = floor(px).toInt().coerceIn(0, size - 1)
    val y0 = floor(py).toInt().coerceIn(0, size - 1)
    val x1 = (x0 + 1).coerceAtMost(size - 1)
    val y1 = (y0 + 1).coerceAtMost(size - 1)
    val fx = px - x0
    val fy = py - y0

    val v00 = at(x0, y0) ?: return null
    val v10 = at(x1, y0) ?: return null
    val v01 = at(x0, y1) ?: return null
    val v11 = at(x1, y1) ?: return null

    return v00 * (1 - fx) * (1 - fy) + v10 * fx * (1 - fy) +
        v01 * (1 - fx) * fy + v11 * fx * fy
}
