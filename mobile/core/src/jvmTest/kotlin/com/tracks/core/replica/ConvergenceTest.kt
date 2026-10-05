// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.replica

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The property the whole design exists for: any number of replicas, editing
 * offline in any interleaving, end up identical.
 *
 * Each run gives several phones their own real SQLite store and their own
 * wrong clock (up to hours apart — within the skew limit, as real phones are),
 * lets them create, edit, delete and retract across entities with parents,
 * children and logs, and syncs them against [FakeServer] in a random order —
 * some pushes landing before others' pulls, some phones staying dark for
 * most of the run. Then everyone syncs until quiet, and every replica's rows
 * must equal the server's, field for field and stamp for stamp.
 *
 * Seeded, so a failure names the seed that reproduces it.
 */
class ConvergenceTest {

    private class Phone(val store: ReplicaStore, var skewMs: Long)

    @Test
    fun `replicas editing offline in any order converge`() = runTest {
        repeat(40) { seed -> converge(seed) }
    }

    private suspend fun converge(seed: Int) {
        val random = Random(seed)
        var wall = 1_700_000_000_000L
        val server = FakeServer(now = { wall })
        val phones = List(2 + random.nextInt(3)) { i ->
            val skew = random.nextLong(-3 * 3_600_000L, 3 * 3_600_000L)
            lateinit var phone: Phone
            val store = newReplica(now = { wall + phone.skewMs }, seed = seed * 31 + i).first
            phone = Phone(store, skew)
            phone
        }

        repeat(200) { step ->
            wall += random.nextLong(0, 5_000)
            val phone = phones.random(random)
            when (random.nextInt(10)) {
                in 0..6 -> randomEdit(phone.store, random)
                7, 8 -> ReplicaSyncEngine(phone.store, server).sync()
                // The web edits something too: the server stamps it itself.
                else -> webEdit(server, random)
            }
            // Midway, some runs lose the server (recreated: phones must give
            // their data back) and some wipe the account on purpose (phones
            // must follow). Either way, everyone must still end identical.
            if (step == 100 && seed % 3 == 0) server.recreate()
            if (step == 100 && seed % 3 == 1) server.deleteMyData()
            // Clocks drift while all this happens.
            if (step % 50 == 0) phones.forEach { it.skewMs += random.nextLong(-60_000, 60_000) }
        }

        // Everyone syncs until nothing moves. Twice around is enough in
        // principle; three guards against a bug that needs a third lap.
        repeat(3) { phones.forEach { ReplicaSyncEngine(it.store, server).sync() } }

        val expected = server.state().map(::canonical)
        for ((i, phone) in phones.withIndex()) {
            assertEquals(0, phone.store.pendingCount(), "seed $seed phone $i still has pending edits")
            assertEquals(expected, phone.store.allRows().map(::canonical), "seed $seed phone $i diverged")
        }
        assertTrue(expected.isNotEmpty(), "seed $seed produced nothing to compare")
    }

    /** Compare values by JSON text, so `2` and `2.0` would count as different — as they must. */
    private fun canonical(row: SyncedRow) = listOf(
        row.entity, row.uid,
        row.fields.toSortedMap().mapValues { it.value.toString() },
        row.clock.toSortedMap(), row.deleted,
    )

    private val names = listOf("Camp", "Ridge", "Lake", "Pass", "Hut")

    private suspend fun randomEdit(store: ReplicaStore, random: Random) {
        val existing = store.allRows()
        fun pick(entity: String) = existing.filter { it.entity == entity && !it.isTombstone }.randomOrNull(random)
        when (random.nextInt(9)) {
            0 -> store.create("waypoint", mapOf("name" to str(names.random(random)), "color" to value(random)))
            1 -> pick("waypoint")?.let { store.edit("waypoint", it.uid, mapOf(randomWaypointField(random) to value(random))) }
            2 -> pick("waypoint")?.let { store.delete("waypoint", it.uid) }
            3 -> store.create("flow", mapOf("name" to str(names.random(random))))
            4 -> {
                // A child — sometimes of a flow this phone has not seen yet
                // or that someone else has deleted; both must converge.
                val parent = existing.filter { it.entity == "flow" }.randomOrNull(random) ?: return
                store.create(
                    "flow_stretch",
                    mapOf("flow_uid" to str(parent.uid), "order" to str(OrderKeys.between(null, null)),
                        "duration_seconds" to JsonPrimitive(random.nextInt(10, 120))),
                )
            }
            5 -> pick("flow")?.let { store.delete("flow", it.uid) }
            6 -> store.create("medication_log", mapOf("status" to str("taken"), "notes" to value(random)))
            7 -> pick("medication_log")?.let { store.delete("medication_log", it.uid) }
            // Natural key: two phones "creating" the same day's weight edit one row.
            else -> {
                val date = "2026-09-2${random.nextInt(3)}"
                store.create(
                    "daily_entry",
                    mapOf("date" to str(date), "weight_kg" to JsonPrimitive(70 + random.nextInt(5))),
                    keyValues = mapOf("date" to date),
                )
            }
        }
    }

    private suspend fun webEdit(server: FakeServer, random: Random) {
        val target = server.rows.all.filter { it.entity == "waypoint" && !it.isTombstone }.randomOrNull(random) ?: return
        server.serverWrite(Change("waypoint", target.uid, mapOf("notes" to Stamped(value(random), server.stamp()))))
    }

    private fun randomWaypointField(random: Random) = listOf("name", "color", "notes", "icon").random(random)

    private fun value(random: Random): JsonElement = when (random.nextInt(4)) {
        0 -> JsonNull
        1 -> JsonPrimitive(random.nextInt(100))
        else -> str(names.random(random))
    }
}
