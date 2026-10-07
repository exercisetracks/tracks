// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.replica.ReplicaStore
import com.tracks.core.replica.TracksSchema
import com.tracks.core.time.ZoneOffsets
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An activity belongs to its day in the account's zone, in every figure the
 * phone works out from its library.
 *
 * The figures themselves are held to the server's by `spec/fixtures/metrics.json`,
 * whose zoned histories put the day on [com.tracks.core.metrics.MetricActivity]
 * the way the fixture test does. What that corpus cannot see is whether the
 * library does the same: before, it took the first ten characters of the
 * stored UTC start, so an 18:04 run in California was the next day's load.
 */
class LocalDayTest {

    /** A phone with an account in [zone] (null: never set), as the app wires it. */
    private class Phone(zone: String?) {
        val library: LocalLibrary
        val metrics: LocalMetrics

        init {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            TracksSchema.create(driver)
            library = LocalLibrary(driver, ZoneOffsets::of)
            val replica = ReplicaStore(driver, { 1_790_000_000_000L })
            if (zone != null) runBlocking { replica.create("settings", mapOf("timezone" to JsonPrimitive(zone))) }
            metrics = LocalMetrics(library, LocalSources(replica, library))
        }

        /** A run as the phone stores it: the start in UTC. */
        fun run(uid: String, startedAt: String) {
            library.q.upsertActivity(uid, "sha-$uid", "serial", startedAt, "running", null, null, 3600, 9000.0,
                null, null, null, 0.0, null, null, null, null, null, null, "{}", "{}")
            library.q.insertAlias(uid)
        }
    }

    @Test
    fun an_evening_run_in_california_is_on_the_dashboard_on_its_own_day() = runBlocking {
        val p = Phone("America/Los_Angeles")
        val m = p.metrics
        p.run("evening", "2026-10-01 01:04:12+00:00")   // 18:04 PDT, 30 September
        assertEquals(listOf("2026-09-30"), m.calendar(null, null).map { it.date })
        // A window from the 30th holds it; one from the 1st does not.
        assertEquals(1, m.summary("2026-09-30")!!.activityCount)
        assertEquals(0, m.summary("2026-10-01")?.activityCount ?: 0)
    }

    @Test
    fun an_evening_run_in_california_is_that_days_training_load() = runBlocking {
        val p = Phone("America/Los_Angeles")
        val m = p.metrics
        p.run("evening", "2026-10-01 01:04:12+00:00")
        val load = m.trainingLoad(CivilDate(2026, 9, 30), thresholdHr = null)
        assertEquals("2026-09-30", load.single { it.tss > 0 }.date)
        // So the 30th's form already carries it, rather than tomorrow's.
        val (ctl, _) = m.ctlAtl(CivilDate(2026, 9, 30), thresholdHr = null)
        assertEquals(load.single { it.date == "2026-09-30" }.ctl, ctl)
    }

    @Test
    fun a_morning_run_ahead_of_utc_is_on_its_own_day_not_the_day_before() = runBlocking {
        // 07:30 in Sydney on 2 October is 21:30 UTC on the 1st.
        val p = Phone("Australia/Sydney")
        val m = p.metrics
        p.run("morning", "2026-10-01T21:30:00+00:00")
        assertEquals(listOf("2026-10-02"), m.calendar(null, null).map { it.date })
        assertEquals("2026-10-02", m.trainingLoad(CivilDate(2026, 10, 2), null).single { it.tss > 0 }.date)
    }

    @Test
    fun an_account_with_no_zone_reads_its_days_in_utc() = runBlocking {
        // As the server's activity_local_date does with a missing setting.
        val p = Phone(null)
        val m = p.metrics
        p.run("evening", "2026-10-01 01:04:12+00:00")
        assertEquals(listOf("2026-10-01"), m.calendar(null, null).map { it.date })
    }

    @Test
    fun a_lifts_history_dates_each_session_by_its_local_day() {
        val library = Phone(null).library
        val detail = """{"strength_sets":[{"set_number":1,"set_type":"active","exercise_name":"Squat","repetitions":5}]}"""
        library.q.upsertActivity("lift", "sha-lift", "serial", "2026-10-01 01:04:12+00:00", "training", null, null,
            3600, null, null, null, null, null, null, null, null, null, null, null, "{}", detail)
        library.q.insertAlias("lift")
        val la = library.strengthHistory("2026-09-30", "America/Los_Angeles")
        assertEquals(listOf("2026-09-30"), la.map { it.activityDate })
        // The window is a local day too: the 1st began after this session ended.
        assertEquals(emptyList(), library.strengthHistory("2026-10-01", "America/Los_Angeles"))
    }
}
