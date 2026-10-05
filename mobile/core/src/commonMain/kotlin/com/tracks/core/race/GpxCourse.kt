// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.race

import com.tracks.core.parse.PyMath

/**
 * A race course from a GPX file — a port of `parse_gpx` and
 * `extract_path_points` in `calculators/race_predictor/course.py`.
 *
 * ## Why a scanner and not an XML parser
 *
 * commonMain has no XML library, and these two functions read exactly one
 * element: every `<trkpt>` with its `lat`/`lon` attributes and `<ele>` child.
 * A scanner over those is small enough to hold to the server by fixtures
 * (`GpxCourseFixtureTest`), where a general parser would be a dependency for
 * the sake of one tag. It keeps ElementTree's namespace rule: a document whose
 * root is in GPX 1.0 or 1.1's namespace, or in none, yields its track points;
 * a document in any other namespace yields none, as `findall` would.
 *
 * Not reproduced: the server rejects malformed XML outright, where this reads
 * whatever well-formed track points it finds. A file bad enough to differ
 * would fail the server's upload, not produce a different course.
 */
object GpxCourse {

    data class Point(val lat: Double, val lon: Double, val ele: Double?)

    private val ROOT = Regex("""<(?:[\w.-]+:)?gpx\b([^>]*)>""", RegexOption.IGNORE_CASE)
    private val XMLNS = Regex("""\bxmlns\s*=\s*["']([^"']*)["']""")
    private val TRKPT = Regex("""<((?:[\w.-]+:)?)trkpt\b([^>]*?)(/>|>(.*?)</\1trkpt\s*>)""", RegexOption.DOT_MATCHES_ALL)
    private val ELE = Regex("""<(?:[\w.-]+:)?ele\b[^>]*>([^<]*)</""")
    private fun attr(attrs: String, name: String): String? =
        Regex("""\b$name\s*=\s*["']([^"']*)["']""").find(attrs)?.groupValues?.get(1)

    /** Every track point, in document order; empty when the namespace is not GPX's (or absent). */
    fun points(text: String): List<Point> {
        val root = ROOT.find(text) ?: return emptyList()
        val ns = XMLNS.find(root.groupValues[1])?.groupValues?.get(1)
        if (ns != null && "topografix.com/GPX/1/1" !in ns && "topografix.com/GPX/1/0" !in ns) return emptyList()
        return TRKPT.findAll(text).mapNotNull { m ->
            // float(pt.get("lat", 0)): an absent attribute is 0, an unreadable one skips the point.
            val lat = attr(m.groupValues[2], "lat")?.let { it.trim().toDoubleOrNull() ?: return@mapNotNull null } ?: 0.0
            val lon = attr(m.groupValues[2], "lon")?.let { it.trim().toDoubleOrNull() ?: return@mapNotNull null } ?: 0.0
            val ele = m.groupValues[4].let { body -> ELE.find(body)?.groupValues?.get(1) }
                ?.takeIf { it.isNotEmpty() }?.trim()?.toDoubleOrNull()
            Point(lat, lon, ele)
        }.toList()
    }

    /** `parse_gpx`: the course as ~100 m segments of distance, gain and gradient. */
    fun segments(text: String): List<Segment> {
        val raw = points(text)
        if (raw.size < 2) return emptyList()
        val out = mutableListOf<Segment>()
        var dist = 0.0
        var gain = 0.0
        var prev = raw[0]
        for (cur in raw.drop(1)) {
            dist += RacePredictor.haversineM(prev.lat, prev.lon, cur.lat, cur.lon)
            gain += if (cur.ele != null && prev.ele != null) cur.ele - prev.ele else 0.0
            if (dist >= 100) {
                out += segment(dist, gain); dist = 0.0; gain = 0.0
            }
            prev = cur
        }
        if (dist > 10) out += segment(dist, gain)
        return out
    }

    private fun segment(dist: Double, gain: Double): Segment {
        val grad = if (dist > 0) gain / dist else 0.0
        return Segment(
            distanceM = PyMath.round(dist, 1),
            gradient = PyMath.round(grad, 4),
            elevationGainM = PyMath.round(gain, 1),
        )
    }

    /** `extract_path_points`: up to [maxPoints] of `[lat, lon, ele]`, evenly sampled. */
    fun path(text: String, maxPoints: Int = 300): List<Point> {
        val coords = points(text).map {
            Point(PyMath.round(it.lat, 6), PyMath.round(it.lon, 6), it.ele?.let { e -> PyMath.round(e, 1) })
        }
        if (coords.size <= maxPoints) return coords
        val step = coords.size.toDouble() / maxPoints
        // Python's round() on i * step: half to even.
        return (0 until maxPoints).map { i -> coords[PyMath.roundToLong(i * step).toInt()] }
    }
}
