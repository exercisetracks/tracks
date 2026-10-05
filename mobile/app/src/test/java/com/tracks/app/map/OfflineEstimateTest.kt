// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds

/**
 * What a download would cost the phone, before it starts.
 *
 * This is the number that was missing, and its absence is most of what "the
 * download failed" meant. The server's estimate answers how many bytes it must
 * cut out of the planet archives; the phone fetches every tile of every source
 * individually, and the two numbers are separated by three orders of magnitude.
 * A box that reads as a reasonable 700 MB on the server is a third of a million
 * requests here — hours of work, presented as a progress bar that creeps, which
 * anybody sane reads as broken and deletes.
 *
 * So the arithmetic is worth pinning: it is the only thing standing between a
 * casual drag of a corner and an afternoon of downloading.
 */
class OfflineEstimateTest {

    private fun box(west: Double, south: Double, east: Double, north: Double) =
        LatLngBounds.Builder()
            .include(LatLng(north, east))
            .include(LatLng(south, west))
            .build()

    /** A degree square, the shape of a long weekend. */
    private val dayOut = box(-151.0, 39.5, -150.5, 40.0)

    @Test
    fun `a single level of a tiny box is a tile or four`() {
        // Small enough to sit inside one tile, though it may straddle a
        // boundary in either axis — so between one and four, never zero.
        val tiles = OfflineEstimate.pyramid(box(-150.001, 39.999, -150.0, 40.0), 8, 8)
        assertTrue("expected a handful of tiles, got $tiles", tiles in 1..4)
    }

    @Test
    fun `each level costs about four times the one above it`() {
        val z10 = OfflineEstimate.pyramid(dayOut, 10, 10)
        val z11 = OfflineEstimate.pyramid(dayOut, 11, 11)
        // Not exactly four: the box's edges land wherever they land, and a
        // partly-covered tile counts whole. Close enough to catch a doubled or
        // halved zoom, which is the mistake worth catching.
        assertTrue("z11=$z11 should be roughly 4x z10=$z10", z11 > z10 * 3)
        assertTrue("z11=$z11 should be roughly 4x z10=$z10", z11 < z10 * 6)
    }

    /**
     * The reason this is Web Mercator and not a rectangle of degrees.
     *
     * A degree of latitude is a smaller slice of the map the further north you
     * go, so the same box of degrees is *more* tiles in Alaska than at 40°N.
     * Treating latitude as linear undercounts by nearly half at 60°N — which is
     * exactly the wrong direction for a guard rail.
     */
    @Test
    fun `the same box of degrees costs more in the north`() {
        val south = OfflineEstimate.pyramid(box(-151.0, 0.0, -150.0, 1.0), 12, 12)
        val north = OfflineEstimate.pyramid(box(-151.0, 60.0, -150.0, 61.0), 12, 12)
        assertTrue("north=$north should exceed south=$south", north > south)
    }

    @Test
    fun `a zoom range backwards is nothing rather than an exception`() {
        assertEquals(0L, OfflineEstimate.pyramid(dayOut, 12, 8))
    }

    @Test
    fun `a day out is a download nobody needs warning about`() {
        assertEquals(OfflineEstimate.Level.Fine, OfflineEstimate.weigh(dayOut).level)
    }

    /** The area the user actually tried: reasonable-looking, and hours of work. */
    @Test
    fun `a two degree box is worth a warning`() {
        val weight = OfflineEstimate.weigh(box(-118.65, 46.74, -115.81, 48.93))
        assertEquals(OfflineEstimate.Level.Long, weight.level)
        assertTrue("expected six figures, got ${weight.resources}", weight.resources > 100_000)
    }

    /** A whole state is not a thing a phone finishes. */
    @Test
    fun `a state is beyond what the phone should attempt`() {
        val weight = OfflineEstimate.weigh(box(-154.0, 37.0, -147.0, 41.0))
        assertEquals(OfflineEstimate.Level.BeyondPhone, weight.level)
        assertTrue(weight.resources > OfflineEstimate.CEILING)
    }

    @Test
    fun `the levels are ordered by the thresholds they name`() {
        assertTrue(OfflineEstimate.HEAVY < OfflineEstimate.CEILING)
    }
}
