// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.tracks.core.replica.ReplicaStore
import com.tracks.core.replica.TracksSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Where the watch forecast is for, now that the phone asks for it itself:
 * the start of the newest activity with GPS, read from this phone's own files
 * the way the server read its database.
 */
class RecentStartPointTest {

    private class World(val sources: LocalSources, val library: LocalLibrary)

    private fun world(): World {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TracksSchema.create(driver)
        val library = LocalLibrary(driver)
        return World(LocalSources(ReplicaStore(driver, { 1_790_000_000_000L }), library), library)
    }

    /** An activity whose points are [points] — (lat, lng) or null for a point with no fix. */
    private fun World.activity(uid: String, startedAt: String, points: List<Pair<Double, Double>?>) {
        val detail = buildJsonObject {
            put("data_points", buildJsonArray {
                for (p in points) add(buildJsonObject {
                    put("recorded_at", startedAt)
                    if (p == null) { put("lat", JsonNull); put("lng", JsonNull) }
                    else { put("lat", p.first); put("lng", p.second) }
                })
            })
        }
        val summary = buildJsonObject { put("started_at", startedAt); put("sport", "running") }
        library.q.upsertActivity(uid, "sha-$uid", "serial", startedAt, "running", null, null, 1800, 5000.0,
            null, null, null, null, null, null, null, null, null, null, summary.toString(), detail.toString())
        library.q.insertAlias(uid)
    }

    @Test
    fun `the newest activity's first fix wins, not a later point in it`() = runBlocking {
        val w = world()
        w.activity("old", "2026-10-01T08:00:00", listOf(10.0 to 20.0))
        // A watch takes a few seconds to get a fix; the leading empty points
        // are skipped rather than ending the search.
        w.activity("new", "2026-10-05T08:00:00", listOf(null, 46.87 to -113.99, 46.9 to -114.0))

        assertEquals(46.87 to -113.99, w.sources.recentStartPoint())
    }

    @Test
    fun `an indoor session is walked past rather than leaving no forecast`() = runBlocking {
        val w = world()
        w.activity("outside", "2026-10-01T08:00:00", listOf(46.87 to -113.99))
        w.activity("treadmill", "2026-10-05T08:00:00", listOf(null, null))

        assertEquals(46.87 to -113.99, w.sources.recentStartPoint())
    }

    @Test
    fun `no activity with GPS means nowhere to forecast for`() = runBlocking {
        val w = world()
        w.activity("treadmill", "2026-10-05T08:00:00", listOf(null))

        assertNull(w.sources.recentStartPoint())
    }
}
