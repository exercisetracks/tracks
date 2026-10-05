// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.tracks.core.metrics.TrainingLoad
import com.tracks.core.replica.TracksSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Load for an athlete with only a phone, as the phone's own library reads it.
 *
 * The estimate itself is held to the server's by `spec/fixtures/metrics.json`;
 * what that corpus cannot see is whether the library hands the estimate what
 * it needs. A climb dropped between the table and [com.tracks.core.metrics.MetricActivity]
 * would score every hilly run as flat on the phone while the server counted
 * it — the same file, two fitness lines.
 */
class LocalPhoneOnlyLoadTest {

    private fun library(): LocalLibrary {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TracksSchema.create(driver)
        return LocalLibrary(driver)
    }

    /** A run as the phone records it: distance, time and climb — no heart rate, power or device TSS. */
    private fun LocalLibrary.phoneRun(uid: String, start: String, ascent: Double?) {
        q.upsertActivity(uid, "sha-$uid", "serial", start, "running", null, null, 3600, 9000.0,
            null, null, null, ascent, null, null, null, null, null, null, "{}", "{}")
        q.insertAlias(uid)
    }

    @Test
    fun a_phone_recorded_run_carries_its_climb_into_the_load() {
        val lib = library()
        lib.phoneRun("hilly", "2026-06-01T08:00:00+00:00", 700.0)
        lib.phoneRun("flat", "2026-06-03T08:00:00+00:00", 0.0)
        val rows = lib.metricActivities(null)
        val hilly = rows.single { it.totalAscent == 700.0 }
        val flat = rows.single { it.totalAscent == 0.0 }
        assertEquals(
            TrainingLoad.estimatedTss("running", 3600.0, 9000.0, 700.0),
            TrainingLoad.estimateTss(hilly),
        )
        assertTrue(TrainingLoad.estimateTss(hilly) > TrainingLoad.estimateTss(flat) * 1.15)
    }

    @Test
    fun a_phone_only_history_is_not_zero_load() {
        val lib = library()
        lib.phoneRun("a", "2026-06-01T08:00:00+00:00", null)
        val load = TrainingLoad.tssByDate(lib.metricActivities(null), thresholdHr = null)
        assertTrue(load.values.single() > 50.0, "$load")
    }
}
