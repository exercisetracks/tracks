// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.replica

import com.tracks.core.spec.SpecFixtures
import com.tracks.core.spec.SpecFixtures.cases
import com.tracks.core.spec.SpecFixtures.str
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `spec/fixtures/sync_scenarios.json`, replayed.
 *
 * The server replays the same file in Python. Every case here passing on both
 * sides is the argument that a phone and a server will agree about every
 * edit — which is the one property of sync that cannot be patched after the
 * fact, because by then the replicas already disagree about the data.
 *
 * The merge cases run twice: over a map, and over the SQL table the phone
 * actually ships. The rule is the same code both times; what the second run
 * adds is the JSON round trip and the parent-uid index, which are exactly
 * where a table and a map could quietly differ.
 */
class SyncScenariosTest {

    private val corpus = SpecFixtures.load("sync_scenarios")

    @Test
    fun `the corpus is not empty`() {
        // Guards against a truncated fixture file making every loop below vacuous.
        assertTrue(corpus.cases("hlc").size >= 11)
        assertTrue(corpus.cases("merge").size >= 22)
        assertTrue(corpus.cases("uids").size >= 7)
        assertTrue(corpus.cases("derived").size >= 2)
    }

    @Test
    fun `every hlc case agrees with the spec`() {
        for (case in corpus.cases("hlc")) {
            val name = case.str("name")
            when (case.str("op")) {
                "format" -> {
                    val hlc = Hlc(
                        case.getValue("wall_ms").jsonPrimitive.long,
                        case.getValue("counter").jsonPrimitive.long.toInt(),
                        case.str("node"),
                    )
                    assertEquals(case.str("expect"), hlc.encoded, name)
                    assertEquals(hlc, Hlc.parse(hlc.encoded), name)
                }
                "tick" -> {
                    val clock = HlcClock(case.str("node"), lastOf(case))
                    assertEquals(case.str("expect"), clock.tick(case.getValue("now").jsonPrimitive.long).encoded, name)
                }
                "receive_pulled" -> {
                    val clock = HlcClock(case.str("node"), lastOf(case))
                    val remote = Hlc.parse(case.str("remote"))
                    assertEquals(
                        case.str("expect"),
                        clock.receivePulled(remote, case.getValue("now").jsonPrimitive.long).encoded,
                        name,
                    )
                }
                "receive" -> {
                    val clock = HlcClock(case.str("node"), lastOf(case))
                    val remote = Hlc.parse(case.str("remote"))
                    val now = case.getValue("now").jsonPrimitive.long
                    if (case.str("expect") == "refused") {
                        assertFailsWith<ClockSkewException>(name) { clock.receive(remote, now) }
                    } else {
                        assertEquals(case.str("expect"), clock.receive(remote, now).encoded, name)
                    }
                }
                else -> error("unknown hlc op in $name")
            }
        }
    }

    private fun lastOf(case: JsonObject): Hlc? =
        case["last"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content?.let(Hlc::parse)

    @Test
    fun `every uid key derives the spec's uuid`() {
        for (case in corpus.cases("uids")) {
            assertEquals(case.str("expect"), Uids.v5(case.str("key")), case.str("key"))
        }
    }

    @Test
    fun `every merge case holds over a map`() {
        for (case in corpus.cases("merge")) {
            val rows = MemoryRows(rowsOf(case.getValue("before").jsonObject))
            val outcome = Merge.apply(rows, Wire.decodeChange(case.getValue("change").jsonObject), allowReadonly = false)
            check(case, outcome.result, rows.all)
        }
    }

    @Test
    fun `every merge case holds over the phone's table`() = runTest {
        for (case in corpus.cases("merge")) {
            val (store, driver) = newReplica(now = { 0L })
            val result = store.withRows { rows ->
                rowsOf(case.getValue("before").jsonObject).forEach(rows::put)
                Merge.apply(rows, Wire.decodeChange(case.getValue("change").jsonObject), allowReadonly = false).result
            }
            check(case, result, store.allRows())
            driver.close()
        }
    }

    private fun check(case: JsonObject, result: ChangeResult, after: List<SyncedRow>) {
        val name = case.str("name")
        assertEquals(case.str("status"), result.status.wire, name)
        assertEquals(
            case.getValue("refused").jsonObject.mapValues { it.value.jsonPrimitive.content },
            result.refused.mapValues { it.value.wire },
            name,
        )
        val expected = rowsOf(case.getValue("after").jsonObject).sortedBy { it.entity + "/" + it.uid }
        assertEquals(expected, after.sortedBy { it.entity + "/" + it.uid }, name)
    }

    @Test
    fun `every derived rule agrees with the spec`() {
        for (case in corpus.cases("derived")) {
            val rows = rowsOf(case.getValue("rows").jsonObject)
            val name = case.str("name")
            when (case.str("rule")) {
                "active_goal" -> assertEquals(case.str("expect"), ReadRules.activeGoal(rows), name)
                "dead_workouts" -> assertEquals(
                    case.getValue("expect").jsonArray.map { it.jsonPrimitive.content },
                    ReadRules.deadWorkouts(rows),
                    name,
                )
                else -> error("unknown rule in $name")
            }
        }
    }

    companion object {
        /** `{"entity/uid": {fields, clock, deleted}}` → rows. */
        fun rowsOf(json: JsonObject): List<SyncedRow> = json.map { (key, value) ->
            val (entity, uid) = key.split('/', limit = 2)
            val row = value.jsonObject
            SyncedRow(
                entity = entity,
                uid = uid,
                fields = row.getValue("fields").jsonObject,
                clock = row.getValue("clock").jsonObject.mapValues { it.value.jsonPrimitive.content },
                deleted = row["deleted"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content,
            )
        }
    }
}
