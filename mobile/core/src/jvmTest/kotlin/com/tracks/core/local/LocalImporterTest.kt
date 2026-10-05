// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.tracks.core.api.TracksJson
import com.tracks.core.parse.Canonical
import com.tracks.core.replica.TracksSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The phone's importer against the server's.
 *
 * `spec/fixtures/local_import.json` is the server's own importer rules — which
 * parser claims a file, the activity uid, the stored TSS under three threshold
 * settings — run over the synthetic corpus by `spec/make_local_import_fixtures.py`.
 * If these disagree, a phone and a server holding the same file would show
 * different training load, or would not recognise it as the same activity.
 */
class LocalImporterTest {

    private val fixture = TracksJson.parseToJsonElement(
        File(Canonical.root, "spec/fixtures/local_import.json").readText(),
    ).jsonObject

    private fun library(): LocalLibrary {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TracksSchema.create(driver)
        return LocalLibrary(driver)
    }

    private fun bytes(name: String) = File(Canonical.root, "spec/fixtures/fit/$name").readBytes()

    @Test
    fun every_sample_imports_to_the_kind_uid_and_tss_the_server_gives_it() = runBlocking {
        val settings = fixture["settings"]!!.jsonArray.map { it.jsonObject }
        for (case in fixture["cases"]!!.jsonArray.map { it.jsonObject }) {
            val file = case["file"]!!.jsonPrimitive.content
            val sha = case["sha256"]!!.jsonPrimitive.content
            for (s in settings) {
                val lib = library()
                val t = ImportThresholds(s["ftp"]?.jsonPrimitive?.doubleOrNull, s["threshold_hr"]?.jsonPrimitive?.doubleOrNull)
                val outcome = LocalImporter(lib, { t }).import(sha, bytes(file))
                val type = case["type"]?.jsonPrimitive?.content
                val expected = when {
                    case["error"] != null -> LocalImporter.Outcome.Failed
                    type == null -> LocalImporter.Outcome.Unrecognised
                    else -> LocalImporter.Outcome.Imported
                }
                assertEquals(expected, outcome, "$file under ${s["name"]}")
                if (type == "activity") {
                    val uid = case["uid"]!!.jsonPrimitive.content
                    val row = assertNotNull(lib.activity(uid), "$file: no activity under the server's uid")
                    val want = case["effective_tss"]!!.jsonObject[s["name"]!!.jsonPrimitive.content]
                    assertEquals((want as? JsonPrimitive)?.doubleOrNull, row.effective_tss, "$file TSS under ${s["name"]}")
                }
            }
        }
    }

    @Test
    fun importing_the_same_file_twice_is_a_duplicate_not_a_second_activity() = runBlocking {
        val lib = library()
        val importer = LocalImporter(lib)
        val b = bytes("activity_ride.fit")
        assertEquals(LocalImporter.Outcome.Imported, importer.import("aa", b))
        assertEquals(LocalImporter.Outcome.Duplicate, importer.import("aa", b))
        assertEquals(1, lib.activities().size)
    }

    @Test
    fun the_lowest_hash_copy_of_an_activity_is_the_one_kept() = runBlocking {
        // Two copies of one activity in different bytes: whichever order they
        // arrive in, every replica must end up showing the same one.
        for (order in listOf(listOf("bb", "aa"), listOf("aa", "bb"))) {
            val lib = library()
            val importer = LocalImporter(lib)
            order.forEach { importer.import(it, bytes("activity_ride.fit")) }
            assertEquals(listOf("aa"), lib.activities().map { lib.activity(lib.uidOf(it.id)!!)!!.sha256 })
        }
    }

    @Test
    fun a_deleted_activity_is_not_brought_back_by_another_copy_of_its_file() = runBlocking {
        val lib = library()
        val outcome = LocalImporter(lib, isDeleted = { true }).import("aa", bytes("activity_ride.fit"))
        assertEquals(LocalImporter.Outcome.Deleted, outcome)
        assertEquals(0, lib.activities().size)
    }

    @Test
    fun an_alias_is_the_same_integer_every_time_it_is_asked_for() {
        val lib = library()
        val a = lib.alias("u1")
        val b = lib.alias("u2")
        assertEquals(a, lib.alias("u1"))
        assertEquals("u2", lib.uidOf(b))
    }

    @Test
    fun a_day_keeps_the_first_reading_except_where_the_server_takes_max_min_or_latest() {
        val first = JsonObject(mapOf(
            "hrv" to JsonPrimitive(40), "steps" to JsonPrimitive(3000),
            "body_battery_low" to JsonPrimitive(30), "resting_hr" to JsonPrimitive(55),
        ))
        val (m, _) = LocalImporter.mergeDay(
            first, JsonObject(emptyMap()),
            mapOf(
                "hrv" to JsonPrimitive(99), "steps" to JsonPrimitive(8000),
                "body_battery_low" to JsonPrimitive(20), "resting_hr" to JsonPrimitive(51),
                "spo2" to JsonNull,
            ),
            JsonArray(emptyList()), JsonArray(emptyList()),
        )
        assertEquals(40, m["hrv"]!!.jsonPrimitive.content.toInt(), "first wins")
        assertEquals(8000, m["steps"]!!.jsonPrimitive.content.toInt(), "max")
        assertEquals(20, m["body_battery_low"]!!.jsonPrimitive.content.toInt(), "min")
        assertEquals(51, m["resting_hr"]!!.jsonPrimitive.content.toInt(), "latest")
        assertNull(m["spo2"], "a null reading writes nothing")
    }

