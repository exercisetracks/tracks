// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.api

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Route thumbnails: the shape, not the map.
 *
 * The interesting cases are all degenerate ones — a track that never moved, a
 * feature with half a coordinate — because those are what a list of several
 * hundred real activities is full of, and each one has to cost a picture rather
 * than the screen.
 */
class TrackShapesTest {

    private fun feature(id: Int, coords: String) = """
        {"type":"Feature","id":$id,"properties":{"id":$id,"name":"x"},
         "geometry":{"type":"LineString","coordinates":[$coords]}}
    """.trimIndent()

    private fun collection(vararg features: String) =
        """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""

    @Test
    fun `reads a track off a feature collection`() {
        val raw = collection(feature(7, "[-150.0,39.7],[-150.0,39.8],[-149.9,39.8]"))
        val shapes = TrackShapes.parse(raw)
        assertEquals(setOf(7), shapes.keys)
        assertEquals(3, shapes.getValue(7).points.size)
    }

    @Test
    fun `normalises into a unit square`() {
        val shape = assertNotNull(
            TrackShapes.normalise(listOf(-150.0 to 39.7, -150.0 to 39.8, -149.9 to 39.8))
        )
        shape.points.forEach { (x, y) ->
            assertTrue(x in 0f..1f, "x out of range: $x")
            assertTrue(y in 0f..1f, "y out of range: $y")
        }
    }

    @Test
    fun `north is up`() {
        // Latitude grows upward and screen y grows downward, so the
        // northernmost fix must have the smallest y.
        val shape = assertNotNull(
            TrackShapes.normalise(listOf(-150.0 to 39.0, -150.0 to 40.0))
        )
        assertTrue(shape.points.first().second > shape.points.last().second)
    }

    @Test
    fun `a track that never moved has no shape`() {
        // A treadmill session with one stray fix repeated. Rendering this would
        // divide by a zero span.
        assertNull(TrackShapes.normalise(List(50) { -150.0 to 39.7 }))
    }

    @Test
    fun `a single point has no shape`() {
        assertNull(TrackShapes.normalise(listOf(-150.0 to 39.7)))
    }

    @Test
    fun `aspect ratio is preserved, not stretched`() {
        // A north-south line: it should stay a line down the middle of the box,
        // not be stretched sideways to fill it.
        val shape = assertNotNull(
            TrackShapes.normalise(listOf(-150.0 to 39.0, -150.0 to 40.0))
        )
        assertTrue(shape.insetX > 0.4f, "narrow axis should be inset, was ${shape.insetX}")
        assertTrue(abs(shape.insetY) < 0.01f, "long axis should fill, was ${shape.insetY}")
    }

    @Test
    fun `longitude is scaled for latitude`() {
        // One degree each way at 60°N, where a degree of longitude is half a
        // degree of latitude on the ground. Un-scaled, this would come out
        // square; scaled, the east-west extent is the shorter one and gets
        // the inset.
        val shape = assertNotNull(
            TrackShapes.normalise(
                listOf(-150.0 to 60.0, -149.0 to 60.0, -149.0 to 61.0)
            )
        )
        assertTrue(shape.insetX > 0.1f, "east-west should be the short axis, was ${shape.insetX}")
    }

    @Test
    fun `long tracks are sampled down but still end where they ended`() {
        val points = (0..5000).map { -150.0 + it * 0.0001 to 39.7 + it * 0.0001 }
        val shape = assertNotNull(TrackShapes.normalise(points))
        assertTrue(shape.points.size <= TrackShapes.MAX_POINTS + 2, "was ${shape.points.size}")
        // The finish is the one point that must survive sampling: a loop that
        // stopped short of closing looks like a different route. This track runs
        // north-east throughout, so its last fix is the extreme corner — and
        // note that x does not reach 1, because longitude is scaled by cos(lat)
        // and east-west is the shorter axis here.
        assertEquals(shape.points.maxOf { it.first }, shape.points.last().first)
        assertEquals(shape.points.minOf { it.second }, shape.points.last().second)
    }

    @Test
    fun `a malformed feature costs itself and nothing else`() {
        val raw = collection(
            feature(1, "[-150.0,39.7],[-150.0,39.8]"),
            """{"type":"Feature","properties":{"id":2},"geometry":{"coordinates":[[1]]}}""",
            """{"nonsense":true}""",
            feature(3, "[-151.0,40.7],[-151.0,40.8]"),
        )
        assertEquals(setOf(1, 3), TrackShapes.parse(raw).keys)
    }

    @Test
    fun `junk is an empty map, not an exception`() {
        assertEquals(emptyMap(), TrackShapes.parse("not json at all"))
        assertEquals(emptyMap(), TrackShapes.parse("{}"))
        assertEquals(emptyMap(), TrackShapes.parse("""{"features":"nope"}"""))
    }
}
