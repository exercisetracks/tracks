// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.api

/**
 * Reading the coordinate pairs `/activities/heatmap` sends.
 *
 * ## Why this is in core rather than next to the map
 *
 * It is a wire-format concern, and it was got wrong precisely because it was
 * treated as a rendering detail. `/activities/heatmap` sends **`[lat, lng]`** —
 * the server builds `[round(r.lat, 4), round(r.lng, 4)]` — while GeoJSON, and
 * therefore MapLibre, wants `[lng, lat]`. The Android map layer assumed GeoJSON
 * order and carried a comment stating so.
 *
 * The result was not a slightly wrong map. Coordinates were transposed on every
 * point, and framing the camera passed a longitude to `LatLng` as a latitude,
 * which throws for any |longitude| > 90 — most of the Americas and Asia. The
 * heatmap took the process down rather than drawing, which is why it looked
 * like a feature that had never been built.
 *
 * Living here means the format is stated once, beside the other wire models,
 * and has tests. A misread wire format is exactly the class of bug the client
 * has now been bitten by twice — see `PushItem.dataB64` and its `fit_b64`.
 */

const val MAX_LATITUDE: Double = 90.0
const val MAX_LONGITUDE: Double = 180.0

/** A usable position, in the order humans and MapLibre's `LatLng` both use. */
data class LatLngPoint(val lat: Double, val lng: Double)

/**
 * One `[lat, lng]` pair from the heatmap endpoint, or null if unusable.
 *
 * Range-checked rather than trusted. These values are decrypted server-side and
 * round-tripped through JSON, and one bad row should cost a single point on a
 * heatmap rather than the whole screen — which is not hypothetical, given that
 * an out-of-range latitude is what crashed the app.
 *
 * Extra elements are ignored: `mode=intensity` appends a third value, and a
 * caller that only wants a position should not have to care.
 */
fun heatmapPoint(pair: List<Double>): LatLngPoint? {
    if (pair.size < 2) return null
    val lat = pair[0]
    val lng = pair[1]
    if (lat.isNaN() || lng.isNaN()) return null
    if (lat < -MAX_LATITUDE || lat > MAX_LATITUDE) return null
    if (lng < -MAX_LONGITUDE || lng > MAX_LONGITUDE) return null
    return LatLngPoint(lat, lng)
}

/** Every usable position in a heatmap response, flattened. */
fun heatmapPoints(pairs: List<List<Double>>): List<LatLngPoint> = pairs.mapNotNull(::heatmapPoint)
