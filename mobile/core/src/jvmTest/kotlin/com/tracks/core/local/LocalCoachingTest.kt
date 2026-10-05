// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.parse.Canonical
import com.tracks.core.replica.ReplicaStore
import com.tracks.core.replica.TracksSchema
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The coaching note and the readiness gauge read the same numbers.
 *
 * They once came from two places — the note from the server, the gauge from
 * the phone — so a phone that had not yet downloaded its history showed
 * "readiness 94" in the note over a gauge reading 50.
 */
class LocalCoachingTest {

    private fun metrics(): Pair<LocalMetrics, LocalLibrary> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TracksSchema.create(driver)
        val library = LocalLibrary(driver)
        val replica = ReplicaStore(driver, { 1_727_190_000_000L })
        return LocalMetrics(library, LocalSources(replica, library)) to library
    }

    @Test
    fun with_no_history_the_note_reads_neutral_like_the_gauge() = runBlocking {
        val (m, _) = metrics()
        val today = CivilDate(2026, 9, 25)
        val note = m.coaching(today, null)
        val gauge = m.readiness(today, 1, null).last()
        assertEquals(gauge.score, note.readiness.score)
        assertEquals(50.0, note.readiness.score)
        assertEquals(0.0, note.signal.tsb)
    }

    @Test
    fun after_an_import_the_note_and_the_gauge_still_agree() = runBlocking {
        val (m, lib) = metrics()
        val dir = File(Canonical.root, "spec/fixtures/fit")
        dir.listFiles()!!.filter { it.name.endsWith(".fit") }.sortedBy { it.name }.forEach { f ->
            LocalImporter(lib, { ImportThresholds(null, 165.0) }).import(f.name, f.readBytes())
        }
        val today = CivilDate(2026, 9, 25)
        val note = m.coaching(today, 165.0)
        val gauge = m.readiness(today, 1, 165.0).last()
        assertEquals(gauge.score, note.readiness.score)
    }
}
