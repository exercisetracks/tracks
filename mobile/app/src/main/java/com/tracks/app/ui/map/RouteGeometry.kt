// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import org.maplibre.android.geometry.LatLng
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The maths behind the route builder, with no Android in it.
 *
 * Split out from the view model because these are the parts worth testing
 * exactly — a segment length that is quietly wrong is not something anybody
 * spots by looking at a map, and a densifier that drops the last point costs
 * the end of every elevation profile.
 */

/** One drawn segment: how long it is, and where its label belongs. */
data class RouteSegment(
    /** The handle this segment leaves from. */
    val index: Int,
    val metres: Double,
    /** Halfway along the drawn line, not halfway between the handles. */
    val lat: Double,
    val lng: Double,
)

/**
 * Split a drawn line at its handles and measure each piece.
 *
 * The naive answer — the straight-line distance between consecutive taps — is
 * wrong for exactly the case the builder exists for. Two taps either side of a
 * ridge are 400 m apart and the trail between them is three kilometres, and it
 * is the three that decides whether the segment is worth walking.
 *
 * So the line is cut where it passes closest to each handle. Snapping moves a
 * tap onto the nearest path, so the handle is never exactly on the line and a
 * nearest-vertex search is the only honest way back to it. The search runs
 * forward only, from the previous handle's vertex: a route that doubles back
 * past its own start would otherwise match a later handle to an earlier vertex
 * and report a negative length.
 *
 * @param line the drawn geometry as `[lng, lat, ele?]`, in order.
 */
internal fun routeSegments(line: List<List<Double>>, handles: List<LatLng>): List<RouteSegment> {
    if (line.size < 2 || handles.size < 2) return emptyList()

    val cumulative = cumulativeMetres(line)

    var from = 0
    val vertices = handles.map { handle ->
        val at = nearestVertex(line, handle, from)
        // Never behind the previous handle, and never the very last vertex
        // until it is the last handle — a segment with no room left in the
        // line is a zero-length label sitting on top of another.
        from = max(from, at)
        from
    }

    return (0 until handles.size - 1).mapNotNull { i ->
        val start = vertices[i]
        val end = vertices[i + 1]
        if (end <= start) return@mapNotNull null
        val metres = cumulative[end] - cumulative[start]
        val midpoint = pointAt(line, cumulative, cumulative[start] + metres / 2)
        RouteSegment(index = i, metres = metres, lat = midpoint[1], lng = midpoint[0])
    }
}

/** Running distance to each vertex, so any span is one subtraction. */
private fun cumulativeMetres(line: List<List<Double>>): DoubleArray {
    val out = DoubleArray(line.size)
    for (i in 1 until line.size) {
        out[i] = out[i - 1] + metresBetween(
            line[i - 1][1], line[i - 1][0], line[i][1], line[i][0],
        )
    }
    return out
}

/** The vertex of [line] closest to [handle], searching from [from] onward. */
private fun nearestVertex(line: List<List<Double>>, handle: LatLng, from: Int): Int {
    var best = from
    var bestDistance = Double.MAX_VALUE
    for (i in from until line.size) {
        val distance = metresBetween(handle.latitude, handle.longitude, line[i][1], line[i][0])
        if (distance < bestDistance) {
            bestDistance = distance
            best = i
        }
    }
    return best
}

/** The position [target] metres along the line, interpolated between vertices. */
private fun pointAt(
    line: List<List<Double>>,
    cumulative: DoubleArray,
    target: Double,
): List<Double> {
    if (target <= 0) return line.first()
    if (target >= cumulative.last()) return line.last()

    var i = 1
    while (i < cumulative.size && cumulative[i] < target) i++
    val span = cumulative[i] - cumulative[i - 1]
    // Two vertices in the same place: no direction to interpolate along, and
    // the earlier one is as good an answer as any.
    if (span <= 0) return line[i - 1]
    val fraction = (target - cumulative[i - 1]) / span
    return listOf(
        line[i - 1][0] + (line[i][0] - line[i - 1][0]) * fraction,
        line[i - 1][1] + (line[i][1] - line[i - 1][1]) * fraction,
    )
}

