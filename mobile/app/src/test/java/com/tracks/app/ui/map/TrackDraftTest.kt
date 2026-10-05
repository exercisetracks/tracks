// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.geometry.LatLng
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a drawn line turns into when it is saved.
 *
 * The failure this guards against is silent and expensive: GeoJSON is
 * longitude-first and the rest of the app is latitude-first, so getting it
 * backwards saves every track in the wrong hemisphere and nothing complains at
 * any point along the way.
 *
 * Robolectric because the coordinates come back through `org.json` — see
 * [RouteProfileTest] for why the stub android.jar is not enough.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class TrackDraftTest {

    private fun line(coordinates: String) =
        """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},""" +
            """"geometry":{"type":"LineString","coordinates":[$coordinates]}}]}"""

    @Test
    fun `coordinates come from the snapped line when there is one`() {
        // The taps and the snapped line disagree, which is the whole point of
        // snapping — what gets saved has to be the line that was drawn on the
        // map, not the points that suggested it.
        val draft = RouteDraft(
            waypoints = listOf(LatLng(40.0, -150.0), LatLng(40.1, -150.1)),
            geoJson = line("[-150.0,40.0,1500.0],[-150.05,40.05,1550.0],[-150.1,40.1,1600.0]"),
        )
        val coordinates = draft.coordinates()

        assertEquals(3, coordinates.size)
        assertEquals(listOf(-150.0, 40.0, 1500.0), coordinates.first())
    }

    @Test
    fun `elevation survives into the saved track`() {
        // BRouter's third ordinate is a real height and the server writes it
        // into the track's profile; trimming to two would throw the climbing
        // away at the last step.
        val draft = RouteDraft(geoJson = line("[-150.0,40.0,1500.0],[-150.1,40.1,1600.0]"))
        assertTrue(draft.coordinates().all { it.size == 3 })
    }

    @Test
    fun `coordinates fall back to the taps when nothing was snapped`() {
        val draft = RouteDraft(waypoints = listOf(LatLng(40.0, -150.0), LatLng(41.0, -151.0)))
        assertEquals(
            listOf(listOf(-150.0, 40.0), listOf(-151.0, 41.0)),
            draft.coordinates(),
        )
    }

    @Test
    fun `longitude comes first, as GeoJSON wants it`() {
        val draft = RouteDraft(waypoints = listOf(LatLng(40.0, -150.0), LatLng(41.0, -151.0)))
        val first = draft.coordinates().first()
        assertEquals(-150.0, first[0], 1e-9)
        assertEquals(40.0, first[1], 1e-9)
    }

    @Test
    fun `a one-tap draft has nothing to save`() {
        assertEquals(1, RouteDraft(waypoints = listOf(LatLng(40.0, -150.0))).coordinates().size)
    }

    @Test
    fun `a snapped line too short to be a line falls back to the taps`() {
        // A snap that came back with a single position is not a route; saving
        // it would be rejected by the server, and the taps are still good.
        val draft = RouteDraft(
            waypoints = listOf(LatLng(40.0, -150.0), LatLng(41.0, -151.0)),
            geoJson = line("[-150.0,40.0]"),
        )
        assertEquals(2, draft.coordinates().size)
    }

    @Test
    fun `unparseable geometry does not lose the taps`() {
        val draft = RouteDraft(
            waypoints = listOf(LatLng(40.0, -150.0), LatLng(41.0, -151.0)),
            geoJson = "{not json at all",
        )
        assertEquals(2, draft.coordinates().size)
    }

    @Test
    fun `positions missing an ordinate are dropped rather than half read`() {
        val coordinates = lineCoordinates(line("[-150.0,40.0],[-150.1],[-150.2,40.2]"))
        assertEquals(2, coordinates.size)
    }
}
