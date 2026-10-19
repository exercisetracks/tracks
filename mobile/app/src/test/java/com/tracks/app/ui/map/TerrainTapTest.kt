// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.maplibre.android.geometry.LatLng

class TerrainTapTest {

    private val origin = LatLng(46.0, 8.0)
    /** A camera 1 km up over [origin], its ray reaching sea level 2 km north. */
    private val camera = TerrainTap.Camera(origin, altitudeM = 1000.0)
    private val seaLevel = TerrainTap.destination(origin, 0.0, 2000.0)

    private fun northOf(p: LatLng) = TerrainTap.distanceM(origin, p)

    /**
     * The bug: in 3D a tap selected a point well above the finger, because the
     * sea-level hit is beyond the hillside the finger was on. On a 500 m
     * plateau the ray is that high halfway out.
     */
    @Test
    fun `a tap on raised ground lands where the ray meets it, short of sea level`() = runBlocking {
        val ground = TerrainTap.ground(seaLevel, camera) { 500.0 }
        assertEquals(1000.0, northOf(ground), 2.0)
    }

    /** A slope rising away from the camera: h = north / 4 meets the ray at 2/3. */
    @Test
    fun `the walk settles on a slope`() = runBlocking {
        val ground = TerrainTap.ground(seaLevel, camera) { p -> northOf(p) / 4 }
        assertEquals(2000.0 * 2 / 3, northOf(ground), 2.0)
    }

    /** No terrain data: the sea-level point stands, exactly as before. */
    @Test
    fun `without heights the tap is left alone`() = runBlocking {
        val ground = TerrainTap.ground(seaLevel, camera) { null }
        assertEquals(seaLevel, ground)
    }
}
