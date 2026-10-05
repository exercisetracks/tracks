// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.tracks.core.replica.ReplicaStore
import com.tracks.core.replica.TracksSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The server's match side effects, replayed on the phone from matched
 * workouts and the activities' own files (see [LocalMatchEffects]).
 */
class LocalMatchEffectsTest {

    private class World(val sources: LocalSources, val library: LocalLibrary)

    private fun world(): World {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TracksSchema.create(driver)
        val library = LocalLibrary(driver)
        return World(LocalSources(ReplicaStore(driver, { 1_790_000_000_000L }), library), library)
    }

    private fun World.activity(uid: String, start: String, sport: String, maxHr: Long? = null,
                               best20: Double? = null, sets: List<Triple<String, Double, Int>> = emptyList()) {
        val summary = buildJsonObject {
            put("started_at", start); put("sport", sport)
            put("distance_meters", 10000.0); put("duration_seconds", 3000)
            maxHr?.let { put("max_heart_rate", it) }
        }
        val detail = buildJsonObject {
            best20?.let { put("power_curve", buildJsonObject { put("1200", it) }) }
            put("strength_sets", buildJsonArray {
                sets.forEach { (n, w, r) -> add(buildJsonObject { put("exercise_name", n); put("weight_kg", w); put("repetitions", r) }) }
            })
        }
        library.q.upsertActivity(uid, "sha-$uid", "serial", start, sport, null, null, 3000, 10000.0, null, maxHr,
            null, null, null, null, null, null, null, null, summary.toString(), detail.toString())
        library.q.insertAlias(uid)
    }

    private suspend fun World.matched(activityUid: String, type: String, complete: Boolean = true, pct: Double = 1.0) {
        sources.replica.create("planned_workout", mapOf<String, JsonElement>(
            "scheduled_date" to JsonPrimitive("2026-09-01"), "workout_type" to JsonPrimitive(type),
            "completed_activity_uid" to JsonPrimitive(activityUid), "is_complete" to JsonPrimitive(complete),
            "completion_pct" to JsonPrimitive(pct), "duration_minutes" to JsonPrimitive(50),
        ))
    }

    private suspend fun World.settings(vararg pairs: Pair<String, String>) =
        sources.replica.create("settings", pairs.associate { (k, v) -> k to JsonPrimitive(v) as JsonElement })

    /**
     * A completed FTP test sets the automatic FTP on the phone as on the
     * server — before, "auto" on a phone meant no threshold at all, so the
     * phone's load figures ignored the test the user just did.
     */
    @Test
    fun a_completed_ftp_test_sets_the_automatic_threshold() = runBlocking {
        val w = world()
        w.settings("ftp_mode" to "auto", "threshold_hr_mode" to "auto")
        w.activity("a1", "2026-09-01T07:00:00", "cycling", maxHr = 180, best20 = 263.0)
        w.matched("a1", "field_test:ftp20")
        val t = w.sources.importThresholds()
        assertEquals(250.0, t.ftp)   // round(263 × 0.95)
        assertTrue((t.thresholdHr ?: 0.0) > 0.0)
    }

    /** A manual FTP is the user's word; a field test never overrides it. */
    @Test
    fun a_manual_threshold_is_never_overridden_by_a_test() = runBlocking {
        val w = world()
        w.settings("ftp_mode" to "manual", "ftp_manual" to "300", "threshold_hr_mode" to "manual", "threshold_hr_manual" to "170")
        w.activity("a1", "2026-09-01T07:00:00", "cycling", maxHr = 180, best20 = 263.0)
        w.matched("a1", "field_test:ftp20")
        assertEquals(300.0, w.sources.importThresholds().ftp)
    }

    /** An unfinished test sets nothing, as the server only applies complete ones. */
    @Test
    fun an_incomplete_test_sets_nothing() = runBlocking {
        val w = world()
        w.settings("ftp_mode" to "auto")
        w.activity("a1", "2026-09-01T07:00:00", "cycling", best20 = 263.0)
        w.matched("a1", "field_test:ftp20", complete = false, pct = 0.4)
        assertNull(w.sources.importThresholds().ftp)
    }

    /** Matched runs advance the running fingerprint that seeds the next plan's base phase. */
    @Test
    fun matched_runs_advance_the_fitness_fingerprint() = runBlocking {
        val w = world()
        w.activity("a1", "2026-09-01T07:00:00", "running")
        w.activity("a2", "2026-09-03T07:00:00", "running")
        w.matched("a1", "easy"); w.matched("a2", "tempo")
        val fp = LocalMatchEffects(w.sources, w.library).fingerprint("running")
        assertEquals(2.15, fp.effectiveWeeks)   // 1.0, then 1.0 × 1.15 for a quality session
        assertEquals(0.0, LocalMatchEffects(w.sources, w.library).fingerprint("cycling").effectiveWeeks)
    }

    /** Sets the watch recorded for a matched strength workout reach the lift records. */
    @Test
    fun recorded_sets_of_a_matched_strength_workout_reach_the_lift_records() = runBlocking {
        val w = world()
        w.activity("s1", "2026-09-02T18:00:00", "training", sets = listOf(Triple("Goblet Squat", 24.0, 10)))
        w.matched("s1", "strength")
        val squat = LocalTraining(w.sources).standings().single { it.exerciseName == "Goblet Squat" }
        assertEquals(1, squat.sessionsCompleted)
        assertEquals(24.0, squat.lastWeightKg)
    }
}
