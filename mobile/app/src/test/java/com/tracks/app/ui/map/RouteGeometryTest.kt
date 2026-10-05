// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.geometry.LatLng

/**
 * The maths under the route builder.
 *
 * Worth testing precisely because none of it announces a mistake. A segment
 * label that reports the straight-line distance instead of the trail's is a
 * plausible-looking number in the right place, and the only way to catch it is
 * to hand the function a line that deliberately does not go straight.
 *
 * No Robolectric here: this is arithmetic on plain lists, and `LatLng` is one
 * of the few MapLibre types that works against the stub android.jar.
 */
class RouteGeometryTest {

    /** A degree of latitude is ~111 km; enough to check magnitudes honestly. */
    private fun at(lat: Double, lng: Double) = listOf(lng, lat)

    @Test
    fun `segment length follows the drawn line, not the gap between handles`() {
        // Two handles 0.02° apart north-south, but the line between them
        // detours a long way east and comes back — a switchbacked trail. The
        // straight answer would be ~2.2 km; the walked one is far more.
        val line = listOf(
            at(40.00, -150.0),
            at(40.01, -149.9),
            at(40.02, -150.0),
        )
        val handles = listOf(LatLng(40.00, -150.0), LatLng(40.02, -150.0))

        val segments = routeSegments(line, handles)

        assertEquals(1, segments.size)
        val straight = LatLng(40.00, -150.0).metresTo(LatLng(40.02, -150.0))
        assertTrue(
            "expected the walked distance to exceed the straight one",
            segments[0].metres > straight * 2,
        )
    }

    @Test
    fun `each handle pair gets its own segment, in order`() {
        val line = listOf(
            at(40.0, -150.0),
            at(40.1, -150.0),
            at(40.4, -150.0),
        )
        val handles = listOf(
            LatLng(40.0, -150.0),
            LatLng(40.1, -150.0),
            LatLng(40.4, -150.0),
        )

        val segments = routeSegments(line, handles)

        assertEquals(listOf(0, 1), segments.map { it.index })
        // The second leg is three times the first, and the labels must not
        // report them the other way round.
        assertTrue(segments[1].metres > segments[0].metres * 2.5)
    }

    @Test
    fun `the label sits halfway along the line rather than between the ends`() {
        // An L-shaped leg: the midpoint of the *line* is at the corner, while
        // the midpoint of the two ends is out in empty space.
        val line = listOf(
            at(40.0, -150.0),
            at(40.0, -149.9),
            at(40.1, -149.9),
        )
        val handles = listOf(LatLng(40.0, -150.0), LatLng(40.1, -149.9))

        val segment = routeSegments(line, handles).single()

        // Halfway along an L lands on one of its arms, not on the diagonal.
        val onDiagonal = LatLng(40.05, -149.95)
        assertTrue(
            "label should be on the drawn line, not on the chord",
            LatLng(segment.lat, segment.lng).metresTo(onDiagonal) > 1_000,
        )
    }

