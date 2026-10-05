// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The offline elevation sampler's arithmetic, checked against Terrarium's own
 * published encoding and `elevation_sampler.py`'s tile math — see
 * [terrariumElevation], [webMercatorTile], and [bilinearElevation].
 */
class TerrainTest {

    @Test
    fun `128,0,0 is Terrarium's own zero-elevation reference pixel`() {
        assertEquals(0.0, terrariumElevation(128, 0, 0), 1e-9)
    }

    @Test
    fun `green counts whole metres above the reference`() {
        assertEquals(100.0, terrariumElevation(128, 100, 0), 1e-9)
    }

    @Test
    fun `blue counts fractional metres`() {
        assertEquals(0.5, terrariumElevation(128, 0, 128), 1e-9)
    }

    @Test
    fun `red carries the large steps, 256m per step`() {
        assertEquals(256.0, terrariumElevation(129, 0, 0), 1e-9)
    }

    @Test
    fun `the equator sits at the vertical midpoint of every zoom level`() {
        val (_, y) = webMercatorTile(lng = 0.0, lat = 0.0, zoom = 5)
        assertEquals(16.0, y, 1e-6) // half of 2^5
    }

    @Test
    fun `the date line and the prime meridian bracket a zoom level's width`() {
        val (west, _) = webMercatorTile(lng = -180.0, lat = 0.0, zoom = 4)
        val (mid, _) = webMercatorTile(lng = 0.0, lat = 0.0, zoom = 4)
        assertEquals(0.0, west, 1e-9)
        assertEquals(8.0, mid, 1e-9) // half of 2^4
    }

    @Test
    fun `latitude compresses toward the poles the way web mercator always has`() {
        // A higher latitude should sit closer to the top (smaller y) than
        // straight-line interpolation would place it — the whole reason this
        // is a log projection and not a plain scale.
        val (_, yEquator) = webMercatorTile(0.0, 0.0, 10)
        val (_, yMidLat) = webMercatorTile(0.0, 45.0, 10)
        val (_, yHighLat) = webMercatorTile(0.0, 80.0, 10)
        assertTrue(yHighLat < yMidLat)
        assertTrue(yMidLat < yEquator)
    }

    @Test
    fun `a uniform tile returns its own value everywhere, corners included`() {
        val flat: (Int, Int) -> Double? = { _, _ -> 1500.0 }
        assertEquals(1500.0, bilinearElevation(flat, px = 0.0, py = 0.0, size = 512)!!, 1e-9)
        assertEquals(1500.0, bilinearElevation(flat, px = 511.9, py = 511.9, size = 512)!!, 1e-9)
        assertEquals(1500.0, bilinearElevation(flat, px = 256.5, py = 100.2, size = 512)!!, 1e-9)
    }

    @Test
    fun `interpolation lands halfway between two corners at the midpoint`() {
        // A ramp along x alone: 0 at x=0, 100 at x=1, flat in y.
        val ramp: (Int, Int) -> Double? = { x, _ -> if (x == 0) 0.0 else 100.0 }
        val mid = bilinearElevation(ramp, px = 0.5, py = 0.0, size = 2)
        assertEquals(50.0, mid!!, 1e-9)
    }

    @Test
    fun `a fractional pixel outside the tile clamps to the nearest edge rather than throwing`() {
        val flat: (Int, Int) -> Double? = { _, _ -> 42.0 }
        assertEquals(42.0, bilinearElevation(flat, px = -5.0, py = 9999.0, size = 512)!!, 1e-9)
    }

    @Test
    fun `a missing corner propagates as null rather than being treated as sea level`() {
        val holey: (Int, Int) -> Double? = { x, y -> if (x == 1 && y == 1) null else 10.0 }
        assertNull(bilinearElevation(holey, px = 0.5, py = 0.5, size = 2))
    }
}
