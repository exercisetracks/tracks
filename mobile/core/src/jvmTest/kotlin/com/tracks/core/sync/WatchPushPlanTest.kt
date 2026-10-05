// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.sync

import com.tracks.core.api.CourseSummary
import com.tracks.core.api.Waypoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The on-device diff that replaces `_course_upload_items`/
 * `_waypoint_upload_items` when there is no server to ask — see
 * `com.tracks.app.device.WatchManager`.
 */
class WatchPushPlanTest {

    // ── Courses ──────────────────────────────────────────────────────────────

    @Test
    fun `a course flagged for the watch and not yet on it needs pushing`() {
        val course = CourseSummary(id = 1, name = "Ridge Loop", loadToDevice = true, deviceStatus = "pending_upload")
        assertEquals(listOf(CoursePushJob(1, "Ridge Loop", "TRK_1.fit")), coursesToPush(listOf(course)))
    }

    @Test
    fun `a course already believed on the device needs nothing`() {
        val course = CourseSummary(id = 1, loadToDevice = true, deviceStatus = "on_device")
        assertTrue(coursesToPush(listOf(course)).isEmpty())
    }

    @Test
    fun `a course not flagged for the watch needs nothing`() {
        val course = CourseSummary(id = 1, loadToDevice = false, deviceStatus = null)
        assertTrue(coursesToPush(listOf(course)).isEmpty())
    }

    @Test
    fun `an external course is never pushed, flagged or not`() {
        // Pulled FROM the watch already — there is nothing further to send it.
        val course = CourseSummary(id = 1, loadToDevice = true, deviceStatus = "pending_upload", isExternal = true)
        assertTrue(coursesToPush(listOf(course)).isEmpty())
    }

    // ── Waypoints ────────────────────────────────────────────────────────────

    @Test
    fun `nothing flagged and nothing believed on the watch is nothing to do`() {
        assertEquals(WaypointPushJob.NothingToDo, waypointPushPlan(emptyList()))
        assertEquals(
            WaypointPushJob.NothingToDo,
            waypointPushPlan(listOf(Waypoint(id = 1, loadToDevice = false, onWatch = false))),
        )
    }

    @Test
    fun `nothing flagged but something still believed on the watch means clear it`() {
        val plan = waypointPushPlan(listOf(Waypoint(id = 1, loadToDevice = false, onWatch = true)))
        assertEquals(WaypointPushJob.Clear, plan)
    }

    @Test
    fun `flagged places that exactly match what is believed on the watch need nothing`() {
        val waypoints = listOf(
            Waypoint(id = 1, loadToDevice = true, onWatch = true),
            Waypoint(id = 2, loadToDevice = true, onWatch = true),
        )
        assertEquals(WaypointPushJob.NothingToDo, waypointPushPlan(waypoints))
    }

    @Test
    fun `a newly flagged place that is not yet believed on the watch means rebuild with every flagged place`() {
        val waypoints = listOf(
            Waypoint(id = 1, name = "Spring", loadToDevice = true, onWatch = true),
            Waypoint(id = 2, name = "Camp", loadToDevice = true, onWatch = false),
        )
        val plan = waypointPushPlan(waypoints) as WaypointPushJob.Rebuild
        assertEquals(setOf(1, 2), plan.waypoints.map { it.id }.toSet())
    }

    @Test
    fun `an unflagged place that is still believed on the watch also forces a rebuild`() {
        // The set has to match exactly, in both directions — an edit that drops
        // a place must rebuild the file without it, not just leave it be.
        val waypoints = listOf(
            Waypoint(id = 1, loadToDevice = true, onWatch = true),
            Waypoint(id = 2, loadToDevice = false, onWatch = true),
        )
        val plan = waypointPushPlan(waypoints) as WaypointPushJob.Rebuild
        assertEquals(listOf(1), plan.waypoints.map { it.id })
    }

    // ── Course geometry out of the shared GeoJSON document ──────────────────

    private val geoJson = """
        {"type":"FeatureCollection","features":[
            {"type":"Feature","id":5,"properties":{},"geometry":{"type":"LineString",
                "coordinates":[[-150.0,40.0],[-150.0,40.01]]}},
            {"type":"Feature","id":9,"properties":{},"geometry":{"type":"LineString",
                "coordinates":[[-149.0,39.0],[-149.0,39.01],[-148.99,39.01]]}}
        ]}
    """.trimIndent()

    @Test
    fun `a course's own line is picked out by its top-level id`() {
        val coords = courseCoordinatesFromGeoJson(geoJson, 9)
        assertEquals(listOf(listOf(-149.0, 39.0), listOf(-149.0, 39.01), listOf(-148.99, 39.01)), coords)
    }

    @Test
    fun `an id with no matching feature is absent rather than an empty line`() {
        assertNull(courseCoordinatesFromGeoJson(geoJson, 404))
    }

    @Test
    fun `bytes that are not GeoJSON at all are absent rather than throwing`() {
        assertNull(courseCoordinatesFromGeoJson("not json", 5))
    }
}
