// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.tracks.core.replica.ReplicaStore
import com.tracks.core.replica.TracksSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Auto thresholds on the phone: manual, else the latest field test, else the
 * whole history — the same rule and the same numbers as the server's
 * backend/tests/test_services/test_field_test_precedence.py, which builds the
 * same rides.
 */
class LocalAutoThresholdsTest {

    private class World(val sources: LocalSources, val library: LocalLibrary)

    private fun world(): World = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TracksSchema.create(driver)
        val library = LocalLibrary(driver)
        val sources = LocalSources(ReplicaStore(driver, { 1_790_000_000_000L }), library)
        sources.writeSetting("ftp_mode", "auto")
        sources.writeSetting("threshold_hr_mode", "auto")
        sources.writeSetting("max_hr_mode", "auto")
        World(sources, library)
    }

    /** A ride whose best 20 minutes average [watts]: 41 points a minute apart. */
    private fun World.ride(uid: String, watts: Int, sport: String = "cycling", maxHr: Long = 170,
                           startEpoch: Long = 1_790_000_000L, durationSeconds: Long = 2400,
                           best20: Double? = null) {
        val start = isoAt(startEpoch)
        val detail = buildJsonObject {
            put("data_points", buildJsonArray {
                for (i in 0..40) add(buildJsonObject {
                    put("recorded_at", isoAt(startEpoch + 60L * i)); put("power", watts); put("heart_rate", JsonNull)
                })
            })
            best20?.let { put("power_curve", buildJsonObject { put("1200", it) }) }
        }
        val summary = buildJsonObject {
            put("started_at", start); put("sport", sport); put("duration_seconds", durationSeconds)
            put("max_heart_rate", maxHr)
        }
        library.q.upsertActivity(uid, "sha-$uid", "serial", start, sport, null, null, durationSeconds, 20000.0,
            null, maxHr, null, null, null, null, null, null, watts.toLong(), null, summary.toString(), detail.toString())
        library.q.insertAlias(uid)
    }

    private suspend fun World.completedFtpTest(activityUid: String, date: String) {
        sources.replica.create("planned_workout", mapOf(
            "scheduled_date" to JsonPrimitive(date), "sport" to JsonPrimitive("cycling"),
            "workout_type" to JsonPrimitive("field_test:ftp20"), "is_complete" to JsonPrimitive(true),
            "completed_activity_uid" to JsonPrimitive(activityUid), "completion_pct" to JsonPrimitive(1.0),
        ))
    }

    @Test
    fun history_fills_what_no_field_test_set() = runBlocking {
        val w = world()
        w.ride("r1", 300)
        val t = w.sources.importThresholds()
        assertEquals(285.0, t.ftp)      // round(300 * 0.95), as on the server
        assertEquals(170.0, t.maxHr)
    }

    @Test
    fun a_field_test_beats_the_history_estimate() = runBlocking {
        val w = world()
        w.ride("r1", 300)
        w.ride("t1", 250, startEpoch = 1_790_100_000L, durationSeconds = 1500, maxHr = 180, best20 = 250.0)
        w.completedFtpTest("t1", "2026-09-26")
        val t = w.sources.importThresholds()
        assertEquals(238.0, t.ftp)          // round(250 * 0.95), not history's 285
        assertEquals(167.0, t.thresholdHr)  // round(180 * 0.93)
    }

    @Test
    fun a_deleted_activity_is_not_history() = runBlocking {
        // The server hard-deletes it; the phone keeps the parsed row, so the
        // search must skip it or the two sides disagree about one account.
        val w = world()
        w.ride("r1", 300)
        w.ride("gone", 400, startEpoch = 1_790_100_000L)
        w.sources.deleteUid("activity", "gone")
        assertEquals(285.0, w.sources.importThresholds().ftp)
    }

    @Test
    fun a_sport_correction_changes_what_qualifies() = runBlocking {
        // Recorded as a run, corrected to a ride: the server queries the
        // corrected sport, so FTP comes from it.
        val w = world()
        w.ride("r1", 300, sport = "running")
        assertNull(w.sources.importThresholds().ftp)
        w.sources.replica.edit("activity", "r1", mapOf("sport" to JsonPrimitive("cycling")))
        assertEquals(285.0, w.sources.importThresholds().ftp)
    }

    @Test
    fun timestamps_convert_exactly_as_python_does() {
        // datetime.fromisoformat(s).timestamp(), recorded from Python.
        mapOf(
            "2026-09-25T07:00:00+00:00" to 1790319600.0,
            "2026-09-25T07:00:00.123456+00:00" to 1790319600.123456,
            "1999-12-31T23:59:59.000001+00:00" to 946684799.000001,
            "2024-02-29T12:30:45.5+00:00" to 1709209845.5,
        ).forEach { (iso, expected) -> assertEquals(expected, LocalAutoThresholds.epochSeconds(iso), iso) }
        assertNull(LocalAutoThresholds.epochSeconds("2026-09-25 07:00:00+02:00"))
    }

    private fun isoAt(epoch: Long): String {
        val day = Math.floorDiv(epoch, 86_400L)
        val secs = Math.floorMod(epoch, 86_400L)
        val date = java.time.LocalDate.ofEpochDay(day)
        return "%sT%02d:%02d:%02d+00:00".format(date, secs / 3600, (secs % 3600) / 60, secs % 60)
    }
}
