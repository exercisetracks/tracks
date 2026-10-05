// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The heatmap's coordinate order, pinned.
 *
 * Every case here is a restatement of one bug: the client read
 * `/activities/heatmap` as `[lng, lat]` when the server sends `[lat, lng]`. The
 * transposition was invisible for anyone near the prime meridian and fatal
 * everywhere else, because a longitude past ±90 is not a valid latitude and
 * MapLibre throws rather than clamping.
 */
class GeoTest {

    @Test
    fun `latitude comes first`() {
        // The North Pacific. A longitude past ±90 is the case that crashed: read as a
        // latitude it is out of range, and MapLibre's LatLng throws on
        // construction rather than returning something wrong-but-drawable.
        val point = heatmapPoint(listOf(40.015, -150.2705))
        assertEquals(LatLngPoint(40.015, -150.2705), point)
    }

    @Test
    fun `a longitude beyond ninety is not mistaken for a latitude`() {
        // The regression proper. If the pair were read in the other order this
        // would be rejected as an impossible latitude — so a non-null result is
        // the assertion, not the values.
        listOf(
            listOf(35.68, 139.69),    // Tokyo
            listOf(-33.87, 151.21),   // Sydney
            listOf(37.77, -122.42),   // San Francisco
        ).forEach { pair ->
            val point = heatmapPoint(pair)
            assertEquals(pair[0], point?.lat, "latitude for $pair")
            assertEquals(pair[1], point?.lng, "longitude for $pair")
        }
    }

    @Test
    fun `the intensity mode's third element is ignored`() {
        // mode=intensity appends a value; a caller wanting a position should
        // not have to know that.
        assertEquals(
            LatLngPoint(51.5, -0.12),
            heatmapPoint(listOf(51.5, -0.12, 0.87)),
        )
    }

    @Test
    fun `unusable pairs are dropped rather than thrown`() {
        // One bad row costs one point, not the screen. This is the whole reason
        // the check exists: the previous code had no notion of an unusable pair
        // and took the process down when it met one.
        assertNull(heatmapPoint(emptyList()))
        assertNull(heatmapPoint(listOf(40.0)))
        assertNull(heatmapPoint(listOf(91.0, 0.0)))
        assertNull(heatmapPoint(listOf(-90.1, 0.0)))
        assertNull(heatmapPoint(listOf(0.0, 180.1)))
        assertNull(heatmapPoint(listOf(Double.NaN, 0.0)))
    }

    @Test
    fun `the poles and the antimeridian are valid`() {
        // Inclusive bounds — a track that genuinely reaches ±90 or ±180 is real
        // data, and rejecting it would be the same class of error in reverse.
        assertEquals(LatLngPoint(90.0, 180.0), heatmapPoint(listOf(90.0, 180.0)))
        assertEquals(LatLngPoint(-90.0, -180.0), heatmapPoint(listOf(-90.0, -180.0)))
    }

    @Test
    fun `a whole response keeps the good points and drops the rest`() {
        val points = heatmapPoints(
            listOf(
                listOf(40.0, -150.0),
                listOf(999.0, 0.0),
                listOf(41.0, -151.0),
                listOf(1.0),
            ),
        )
        assertEquals(listOf(LatLngPoint(40.0, -150.0), LatLngPoint(41.0, -151.0)), points)
    }
}
