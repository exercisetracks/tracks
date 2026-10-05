// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.tracks.core.plan.PlanAssembly
import com.tracks.core.replica.ReplicaStore
import com.tracks.core.replica.TracksSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * An activity imported on the phone ticks off the workout it satisfies, with
 * the server's rules, as a synced edit — so a plan followed offline is not
 * left unticked until the files reach a server.
 */
class LocalMatchingTest {

    private fun replica(): ReplicaStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TracksSchema.create(driver)
        return ReplicaStore(driver, { 1_727_190_000_000L })
    }

    private suspend fun ReplicaStore.workout(date: String, sport: String, type: String = "easy",
                                             minutes: Long = 60, plan: String? = null): String =
        create("planned_workout", mapOf(
            "scheduled_date" to JsonPrimitive(date), "sport" to JsonPrimitive(sport),
            "workout_type" to JsonPrimitive(type), "duration_minutes" to JsonPrimitive(minutes),
            "plan_uid" to (plan?.let(::JsonPrimitive) ?: JsonNull),
        )).first

    @Test
    fun a_run_ticks_off_the_days_run_as_a_synced_edit() = runBlocking {
        val r = replica()
        val run = r.workout("2026-09-25", "running")
        val got = LocalMatching(r).match("act-1", "2026-09-25T07:00:00",
            PlanAssembly.DoneActivity("running", null, 3600))
        assertEquals(run, got)
        val row = r.row("planned_workout", run)!!
        assertEquals("act-1", (row.fields["completed_activity_uid"] as JsonPrimitive).content)
        assertEquals(1.0, (row.fields["completion_pct"] as JsonPrimitive).doubleOrNull)
        assertEquals(true, (row.fields["is_complete"] as JsonPrimitive).booleanOrNull)
    }

    @Test
    fun an_evening_run_ticks_off_its_own_days_workout_not_tomorrows() = runBlocking {
        // 18:04 in California is 01:04 UTC the next day. Matched by the UTC
        // day, it ticked off tomorrow's easy run and left today's open.
        val r = replica()
        r.create("settings", mapOf("timezone" to JsonPrimitive("America/Los_Angeles")))
        val today = r.workout("2026-09-30", "running")
        val tomorrow = r.workout("2026-10-01", "running")
        val got = LocalMatching(r, com.tracks.core.time.ZoneOffsets::of).match(
            "act-1", "2026-10-01 01:04:12+00:00", PlanAssembly.DoneActivity("running", null, 1800),
        )
        assertEquals(today, got)
        assertNull(r.row("planned_workout", tomorrow)!!.fields["completed_activity_uid"]?.takeIf { it !is JsonNull })
    }

    @Test
    fun a_match_made_by_the_utc_day_moves_to_the_right_workout() = runBlocking {
        // What an older build wrote for the 2026-09-30 run: tomorrow's easy run
        // ticked off, today's left open.
        val r = replica()
        r.create("settings", mapOf("timezone" to JsonPrimitive("America/Los_Angeles")))
        val today = r.workout("2026-09-30", "running")
        val tomorrow = r.workout("2026-10-01", "running")
        val started = "2026-10-01 01:04:12+00:00"
        val done = PlanAssembly.DoneActivity("running", null, 1800)
        LocalMatching(r).match("act-1", started, done)          // UTC everywhere: the old behaviour
        assertEquals("act-1", (r.row("planned_workout", tomorrow)!!.fields["completed_activity_uid"] as JsonPrimitive).content)

        val m = LocalMatching(r, com.tracks.core.time.ZoneOffsets::of)
        assertEquals(1, m.repairUtcDayMatches(listOf(LocalMatching.Held("act-1", started, done))))

        assertEquals("act-1", (r.row("planned_workout", today)!!.fields["completed_activity_uid"] as JsonPrimitive).content)
        assertEquals(JsonNull, r.row("planned_workout", tomorrow)!!.fields["completed_activity_uid"])
        assertEquals(0, m.repairUtcDayMatches(listOf(LocalMatching.Held("act-1", started, done))), "repaired twice")
    }

    @Test
    fun a_morning_match_is_left_alone() = runBlocking {
        // Same day in UTC and locally: nothing about it is the old bug.
        val r = replica()
        r.create("settings", mapOf("timezone" to JsonPrimitive("America/Los_Angeles")))
        val today = r.workout("2026-09-30", "running")
        val done = PlanAssembly.DoneActivity("running", null, 1800)
        val m = LocalMatching(r, com.tracks.core.time.ZoneOffsets::of)
        m.match("act-1", "2026-09-30 16:00:00+00:00", done)
        assertEquals(0, m.repairUtcDayMatches(listOf(LocalMatching.Held("act-1", "2026-09-30 16:00:00+00:00", done))))
        assertEquals("act-1", (r.row("planned_workout", today)!!.fields["completed_activity_uid"] as JsonPrimitive).content)
    }

    @Test
    fun a_run_never_ticks_off_a_strength_session() = runBlocking {
        val r = replica()
        r.workout("2026-09-25", "strength_training", type = "strength")
        assertNull(LocalMatching(r).match("act-1", "2026-09-25T07:00:00",
            PlanAssembly.DoneActivity("running", null, 3600)))
    }

    @Test
    fun a_short_run_is_matched_but_not_counted_complete() = runBlocking {
        val r = replica()
        val run = r.workout("2026-09-25", "running", minutes = 60)
        LocalMatching(r).match("act-1", "2026-09-25T07:00:00", PlanAssembly.DoneActivity("running", null, 1800))
        val row = r.row("planned_workout", run)!!
        assertEquals(0.5, (row.fields["completion_pct"] as JsonPrimitive).doubleOrNull)
        assertEquals(false, (row.fields["is_complete"] as JsonPrimitive).booleanOrNull)
    }

    @Test
    fun an_already_matched_workout_is_not_taken_twice() = runBlocking {
        val r = replica()
        r.workout("2026-09-25", "running")
        val m = LocalMatching(r)
        m.match("act-1", "2026-09-25T07:00:00", PlanAssembly.DoneActivity("running", null, 3600))
        assertNull(m.match("act-2", "2026-09-25T18:00:00", PlanAssembly.DoneActivity("running", null, 3600)))
    }
}
