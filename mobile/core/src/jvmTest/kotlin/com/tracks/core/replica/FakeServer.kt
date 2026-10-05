// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.replica

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * A server that speaks the protocol with the real [Merge], for tests.
 *
 * Deliberately built from the same pieces as the phone rather than as a
 * stand-in with its own rules: the property under test is that every replica
 * applying every change with one function converges, and a fake with rules of
 * its own would test a different system. What it adds is only what a server
 * has and a phone does not — an arrival sequence for the pull cursor, and
 * readonly rows it may write.
 *
 * Every request and response goes through [Wire] and back as JSON text, so
 * the encoding is exercised on every call, not just in its own test.
 */
class FakeServer(
    var now: () -> Long = { 1_700_000_000_000L },
    node: String = "5e5e5e5e5e5e5e5e",
) : SyncTransport {

    /** The server's identity; changes when its database is [recreate]d. */
    var serverId: String = node
        private set

    /** The account epoch; rises when the user [deleteMyData]s. */
    var epoch: Long = 0
        private set

    var rows = MemoryRows()
        private set
    val clock = HlcClock(node)
    private var seq = 0L
    private val seqOf = HashMap<Pair<String, String>, Long>()
    val blobs = HashMap<String, ByteArray>()

    /** Called at the top of every call; throw from it to simulate the network dropping. */
    var beforeCall: (String) -> Unit = {}

    var pushes = 0
        private set

    override suspend fun push(request: PushRequest): PushResponse {
        beforeCall("push")
        pushes++
        val decoded = Wire.decodePush(roundTrip(Wire.encodePush(request)))
        if (decoded.epoch < epoch) {
            // The account was wiped since this phone last looked: refuse the
            // whole push rather than let it put the data back.
            val results = decoded.changes.map {
                ChangeResult(it.entity, it.uid, ChangeStatus.REJECTED, reason = RejectReason.WIPED)
            }
            return Wire.decodePushResponse(roundTrip(Wire.encodePushResponse(PushResponse(clock.last.encoded, results))))
        }
        Hlc.parseOrNull(decoded.clock)?.let { if (!clock.wouldRefuse(it, now())) clock.receive(it, now()) }
        val results = decoded.changes.map { applyPushed(it) }
        return Wire.decodePushResponse(roundTrip(Wire.encodePushResponse(PushResponse(clock.last.encoded, results))))
    }

    private fun applyPushed(change: Change): ChangeResult {
        val stamps = change.stamps.map(Hlc::parse)
        if (stamps.any { clock.wouldRefuse(it, now()) }) {
            return ChangeResult(change.entity, change.uid, ChangeStatus.REJECTED, reason = RejectReason.CLOCK_SKEW)
        }
        stamps.forEach { clock.receive(it, now()) }
        val outcome = Merge.apply(rows, change, allowReadonly = false)
        outcome.touched.forEach { seqOf[it.row.entity to it.row.uid] = ++seq }
        return outcome.result
    }

    /** A write the server makes itself — a web edit, or LLM output. */
    fun serverWrite(change: Change) {
        Merge.apply(rows, change, allowReadonly = true).touched
            .forEach { seqOf[it.row.entity to it.row.uid] = ++seq }
    }

    fun stamp(): String = clock.tick(now()).encoded

    override suspend fun pull(since: Long, limit: Int): PullPage {
        beforeCall("pull")
        val ordered = seqOf.entries.filter { it.value > since }.sortedBy { it.value }
        val page = ordered.take(limit)
        val changes = page.map { (key, _) -> fullRow(rows.get(key.first, key.second)!!) }
        val next = page.lastOrNull()?.value ?: since
        val out = PullPage(changes, next, hasMore = ordered.size > page.size, serverId = serverId, epoch = epoch)
        return Wire.decodePull(roundTrip(Wire.encodePull(out)))
    }

    override suspend fun blob(sha256: String): ByteArray {
        beforeCall("blob")
        return blobs[sha256] ?: error("no blob $sha256")
    }

    override suspend fun identify(): ServerIdentity = ServerIdentity(serverId, epoch)

    /** A database recreated from scratch: nothing kept, and a new identity. */
    fun recreate(newServerId: String = "6f6f6f6f6f6f6f6f") {
        clear()
        serverId = newServerId
        epoch = 0
    }

    /** The web's "Delete my data": everything gone on purpose, epoch bumped. */
    fun deleteMyData() {
        clear()
        blobs.clear()
        epoch++
    }

    private fun clear() {
        rows = MemoryRows()
        seqOf.clear()
        seq = 0
    }

    fun state(): List<SyncedRow> = rows.all.filter { it.fields.isNotEmpty() || it.deleted != null }
        .sortedWith(compareBy({ it.entity }, { it.uid }))

    private fun roundTrip(json: JsonObject): JsonObject =
        kotlinx.serialization.json.Json.parseToJsonElement(json.toString()).jsonObject

    companion object {
        fun fullRow(row: SyncedRow): Change = Change(
            entity = row.entity,
            uid = row.uid,
            fields = row.fields.mapValues { (name, value) -> Stamped(value, row.clock.getValue(name)) },
            deleted = row.deleted,
        )
    }
}

/** A fresh replica on its own in-memory SQLite database. */
fun newReplica(now: () -> Long, seed: Int = 0): Pair<ReplicaStore, JdbcSqliteDriver> {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    return ReplicaStore.create(driver, now, kotlin.random.Random(seed)) to driver
}

fun str(value: String) = JsonPrimitive(value)
