// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import com.tracks.core.api.CourseDetail
import com.tracks.core.api.CourseProfile
import com.tracks.core.api.CourseProfilePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning a saved track's stored profile into the chart's points.
 *
 * The interesting case is the null elevation. The server writes one wherever a
 * track leaves the DEM's coverage, and treating it as zero draws a sea-level
 * cliff in the middle of a mountain route — which also flattens every real
 * point on the chart, since the vertical scale then spans 3,000 m of nothing.
 */
class TrackProfileTest {

    private fun profile(vararg samples: Pair<Double, Double?>) =
        CourseProfile(points = samples.map { CourseProfilePoint(it.first, it.second) })

    @Test
    fun `kilometres become metres`() {
        val points = profilePoints(profile(0.0 to 1500.0, 1.5 to 1650.0))
        assertEquals(0.0, points[0].metres, 1e-9)
        assertEquals(1500.0, points[1].metres, 1e-9)
    }

    @Test
    fun `elevation is carried through unchanged`() {
        val points = profilePoints(profile(0.0 to 1500.0, 1.0 to 1650.5))
        assertEquals(1650.5, points[1].elevationMetres, 1e-9)
    }

    @Test
    fun `samples off the edge of the elevation model are dropped`() {
        val points = profilePoints(profile(0.0 to 1500.0, 1.0 to null, 2.0 to 1700.0))
        assertEquals(2, points.size)
        assertTrue(points.none { it.elevationMetres == 0.0 })
    }

    @Test
    fun `a profile that is entirely gaps yields nothing to draw`() {
        assertEquals(emptyList<RoutePoint>(), profilePoints(profile(0.0 to null, 1.0 to null)))
    }

    @Test
    fun `no profile at all is not a crash`() {
        assertEquals(emptyList<RoutePoint>(), profilePoints(null))
        assertEquals(emptyList<RoutePoint>(), profilePoints(CourseProfile()))
    }

    @Test
    fun `a detail renders as the same row the list shows`() {
        // The two sheets share their action block, so a detail has to be able
        // to present itself as a summary without losing what the row reads.
        val detail = CourseDetail(
            id = 7, name = "Ridge", color = "#DC2626", sport = "hiking",
            distanceMetres = 12345.0, ascentMetres = 800.0,
            loadToDevice = true, deviceStatus = "on_device",
            bounds = listOf(-150.0, 40.0, -149.0, 41.0),
        )
        val summary = detail.summary()

        assertEquals(7, summary.id)
        assertEquals("Ridge", summary.name)
        assertEquals("#DC2626", summary.color)
        assertEquals(12345.0, summary.distanceMetres, 1e-9)
        assertTrue(summary.loadToDevice)
        assertEquals("on_device", summary.deviceStatus)
        assertEquals(listOf(-150.0, 40.0, -149.0, 41.0), summary.bounds)
    }

    @Test
    fun `the shared row label agrees for a detail and its summary`() {
        val detail = CourseDetail(id = 1, deviceStatus = "pending_upload", loadToDevice = true)
        assertEquals("queued", deviceLabel(detail.summary()))
    }
}
