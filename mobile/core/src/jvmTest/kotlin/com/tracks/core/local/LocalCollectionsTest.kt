// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.tracks.core.api.CourseCreate
import com.tracks.core.api.WaypointIn
import com.tracks.core.replica.ReplicaStore
import com.tracks.core.replica.TracksSchema
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Collections on the phone: track folders that hold places as well as tracks,
 * each a collection of its own on the watch.
 */
class LocalCollectionsTest {

    private fun items(): LocalMapItems {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TracksSchema.create(driver)
        return LocalMapItems(LocalSources(ReplicaStore(driver, { 1_727_190_000_000L }), LocalLibrary(driver)))
    }

    private val line = listOf(listOf(7.0, 46.0), listOf(7.01, 46.01))

    @Test
    fun a_track_and_a_place_are_filed_under_a_collection_and_back() = runBlocking {
        val m = items()
        val alps = m.createCollection("Alps")
        val track = m.createCourse(CourseCreate(name = "Ridge", coords = line))!!
        val place = m.createWaypoint(WaypointIn(name = "Hut", lat = 46.0, lng = 7.0))!!
        m.moveCourse(track.id, alps); m.moveWaypoint(place.id, alps)
        assertEquals(alps, m.courses().single().folderId)
        assertEquals(alps, m.waypoints().single().folderId)
        m.moveWaypoint(place.id, null)
        assertNull(m.waypoints().single().folderId, "back in the default")
    }

    @Test
    fun a_collections_watch_switch_reaches_every_member() = runBlocking {
        val m = items()
        val alps = m.createCollection("Alps")
        val track = m.createCourse(CourseCreate(name = "Ridge", coords = line))!!
        val place = m.createWaypoint(WaypointIn(name = "Hut", lat = 46.0, lng = 7.0))!!
        m.moveCourse(track.id, alps); m.moveWaypoint(place.id, alps)
        m.setCollectionOnWatch(alps, true)
        assertTrue(m.collections().single().loadToDevice)
        assertTrue(m.courses().single().loadToDevice)
        assertTrue(m.waypoints().single().loadToDevice)
    }

    @Test
    fun deleting_a_collection_keeps_what_was_in_it() = runBlocking {
        val m = items()
        val alps = m.createCollection("Alps")
        val place = m.createWaypoint(WaypointIn(name = "Hut", lat = 46.0, lng = 7.0))!!
        m.moveWaypoint(place.id, alps)
        m.deleteCollection(alps)
        assertTrue(m.collections().isEmpty())
        assertNull(m.waypoints().single().folderId, "kept, in the default")
    }
}