    @Test
    fun a_days_stress_merges_by_minute_with_the_incoming_reading_winning_its_minute() {
        fun series(vararg p: Pair<Int, Int>) = JsonArray(p.map { JsonArray(listOf(JsonPrimitive(it.first), JsonPrimitive(it.second))) })
        val merged = LocalImporter.mergeStress(series(10 to 1, 20 to 2), series(20 to 9, 5 to 3))
        assertEquals(series(5 to 3, 10 to 1, 20 to 9), merged)
    }

    @Test
    fun the_longer_sleep_timeline_wins() {
        fun stages(n: Int) = JsonArray(List(n) { JsonPrimitive(it) })
        val existing = JsonObject(mapOf("sleep_stages" to stages(5)))
        val metrics = JsonObject(emptyMap())
        val (_, shorter) = LocalImporter.mergeDay(metrics, existing, emptyMap(), stages(3), JsonArray(emptyList()))
        assertEquals(5, shorter["sleep_stages"]!!.jsonArray.size)
        val (_, longer) = LocalImporter.mergeDay(metrics, existing, emptyMap(), stages(8), JsonArray(emptyList()))
        assertEquals(8, longer["sleep_stages"]!!.jsonArray.size)
    }

    @Test
    fun every_health_sample_lands_on_a_day() = runBlocking {
        val lib = library()
        val importer = LocalImporter(lib)
        for (f in listOf("monitoring.fit", "sleep.fit", "hrv.fit")) importer.import(f, bytes(f))
        assert(lib.days().isNotEmpty()) { "health samples produced no days" }
    }

    /**
     * The MTB and indoor-cycling multipliers apply when load is read, on both
     * sides, and must agree on every sport and discipline. (The import stores
     * the unscaled value; the per-file cases above pin that.)
     */
    @Test
    fun load_is_scaled_by_sport_exactly_as_the_server_scales_it() {
        val grid = fixture.jsonObject["tss_scaling"]!!.jsonArray
        assert(grid.isNotEmpty())
        for (c in grid) {
            val o = c.jsonObject
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            val got = com.tracks.core.metrics.TrainingLoad.scaleTss(str("sport"), o["tss"]?.jsonPrimitive?.doubleOrNull, str("discipline"))
            assertEquals(o["want"]?.jsonPrimitive?.doubleOrNull, got, "$o")
        }
    }

    @Test
    fun an_activity_parsed_before_feel_and_effort_existed_gains_them_from_its_file() = runBlocking {
        // The run that prompted this was imported an hour before the parser
        // learned these fields, and an import never happens twice.
        val lib = library()
        val run = bytes("activity_run.fit")
        LocalImporter(lib).import("sha-run", run)
        val uid = lib.activityRows().single().uid
        // As an older build stored it: everything except the new keys.
        val old = JsonObject(lib.summaryJson(uid)!! - LocalImporter.BACKFILLED_SUMMARY_KEYS.toSet())
        val computed = old["effective_tss"]
        lib.locked { lib.q.updateActivitySummary(old.toString(), uid) }

        val updated = LocalImporter(lib).backfillSummaries { if (it == "sha-run") run else null }

        assertEquals(1, updated)
        val now = lib.summaryJson(uid)!!
        assertEquals(75, now["workout_feel"]!!.jsonPrimitive.content.toDouble().toInt())
        assertEquals(60, now["workout_rpe"]!!.jsonPrimitive.content.toDouble().toInt())
        assertEquals(computed, now["effective_tss"], "a value already stored was changed")
        assertEquals(0, LocalImporter(lib).backfillSummaries { run }, "a current summary was redone")
    }

    @Test
    fun an_activity_whose_file_is_gone_is_marked_done_rather_than_retried() = runBlocking {
        val lib = library()
        LocalImporter(lib).import("sha-run", bytes("activity_run.fit"))
        val uid = lib.activityRows().single().uid
        val old = JsonObject(lib.summaryJson(uid)!! - LocalImporter.BACKFILLED_SUMMARY_KEYS.toSet())
        lib.locked { lib.q.updateActivitySummary(old.toString(), uid) }

        assertEquals(1, LocalImporter(lib).backfillSummaries { null })
        assertEquals(JsonNull, lib.summaryJson(uid)!!["workout_feel"])
        assertEquals(0, LocalImporter(lib).backfillSummaries { null })
    }
}