/**
 * Points along a line, close enough together to sample ground under it.
 *
 * A line drawn with snapping off has only the taps in it — sometimes two, for a
 * kilometre of terrain — so asking the DEM for its heights would return a
 * profile of two points and call a valley a straight slope. Interpolating first
 * is what makes the answer describe the ground rather than the line.
 *
 * The cap is not politeness: each sample is a DEM lookup on the server, and the
 * profile is redrawn on every tap. Spreading a fixed budget over the whole line
 * keeps a 2 km sketch and a 40 km one costing the same.
 */
internal fun densify(points: List<LatLng>, cap: Int = ELEVATION_SAMPLES): List<LatLng> {
    if (points.size < 2 || cap < 2) return points

    val lengths = points.zipWithNext { a, b -> a.metresTo(b) }
    val total = lengths.sum()
    if (total <= 0) return points

    val out = ArrayList<LatLng>(cap + points.size)
    // The budget buys the points *between* the taps; the taps themselves are
    // already there and are not negotiable — they are what the user chose.
    val spacing = total / (cap - 1).coerceAtLeast(1)

    points.zipWithNext().forEachIndexed { index, (from, to) ->
        out.add(from)
        val steps = min((lengths[index] / spacing).toInt(), cap)
        for (step in 1 until steps) {
            val fraction = step.toDouble() / steps
            out.add(
                LatLng(
                    from.latitude + (to.latitude - from.latitude) * fraction,
                    from.longitude + (to.longitude - from.longitude) * fraction,
                )
            )
        }
    }
    out.add(points.last())
    return out
}

/**
 * A profile from sampled ground, dropping the holes.
 *
 * A DEM has gaps — coastline, the edge of a downloaded tile — and the sampler
 * reports them as nulls rather than guessing. Drawing those as zero would put a
 * sea-level notch in the middle of a mountain, so the point is skipped and the
 * line closes over it. Distance still accumulates across the gap, because the
 * ground did not stop existing.
 */
internal fun sampledProfile(points: List<LatLng>, elevations: List<Double?>): List<RoutePoint> {
    if (points.size < 2 || elevations.size != points.size) return emptyList()

    val out = ArrayList<RoutePoint>(points.size)
    var travelled = 0.0
    points.forEachIndexed { index, point ->
        if (index > 0) travelled += points[index - 1].metresTo(point)
        elevations[index]?.let { out.add(RoutePoint(travelled, it)) }
    }
    return if (out.size > 1) out else emptyList()
}

/**
 * Climbing, with the model's noise filtered out.
 *
 * Summing every positive step turns a DEM's metre-scale jitter into hundreds of
 * metres of imaginary ascent over a long flat line — the number BRouter calls
 * "plain-ascend" and does not show. A climb only counts once it has gained
 * [threshold] above the last low point, which is what a person would call a
 * climb, and is why this matches the figure a snapped route reports.
 */
internal fun filteredAscent(profile: List<RoutePoint>, threshold: Double = 3.0): Double? {
    if (profile.size < 2) return null

    var ascent = 0.0
    var reference = profile.first().elevationMetres
    var peak = reference

    profile.forEach { point ->
        val height = point.elevationMetres
        if (height > peak) peak = height
        if (height < reference) {
            reference = height
            peak = height
        } else if (peak - reference >= threshold) {
            ascent += peak - reference
            reference = peak
        }
    }
    return ascent
}

/** Equirectangular metres — accurate well past what a drawn line needs. */
internal fun metresBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val meanLat = Math.toRadians((lat1 + lat2) / 2)
    val dx = Math.toRadians(lng2 - lng1) * cos(meanLat) * EARTH_RADIUS_METRES
    val dy = Math.toRadians(lat2 - lat1) * EARTH_RADIUS_METRES
    return hypot(dx, dy)
}

internal fun LatLng.metresTo(other: LatLng): Double =
    metresBetween(latitude, longitude, other.latitude, other.longitude)

/** Whether two positions are close enough that one replaced the other. */
internal fun LatLng.sameAs(other: LatLng): Boolean =
    abs(latitude - other.latitude) < 1e-9 && abs(longitude - other.longitude) < 1e-9

private const val EARTH_RADIUS_METRES = 6_371_000.0

/**
 * How many heights one un-snapped line is worth asking for.
 *
 * Enough that a profile has shape at phone width — the chart is under 400 px
 * wide, so past this the samples are finer than the pixels.
 */
internal const val ELEVATION_SAMPLES = 160
