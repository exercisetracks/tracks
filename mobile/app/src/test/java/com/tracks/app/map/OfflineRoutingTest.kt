// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.map

import btools.router.FormatJson
import btools.router.OsmPathElement
import btools.router.OsmTrack
import btools.router.RoutingContext
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two seams where offline routing can break silently.
 *
 * Neither is the route search itself — that is BRouter's own, tested upstream,
 * and exercising it here would mean checking in a multi-megabyte rd5. What is
 * ours is the naming rule that decides *which* rd5 to ask for, and the
 * assumption that the vendored engine's output is shaped like the server's.
 */
// Robolectric only for a real org.json: the stub android.jar on the unit-test
// classpath throws from every JSONObject method, and this suite parses the
// engine's output rather than string-matching it.
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class OfflineRoutingTest {

    /**
     * The filenames must match the server's `_cells_for_bbox` exactly, because
     * the phone computes them with no network — that is the entire point — and
     * a mismatch would ask for a file that 404s, or worse, report an area as
     * covered when the cell it holds is next door.
     */
    @Test
    fun `names the 5 degree cell a box falls in`() {
        assertEquals(listOf("W155_N35.rd5"), cellNames(-151.5, 38.5, -151.0, 39.0))
        assertEquals(listOf("E5_N45.rd5"), cellNames(7.0, 46.0, 8.0, 47.0))
    }

    @Test
    fun `names every cell a box straddles`() {
        // Across both a longitude and a latitude boundary: four cells, and all
        // four are needed — a route that leaves the one it started in fails
        // partway through the search rather than at the edge.
        val names = cellNames(-150.2, 39.5, -149.8, 40.5).sorted()
        assertEquals(
            listOf("W150_N35.rd5", "W150_N40.rd5", "W155_N35.rd5", "W155_N40.rd5"),
            names,
        )
    }

    @Test
    fun `names cells south of the equator and west of Greenwich`() {
        // The naming rule is the cell's lower-left corner, so a box at 2°S sits
        // in S5, not S0 — an off-by-one-cell here is invisible in the northern
        // hemisphere and wrong everywhere else.
        assertEquals(listOf("W5_S5.rd5"), cellNames(-3.0, -2.0, -2.5, -1.0))
        assertEquals(listOf("E0_N0.rd5"), cellNames(1.0, 1.0, 2.0, 2.0))
    }

    /**
     * `MapToolsViewModel.withSnapped` reads three properties by name out of
     * whatever GeoJSON it is handed, and now that document can come from the
     * vendored engine instead of the server. If a BRouter refresh renamed one,
     * the route would still draw and the distance readout would silently go
     * blank — which is exactly the kind of failure nobody reports.
     */
    @Test
    fun `the vendored formatter emits the properties the map reads`() {
        val track = OsmTrack().apply {
            distance = 4200
            ascend = 310
            // Two nodes: the formatter walks them for the geometry, and an
            // empty track takes a different branch that would not prove much.
            nodes.add(OsmPathElement.create(180_000_000, 90_000_000, 1000, null))
            nodes.add(OsmPathElement.create(180_001_000, 90_001_000, 1100, null))
        }

        val json = JSONObject(FormatJson(RoutingContext()).format(track))
        val properties = json.getJSONArray("features")
            .getJSONObject(0)
            .getJSONObject("properties")

        assertEquals("4200", properties.getString("track-length"))
        assertEquals("310", properties.getString("filtered ascend"))
        assertTrue(properties.has("total-time"), "total-time is the route's duration readout")
        assertEquals(
            "LineString",
            json.getJSONArray("features").getJSONObject(0)
                .getJSONObject("geometry").getString("type"),
        )
    }
}
