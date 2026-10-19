// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.tracks.core.api.TracksJson
import com.tracks.core.replica.TracksSchema
import com.tracks.core.parse.Canonical
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Nights read before the fenix 6's stage records were understood as stage
 * ends kept their wrong shape: the import keeps a night's first totals and its
 * longer timeline, so a fixed parser never reached them. The redraw replaces
 * both from the stored file.
 */
class SleepRedrawTest {

    @Test
    fun a_night_drawn_the_old_way_is_redrawn_from_its_file() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TracksSchema.create(driver)
        val lib = LocalLibrary(driver)
        val bytes = File(Canonical.root, "spec/fixtures/fit/sleep_end_marked.fit").readBytes()
        val importer = LocalImporter(lib, { ImportThresholds(null, null) })
        importer.import("s", bytes)
        val date = lib.q.selectDays().executeAsList().single().date
        // As the old reading stored it: a long awake span first.
        lib.q.upsertDay(
            date,
            """{"sleep_awake_hours":1.5,"sleep_hours":3.0}""",
            """{"sleep_stages":[{"start":"x","end":"y","level":"awake","seconds":5400}]}""",
        )

        assertEquals(1, importer.redrawSleep(listOf("s")) { bytes })

        val day = lib.q.selectDay(date).executeAsOne()
        val stages = TracksJson.parseToJsonElement(day.extra).jsonObject["sleep_stages"]!!.jsonArray
        assertEquals("light", stages.first().jsonObject["level"]!!.jsonPrimitive.content)
        val awake = TracksJson.parseToJsonElement(day.metrics).jsonObject["sleep_awake_hours"]!!.jsonPrimitive.content
        assertEquals(0.12, awake.toDouble(), 0.001)
    }
}
