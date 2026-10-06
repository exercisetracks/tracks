// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.tracks.core.replica.TracksSchema
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A FIT file's "uploaded" flag means uploaded to the server the phone syncs
 * with now, not to whichever one it synced with when the flag was set.
 *
 * Found on a real phone: moved from one server to a second through "restore
 * this phone's data", it pushed every plan and edit and none of the files, so
 * the new server never had the activity history or the past health days.
 */
class UploadServerTest {

    private fun library(): LocalLibrary {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TracksSchema.create(driver)
        return LocalLibrary(driver)
    }

    private fun LocalLibrary.addFile(sha: String, uploaded: Boolean) =
        q.upsertFile(sha, "activity", null, if (uploaded) 1 else 0)

    @Test
    fun `files sent to one server are sent again to the next`() = runBlocking {
        val lib = library()
        lib.addFile("a", uploaded = true)
        lib.addFile("b", uploaded = true)
        lib.uploadsGoTo("server-1")
        assertEquals(emptyList(), lib.notUploaded())

        lib.uploadsGoTo("server-2")
        assertEquals(setOf("a", "b"), lib.notUploaded().toSet())
    }

    @Test
    fun `the same server keeps its flags`() = runBlocking {
        val lib = library()
        lib.addFile("a", uploaded = false)
        lib.uploadsGoTo("server-1")
        lib.markUploaded("a")
        lib.uploadsGoTo("server-1")
        assertEquals(emptyList(), lib.notUploaded())
    }

    /**
     * A fresh phone's first sync downloads its history, marked uploaded as it
     * lands; treating "nothing recorded" as a change would send it all back.
     */
    @Test
    fun `the first server recorded takes the flags as they are`() = runBlocking {
        val lib = library()
        lib.addFile("from-server", uploaded = true)
        lib.addFile("from-watch", uploaded = false)
        lib.uploadsGoTo("server-1")
        assertEquals(listOf("from-watch"), lib.notUploaded())
    }

    /** After an erase, the next server is a first server again. */
    @Test
    fun `clearing the library forgets the server`() = runBlocking {
        val lib = library()
        lib.uploadsGoTo("server-1")
        lib.clear()
        lib.addFile("a", uploaded = true)
        lib.uploadsGoTo("server-2")
        assertEquals(emptyList(), lib.notUploaded())
    }

    /**
     * A phone already moved to a second server before this was recorded holds
     * flags from the first. The migration cannot know which server they were
     * for, so it clears them, and the files go up once more.
     */
    @Test
    fun `upgrading from schema 7 queues every file again`() {
        val snapshot = File(javaClass.classLoader.getResource("schema/7.sql")!!.toURI()).readText()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        snapshot.split("\n-- statement --\n").map { it.trim() }.filter { it.isNotEmpty() }
            .forEach { driver.execute(null, it, 0) }
        driver.execute(null, "INSERT INTO local_file(sha256, kind, activity_uid, uploaded) VALUES ('a', 'activity', NULL, 1)", 0)

        TracksSchema.migrate(driver, 7, TracksSchema.VERSION)

        val uploaded = driver.executeQuery(null, "SELECT uploaded FROM local_file WHERE sha256 = 'a'", { c ->
            c.next(); QueryResult.Value(c.getLong(0))
        }, 0).value
        assertEquals(0L, uploaded)
    }
}