    @Test
    fun `a route with fewer than two handles has no segments`() {
        val line = listOf(at(40.0, -150.0), at(40.1, -150.0))
        assertTrue(routeSegments(line, listOf(LatLng(40.0, -150.0))).isEmpty())
        assertTrue(routeSegments(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `handles matched forward only, so a route that doubles back stays positive`() {
        // Out and back along the same ground: the third handle sits on top of
        // the first, and a nearest-vertex search with no memory would match it
        // to vertex 0 and report a negative length.
        val line = listOf(
            at(40.0, -150.0),
            at(40.1, -150.0),
            at(40.0, -150.0),
        )
        val handles = listOf(
            LatLng(40.0, -150.0),
            LatLng(40.1, -150.0),
            LatLng(40.0, -150.0),
        )

        val segments = routeSegments(line, handles)

        assertTrue("no segment may be negative", segments.all { it.metres > 0 })
    }

    @Test
    fun `densify keeps the taps and adds points between them`() {
        val taps = listOf(LatLng(40.0, -150.0), LatLng(40.5, -150.0))

        val dense = densify(taps, cap = 20)

        assertTrue(dense.size in 15..25)
        assertEquals(taps.first(), dense.first())
        // The last tap is the end of the line, and a profile that stops short
        // of it loses whatever the route was climbing towards.
        assertEquals(taps.last(), dense.last())
    }

    @Test
    fun `densify spends its budget across the whole line, not per segment`() {
        // Three taps rather than two: a naive implementation that gives every
        // segment the full cap returns twice as many points as asked for.
        val taps = listOf(
            LatLng(40.0, -150.0),
            LatLng(40.5, -150.0),
            LatLng(41.0, -150.0),
        )

        val dense = densify(taps, cap = 30)

        assertTrue("got ${dense.size}", dense.size <= 36)
    }

    @Test
    fun `densify leaves a line it cannot subdivide alone`() {
        val single = listOf(LatLng(40.0, -150.0))
        assertEquals(single, densify(single, cap = 50))

        // Both taps in the same place: no length to spread samples along, and
        // dividing by that total would be a NaN in every coordinate.
        val stacked = listOf(LatLng(40.0, -150.0), LatLng(40.0, -150.0))
        assertEquals(stacked, densify(stacked, cap = 50))
    }

    @Test
    fun `a sampled profile skips holes in the DEM but keeps the distance`() {
        val points = listOf(
            LatLng(40.0, -150.0),
            LatLng(40.1, -150.0),
            LatLng(40.2, -150.0),
        )

        val profile = sampledProfile(points, listOf(1500.0, null, 1700.0))

        assertEquals(2, profile.size)
        assertEquals(1500.0, profile[0].elevationMetres, 0.01)
        assertEquals(1700.0, profile[1].elevationMetres, 0.01)
        // The gap is skipped as a *height*, not as ground: the second point is
        // two tenths of a degree along, not one.
        assertTrue(profile[1].metres > 20_000)
    }

    @Test
    fun `a profile with nothing usable in it is empty rather than one point`() {
        val points = listOf(LatLng(40.0, -150.0), LatLng(40.1, -150.0))
        assertTrue(sampledProfile(points, listOf(null, 1500.0)).isEmpty())
        // Mismatched lengths mean the sampler answered a different question.
        assertTrue(sampledProfile(points, listOf(1500.0)).isEmpty())
    }

    @Test
    fun `filtered ascent counts the climb and ignores the jitter`() {
        // One real 100 m climb, buried in metre-scale DEM noise. Summing every
        // positive step would report far more than 100.
        val noisy = buildList {
            var height = 1500.0
            repeat(50) { step ->
                height += if (step % 2 == 0) 2.0 else -2.0
                add(RoutePoint(step * 10.0, height))
            }
            add(RoutePoint(500.0, 1600.0))
        }

        val ascent = filteredAscent(noisy) ?: 0.0

        assertTrue("got $ascent", ascent in 95.0..130.0)
    }

    @Test
    fun `filtered ascent is null when there is no profile to read`() {
        assertNull(filteredAscent(emptyList()))
        assertNull(filteredAscent(listOf(RoutePoint(0.0, 1500.0))))
    }

    @Test
    fun `a descent alone climbs nothing`() {
        val downhill = (0..20).map { RoutePoint(it * 100.0, 2000.0 - it * 10.0) }
        assertEquals(0.0, filteredAscent(downhill) ?: -1.0, 0.01)
    }

    @Test
    fun `hour labels come off the local timestamp`() {
        assertEquals("14", hourLabel("2026-08-19T14:00"))
        assertEquals("00", hourLabel("2026-08-19T00:00"))
        // A timestamp with no time in it has no hour to show; the tail is
        // shown instead, so a provider that changed format is visible rather
        // than a strip of blank cells.
        assertEquals("08-19", hourLabel("2026-08-19"))
    }
}
