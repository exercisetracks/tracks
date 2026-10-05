// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reading a route's climbing out of the geometry it was planned on.
 *
 * BRouter routes *on* elevation, so the altitudes are already in the answer and
 * asking a server for them again could contradict the line that was drawn. The
 * awkward part is that the third ordinate is optional in GeoJSON, so this has
 * to survive a mixture of 2D and 3D positions rather than assuming either.
 *
 * Robolectric because the parsing goes through `org.json`, which the stub
 * android.jar throws on — the failure looks like "the profile came back empty"
 * rather than like a missing dependency, which is worth knowing before chasing
 * it in the parser.
 */
@RunWith(RobolectricTestRunner::class)
// A bare Application: booting the real one starts WorkManager, which a pure
// parsing test has no use for and which fails without its initializer.
@Config(application = android.app.Application::class)
class RouteProfileTest {

    private fun feature(coordinates: String) = """
        {"type":"FeatureCollection","features":[{"type":"Feature",
         "properties":{},
         "geometry":{"type":"LineString","coordinates":[$coordinates]}}]}
    """.trimIndent()

    @Test
    fun `distance accumulates along the line`() {
        // Three points a little under 1 km apart in longitude at 40 degrees.
        val profile = elevationProfile(
            feature("[-150.0,40.0,1600],[-150.01,40.0,1650],[-150.02,40.0,1700]")
        )
        assertEquals(3, profile.size)
        assertEquals(0.0, profile[0].metres, 1e-9)
        assertTrue("second point is ~850m along", profile[1].metres in 800.0..900.0)
        // Evenly spaced input, so the second gap matches the first.
        assertEquals(profile[1].metres * 2, profile[2].metres, 1.0)
    }

    @Test
    fun `elevation comes through unchanged`() {
        val profile = elevationProfile(feature("[-150.0,40.0,1600],[-150.01,40.0,1750]"))
        assertEquals(1600.0, profile.first().elevationMetres, 1e-9)
        assertEquals(1750.0, profile.last().elevationMetres, 1e-9)
    }

    /** A gap drawn at zero would dive the profile to sea level and back. */
    @Test
    fun `positions without an altitude are dropped, not zeroed`() {
        val profile = elevationProfile(
            feature("[-150.0,40.0,1600],[-150.01,40.0],[-150.02,40.0,1700]")
        )
        assertEquals(2, profile.size)
        assertTrue("no sea-level artefact", profile.none { it.elevationMetres < 1000 })
    }

    @Test
    fun `a flat route still yields points`() {
        val profile = elevationProfile(feature("[-150.0,40.0,1600],[-150.01,40.0,1600]"))
        assertEquals(2, profile.size)
        assertEquals(1600.0, profile[1].elevationMetres, 1e-9)
    }

    @Test
    fun `malformed geometry is empty rather than a crash`() {
        assertTrue(elevationProfile("not json").isEmpty())
        assertTrue(elevationProfile("""{"type":"FeatureCollection","features":[]}""").isEmpty())
    }
}
