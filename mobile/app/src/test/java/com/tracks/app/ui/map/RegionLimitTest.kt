// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds

/**
 * What a single offline download is allowed to cover.
 *
 * The cap used to be two degrees on a side, which refused a state: a western one is
 * often about seven degrees wide. Bounding the ground covered rather than either edge
 * lets a real trip through — including a long thin corridor along a range —
 * while still refusing the requests that are not worth even measuring.
 */
class RegionLimitTest {

    private fun box(west: Double, south: Double, east: Double, north: Double) =
        LatLngBounds.Builder()
            .include(LatLng(north, east))
            .include(LatLng(south, west))
            .build()

    @Test
    fun `a day out is nowhere near the limit`() {
        assertFalse(box(-151.0, 39.0, -150.5, 39.5).tooLargeToDownload())
    }

    /** The case this was changed for. */
    @Test
    fun `a whole state fits`() {
        // A mid-sized state: 7 degrees by 4.
        assertFalse(box(-154.0, 37.0, -147.0, 41.0).tooLargeToDownload())
    }

    @Test
    fun `two states fit`() {
        // Two such states side by side, about 12 by 5.
        assertFalse(box(-159.0, 37.0, -147.0, 42.0).tooLargeToDownload())
    }

    /** A corridor along a mountain range: long, thin, and perfectly sensible. */
    @Test
    fun `a long thin corridor is allowed`() {
        assertFalse(box(-165.0, 35.0, -150.0, 37.0).tooLargeToDownload())
    }

    @Test
    fun `a continent is refused`() {
        assertTrue(box(-125.0, 25.0, -66.0, 49.0).tooLargeToDownload())
    }

    /** Area alone would let this through; the side bound is why it does not. */
    @Test
    fun `a thin band round the planet is refused`() {
        assertTrue(box(-180.0, 40.0, -60.0, 40.5).tooLargeToDownload())
    }
}
