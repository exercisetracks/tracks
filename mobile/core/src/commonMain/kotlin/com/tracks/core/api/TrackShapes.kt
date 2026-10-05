// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max

/**
 * The shape of one activity's GPS track, normalised into a unit square.
 *
 * What a route thumbnail needs and nothing more: no coordinates, no scale, no
 * projection anyone could navigate by. [points] run 0..1 on both axes with the
 * aspect ratio preserved, so a long out-and-back stays a line instead of being
 * stretched to fill a square; [insetX] and [insetY] centre the shorter axis.
 */
data class TrackShape(
    val points: List<Pair<Float, Float>>,
    val insetX: Float,
    val insetY: Float,
)

/**
 * Route outlines for a list of activities, from `/activities/tracks-geojson`.
 *
 * Lives here rather than in the screen that draws them because it is data work,
 * not drawing: parsing a FeatureCollection and reducing a few hundred polylines
 * to something that fits in a 76dp box is the sort of thing worth having a test
 * for, and `:core` is where the tests are.
 *
 * A malformed feature is skipped rather than thrown on. This fills thumbnails —
 * one that cannot be drawn should cost a picture, not the list.
 */
object TrackShapes {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Points kept per thumbnail.
     *
     * A 76dp square holds nowhere near a thousand distinguishable points, so
     * everything past this is drawn on top of itself. Sampled evenly rather
     * than simplified properly — Douglas-Peucker would keep the corners better,
     * and at this size nobody can tell, so it would be cost with no picture to
     * show for it.
     */
    const val MAX_POINTS = 96

    /** Shapes, for drawing an outline. */
    fun parse(raw: String): Map<Int, TrackShape> =
        parseTracks(raw).mapNotNull { (id, points) -> normalise(points)?.let { id to it } }.toMap()

    /**
     * The coordinates themselves, by activity id.
     *
     * Kept separate from [parse] because the two are wanted for different jobs:
     * a normalised shape draws an outline, and real longitude and latitude are
     * what a map renderer needs to know where to point.
     */
    fun parseTracks(raw: String): Map<Int, List<Pair<Double, Double>>> {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return emptyMap()
        val features = runCatching { root["features"]?.jsonArray }.getOrNull() ?: return emptyMap()

        val out = mutableMapOf<Int, List<Pair<Double, Double>>>()
        for (feature in features) {
            runCatching {
                val obj = feature as? JsonObject ?: return@runCatching
                // The id is on the properties and repeated on the feature; read
                // the property and fall back, because a FeatureCollection is
                // allowed to carry either.
                val id = obj["properties"]?.jsonObject?.get("id")?.jsonPrimitive?.intOrNull
                    ?: obj["id"]?.jsonPrimitive?.intOrNull
                    ?: return@runCatching
                val coords = obj["geometry"]?.jsonObject?.get("coordinates")?.jsonArray
                    ?: return@runCatching
                val points = coords.mapNotNull { pair ->
                    val xy = pair.jsonArray
                    val lon = xy.getOrNull(0)?.jsonPrimitive?.doubleOrNull
                    val lat = xy.getOrNull(1)?.jsonPrimitive?.doubleOrNull
                    if (lon == null || lat == null) null else lon to lat
                }
                if (points.size >= 2) out[id] = points
            }
        }
        return out
    }

    /**
     * Longitude/latitude pairs to a unit square, aspect preserved.
     *
     * Longitude is scaled by cos(latitude), because a degree of it is not a
     * degree of latitude anywhere but the equator — at 40°N it is about 77 km
     * against latitude's 111, and ignoring that turns a square block of streets
     * into a wide rectangle. This is the same flat-earth approximation every
     * thumbnail uses and it is correct enough over the span of one activity.
     *
     * Null for anything with no shape: fewer than two points, or a track that
     * never moved — a treadmill session with one stray fix.
     */
    fun normalise(points: List<Pair<Double, Double>>): TrackShape? {
        if (points.size < 2) return null

        val midLat = points.sumOf { it.second } / points.size
        val kx = cos(midLat * PI / 180.0)

        val xs = points.map { it.first * kx }
        val ys = points.map { it.second }
        val minX = xs.min()
        val maxX = xs.max()
        val minY = ys.min()
        val maxY = ys.max()
        val spanX = maxX - minX
        val spanY = maxY - minY
        val span = max(spanX, spanY)
        if (span <= 0.0) return null

        val step = max(1, points.size / MAX_POINTS)
        val sampled = ArrayList<Pair<Float, Float>>(points.size / step + 2)
        for (i in points.indices step step) {
            sampled += ((xs[i] - minX) / span).toFloat() to
                // Screen y grows downward; latitude grows upward.
                (1.0 - (ys[i] - minY) / span).toFloat()
        }
        // Always finish on the last fix, whatever the sampling stride did, so a
        // loop closes where it actually closed.
        val last = ((xs.last() - minX) / span).toFloat() to
            (1.0 - (ys.last() - minY) / span).toFloat()
        if (sampled.lastOrNull() != last) sampled += last

        return TrackShape(
            points = sampled,
            insetX = ((span - spanX) / span / 2).toFloat(),
            insetY = ((span - spanY) / span / 2).toFloat(),
        )
    }
}
