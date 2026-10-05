// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.replica

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The store's bookkeeping and the engine's walk, against [FakeServer] — which
 * runs the real merge behind the real wire encoding.
 */
class ReplicaEngineTest {

    private var clockMs = 1_700_000_000_000L
    private val now = { clockMs }

    private fun replica(seed: Int = 1) = newReplica(now, seed).first

    // ── The store ───────────────────────────────────────────────────────────

    @Test
    fun `a local edit is stamped and waits to be pushed`() = runTest {
        val store = replica()
        val (uid, result) = store.create("waypoint", mapOf("name" to str("Camp 2")))
        assertEquals(ChangeStatus.APPLIED, result.status)
        val row = assertNotNull(store.row("waypoint", uid))
        assertEquals(str("Camp 2"), row.fields["name"])
        assertTrue(row.clock.getValue("name").endsWith(store.node))
        assertEquals(1, store.pendingCount())
    }

    @Test
    fun `the node and clock survive a restart`() = runTest {
        val dir = Files.createTempDirectory("replica").toFile()
        val url = "jdbc:sqlite:${File(dir, "r.db").absolutePath}"
        val first = ReplicaStore.create(JdbcSqliteDriver(url), now)
        first.create("waypoint", mapOf("name" to str("A")))
        val stamp = first.currentClock()

        // The wall clock went backwards across the restart; stamps must not.
        clockMs -= 60_000
        val second = ReplicaStore(JdbcSqliteDriver(url), now)
        assertEquals(first.node, second.node)
        val (uid, _) = second.create("waypoint", mapOf("name" to str("B")))
        assertTrue(second.row("waypoint", uid)!!.clock.getValue("name") > stamp)
    }

    @Test
    fun `a pushed edit is no longer pending`() = runTest {
        val store = replica()
        val server = FakeServer(now)
        store.create("waypoint", mapOf("name" to str("A")))
        ReplicaSyncEngine(store, server).sync()
        assertEquals(0, store.pendingCount())
        assertEquals(1, server.state().size)
    }

    /**
     * The race dirty tracking exists for: an edit made while a push carrying
     * the previous value is in flight. Acknowledging that push must not also
     * acknowledge the newer edit, or it would never be sent.
     */
    @Test
    fun `a field edited again during a push stays pending`() = runTest {
        val store = replica()
        val server = FakeServer(now)
        val (uid, _) = store.create("waypoint", mapOf("name" to str("first")))
        server.beforeCall = { call ->
            if (call == "push") {
                clockMs += 1
                kotlinx.coroutines.runBlocking { store.edit("waypoint", uid, mapOf("name" to str("second"))) }
                server.beforeCall = {}
            }
        }
        ReplicaSyncEngine(store, server).sync()
        // The first push acknowledged "first"; "second" went out in the next batch.
        assertEquals(0, store.pendingCount())
        assertEquals(str("second"), server.rows.get("waypoint", uid)!!.fields["name"])
    }

    /** A pull must never undo an edit the server has not heard about yet. */
    @Test
    fun `a pull does not undo an unpushed edit`() = runTest {
        val store = replica()
        val server = FakeServer(now)
        val (uid, _) = store.create("waypoint", mapOf("name" to str("mine")))
        ReplicaSyncEngine(store, server).sync()

        // The web edits it, earlier than the phone's next edit.
        server.serverWrite(Change("waypoint", uid, mapOf("name" to Stamped(str("web"), server.stamp()))))
        clockMs += 1_000
        store.edit("waypoint", uid, mapOf("name" to str("phone, later")))

        store.applyPulled(server.pull(store.cursor(), 100).changes, 0)
        assertEquals(str("phone, later"), store.row("waypoint", uid)!!.fields["name"])
        assertEquals(1, store.pendingCount())
    }

    @Test
    fun `a newer remote write wins and clears the local edit`() = runTest {
        val store = replica()
        val server = FakeServer(now)
        val (uid, _) = store.create("waypoint", mapOf("name" to str("mine")))
        ReplicaSyncEngine(store, server).sync()

        store.edit("waypoint", uid, mapOf("name" to str("phone")))
        clockMs += 1_000
        server.serverWrite(Change("waypoint", uid, mapOf("name" to Stamped(str("web, later"), server.stamp()))))

        ReplicaSyncEngine(store, server).sync()
        assertEquals(str("web, later"), store.row("waypoint", uid)!!.fields["name"])
        assertEquals(str("web, later"), server.rows.get("waypoint", uid)!!.fields["name"])
        assertEquals(0, store.pendingCount())
    }

    @Test
    fun `clearing a field syncs as a null, not as nothing`() = runTest {
        val store = replica()
        val server = FakeServer(now)
        val (uid, _) = store.create("injury", mapOf("notes" to str("sore")))
        clockMs += 1
        store.edit("injury", uid, mapOf("notes" to JsonNull))
        ReplicaSyncEngine(store, server).sync()
        assertEquals(JsonNull, server.rows.get("injury", uid)!!.fields["notes"])
    }

    @Test
    fun `a delete reaches the server and takes the children with it`() = runTest {
        val store = replica()
        val server = FakeServer(now)
        val (flow, _) = store.create("flow", mapOf("name" to str("Hips")))
        val (stretch, _) = store.create(
            "flow_stretch",
            mapOf("flow_uid" to str(flow), "order" to str(OrderKeys.between(null, null))),
        )
        ReplicaSyncEngine(store, server).sync()
        clockMs += 1
        store.delete("flow", flow)
        assertNotNull(store.row("flow_stretch", stretch)!!.deleted)

        ReplicaSyncEngine(store, server).sync()
        assertNotNull(server.rows.get("flow", flow)!!.deleted)
        assertNotNull(server.rows.get("flow_stretch", stretch)!!.deleted)
        assertEquals(0, store.pendingCount())
    }

    @Test
    fun `a local edit to a readonly entity is refused`() = runTest {
        val store = replica()
        val result = store.edit("coaching_note", "01922a8c-0000-7000-8000-000000000001", mapOf("ai_response" to str("x")))
        assertEquals(ChangeStatus.REJECTED, result.status)
        assertEquals(0, store.pendingCount())
    }

    @Test
    fun `readonly rows from the server are kept`() = runTest {
        val store = replica()
        val server = FakeServer(now)
        val uid = "01922a8c-0000-7000-8000-000000000009"
        server.serverWrite(Change("coaching_note", uid, mapOf("ai_response" to Stamped(str("Rest today."), server.stamp()))))
        ReplicaSyncEngine(store, server).sync()
        assertEquals(str("Rest today."), store.row("coaching_note", uid)!!.fields["ai_response"])
    }

    // ── The walk ────────────────────────────────────────────────────────────

    @Test
    fun `a pull walks every page and stops`() = runTest {
        val server = FakeServer(now)
        repeat(25) { i ->
            server.serverWrite(
                Change("waypoint", Uids.v7(clockMs + i), mapOf("name" to Stamped(str("w$i"), server.stamp()))),
            )
        }
        val store = replica()
        val report = ReplicaSyncEngine(store, server, config = ReplicaSyncEngine.Config(pullLimit = 10)).sync()
        assertEquals(25, report.pulled)
        // Three full pages, then one empty page after the push (a first sync
        // pulls before it pushes, to learn the account's epoch).
        assertEquals(4, report.pages)
        assertFalse(report.truncated)
        assertEquals(25, store.rows("waypoint").size)
    }

    @Test
    fun `the walk is bounded`() = runTest {
        val server = FakeServer(now)
        repeat(25) { i ->
            server.serverWrite(Change("waypoint", Uids.v7(clockMs + i), mapOf("name" to Stamped(str("w$i"), server.stamp()))))
        }
        val store = replica()
        val bounded = ReplicaSyncEngine(store, server, config = ReplicaSyncEngine.Config(pullLimit = 10, maxPages = 1))
        assertTrue(bounded.sync().truncated)
        assertEquals(10, store.rows("waypoint").size)
        // The next run resumes; nothing was lost to the bound.
        bounded.sync(); bounded.sync()
        assertEquals(25, store.rows("waypoint").size)
    }

    /**
     * Connectivity dies between two pages. The first page and its cursor
     * landed together; the second is fetched again next time, not skipped.
     */
    @Test
    fun `a sync interrupted mid-walk resumes where it stopped`() = runTest {
        val server = FakeServer(now)
        repeat(20) { i ->
            server.serverWrite(Change("waypoint", Uids.v7(clockMs + i), mapOf("name" to Stamped(str("w$i"), server.stamp()))))
        }
        val store = replica()
        var pulls = 0
        server.beforeCall = { call -> if (call == "pull" && ++pulls == 2) throw IOException("signal lost") }
        val engine = ReplicaSyncEngine(store, server, config = ReplicaSyncEngine.Config(pullLimit = 10))
        assertFailsWith<IOException> { engine.sync() }
        assertEquals(10, store.rows("waypoint").size)

        server.beforeCall = {}
        engine.sync()
        assertEquals(20, store.rows("waypoint").size)
    }

    @Test
    fun `a push that fails leaves the edits queued`() = runTest {
        val store = replica()
        val server = FakeServer(now)
        store.create("waypoint", mapOf("name" to str("A")))
        server.beforeCall = { if (it == "push") throw IOException("no signal") }
        assertFailsWith<IOException> { ReplicaSyncEngine(store, server).sync() }
        assertEquals(1, store.pendingCount())
        server.beforeCall = {}
        ReplicaSyncEngine(store, server).sync()
        assertEquals(0, store.pendingCount())
    }

    /**
     * The server was recreated — the no-migrations policy does exactly this.
     * It comes back with a new server id, and the phone's rows are sources, so
     * it gives them back rather than dropping them the way the old mirror did.
     */
    @Test
    fun `a recreated server gets the phone's data back`() = runTest {
        val store = replica()
        val server = FakeServer(now)
        val (uid, _) = store.create("waypoint", mapOf("name" to str("Camp")))
        val (gone, _) = store.create("waypoint", mapOf("name" to str("Old")))
        clockMs += 1
        store.delete("waypoint", gone)
        ReplicaSyncEngine(store, server).sync()

        server.recreate()
        val report = ReplicaSyncEngine(store, server).sync()

        assertTrue(report.newServer)
        assertFalse(report.wiped)
        assertEquals(str("Camp"), server.rows.get("waypoint", uid)!!.fields["name"])
        // Deletes go back too, or another phone would resurrect the row.
        assertNotNull(server.rows.get("waypoint", gone)!!.deleted)
        assertEquals(0, store.pendingCount())
        assertEquals(str("Camp"), store.row("waypoint", uid)!!.fields["name"])
        assertEquals(server.serverId, store.server()!!.serverId)
    }

    /**
     * The opposite case, and why the two must be told apart: the user deleted
     * their data on purpose. A phone that re-pushed here would silently undo
     * the deletion; it wipes its own copy instead, unpushed edits included.
     */
    @Test
    fun `a deliberate wipe on the server wipes the phone too`() = runTest {
        val store = replica()
        val server = FakeServer(now)
        val blobs = MemoryBlobs()
        server.addFit(byteArrayOf(1, 2))
        store.create("waypoint", mapOf("name" to str("Camp")))
        ReplicaSyncEngine(store, server, blobs).sync()
        assertEquals(1, blobs.files.size)

        server.deleteMyData()
        clockMs += 1
        store.create("waypoint", mapOf("name" to str("unpushed")))
        val report = ReplicaSyncEngine(store, server, blobs).sync()

        assertTrue(report.wiped)
        assertTrue(store.allRows().isEmpty())
        assertTrue(blobs.files.isEmpty())
        assertTrue(server.state().isEmpty())
        assertEquals(1L, store.server()!!.epoch)
        // And the phone carries on normally at the new epoch.
        store.create("waypoint", mapOf("name" to str("after")))
        ReplicaSyncEngine(store, server, blobs).sync()
        assertEquals(1, server.state().size)
    }

    /** The pull path to the same outcome, with nothing pending to push first. */
    @Test
    fun `a higher epoch in a pull wipes the phone`() = runTest {
        val store = replica()
        val server = FakeServer(now)
        store.create("waypoint", mapOf("name" to str("Camp")))
        ReplicaSyncEngine(store, server).sync()
        server.deleteMyData()

        val report = ReplicaSyncEngine(store, server).sync()
        assertTrue(report.wiped)
        assertTrue(store.allRows().isEmpty())
        assertEquals(1L, store.epoch())
    }

    /**
     * A standalone phone linking to an account whose epoch is already above
     * zero must not guess it: a push stamped epoch 0 would be rejected as
     * wiped and delete the phone's only copy. It pulls first, then pushes.
     */
    @Test
    fun `a first sync learns the epoch before it pushes`() = runTest {
        val server = FakeServer(now)
        server.deleteMyData() // epoch 1, long before this phone existed
        val store = replica()
        val (uid, _) = store.create("waypoint", mapOf("name" to str("standalone")))

        val report = ReplicaSyncEngine(store, server).sync()
        assertFalse(report.wiped)
        assertEquals(str("standalone"), server.rows.get("waypoint", uid)!!.fields["name"])
        assertEquals(1L, store.epoch())
    }

    /**
     * A server a day ahead of this phone's clock. The phone takes its stamps
     * anyway — the server vetted them — rather than stalling its cursor for as
     * long as its own clock stays wrong.
     */
    @Test
    fun `a page stamped far ahead is accepted`() = runTest {
        val server = FakeServer(now = { clockMs + HLC_MAX_SKEW_MS + 60_000 })
        val uid = Uids.v7(clockMs)
        server.serverWrite(Change("waypoint", uid, mapOf("name" to Stamped(str("x"), server.stamp()))))
        val store = replica()
        ReplicaSyncEngine(store, server).sync()
        assertEquals(str("x"), store.row("waypoint", uid)!!.fields["name"])
        assertTrue(store.cursor() > 0)
        // A local edit made afterwards still sorts after it.
        store.edit("waypoint", uid, mapOf("name" to str("y")))
        assertEquals(str("y"), store.row("waypoint", uid)!!.fields["name"])
    }

    // ── Accounts ────────────────────────────────────────────────────────────

    private val home = ServerIdentity("5e5e5e5e5e5e5e5e", 0)

    @Test
    fun `the first sign-in binds the phone, and signing back in resumes`() = runTest {
        val store = replica()
        store.create("waypoint", mapOf("name" to str("recorded standalone")))
        assertEquals(LinkResult.Linked, store.link(home, "1"))
        assertEquals(AccountBinding(home.serverId, "1"), store.boundAccount())
        assertEquals(LinkResult.Resumed, store.link(home, "1"))
    }

    /** Merging one person's history into another's account cannot be undone by any later sync. */
    @Test
    fun `a different account is refused while the phone holds data`() = runTest {
        val store = replica()
        store.link(home, "1")
        store.create("waypoint", mapOf("name" to str("mine")))
        val result = store.link(home, "2")
        assertEquals(LinkResult.OtherAccount(AccountBinding(home.serverId, "1")), result)
        assertEquals(AccountBinding(home.serverId, "1"), store.boundAccount())
        assertEquals(1, store.rows("waypoint").size)
    }

    /**
     * A different server id could be a stranger's server or the user's own,
     * rebuilt — a rebuilt database mints new ids for everything, so even the
     * account id is no clue. So it is asked, never refused and never assumed.
     */
    @Test
    fun `a different server is a question, whatever the account id`() = runTest {
        val store = replica()
        store.link(home, "1")
        store.create("waypoint", mapOf("name" to str("mine")))
        val rebuilt = ServerIdentity("6f6f6f6f6f6f6f6f", 0)
        for (account in listOf("1", "7")) {
            val result = store.link(rebuilt, account, "alex")
            assertEquals(
                LinkResult.DifferentServer(AccountBinding(home.serverId, "1"), rebuilt, account, "alex"),
                result,
            )
        }
        // Asking changed nothing.
        assertEquals(AccountBinding(home.serverId, "1"), store.boundAccount())
    }

    /** Yes: the rebuilt server gets everything back, deletes included, and the phone is its account's. */
    @Test
    fun `restoring into a rebuilt server re-pushes everything`() = runTest {
        val store = replica()
        val old = FakeServer(now)
        val (kept, _) = store.create("waypoint", mapOf("name" to str("Camp")))
        val (gone, _) = store.create("waypoint", mapOf("name" to str("Old")))
        clockMs += 1
        store.delete("waypoint", gone)
        store.link(old.identify(), "1")
        ReplicaSyncEngine(store, old).sync()
        assertEquals(0, store.pendingCount())

        val rebuilt = FakeServer(now, node = "6f6f6f6f6f6f6f6f")
        val question = store.link(rebuilt.identify(), "3", "alex") as LinkResult.DifferentServer
        store.restoreInto(question.server, question.account)
        assertEquals(AccountBinding(rebuilt.serverId, "3"), store.boundAccount())
        assertEquals(2, store.pendingCount())
        assertEquals(0L, store.cursor())

        val report = ReplicaSyncEngine(store, rebuilt).sync()
        assertFalse(report.newServer) // already settled by the restore, not rediscovered
        assertEquals(str("Camp"), rebuilt.rows.get("waypoint", kept)!!.fields["name"])
        assertNotNull(rebuilt.rows.get("waypoint", gone)!!.deleted)
        assertEquals(LinkResult.Resumed, store.link(rebuilt.identify(), "3"))
    }

    /** No: nothing about the phone changes, so signing in to the original server later still works. */
    @Test
    fun `declining a rebuilt server leaves the phone as it was`() = runTest {
        val store = replica()
        store.link(home, "1")
        store.create("waypoint", mapOf("name" to str("mine")))
        val before = store.allRows()
        store.link(ServerIdentity("6f6f6f6f6f6f6f6f", 0), "1", "alex")
        // Declining is a sign-out; the store is not touched at all.
        assertEquals(before, store.allRows())
        assertEquals(AccountBinding(home.serverId, "1"), store.boundAccount())
        assertEquals(LinkResult.Resumed, store.link(home, "1"))
    }

    @Test
    fun `an empty phone rebinds to whoever signs in`() = runTest {
        val store = replica()
        store.link(home, "1")
        assertEquals(LinkResult.Linked, store.link(home, "2"))
        assertEquals("2", store.boundAccount()!!.account)
    }

    /** The explicit way past a refusal, and the only one. */
    @Test
    fun `erasing the phone clears the way for another account`() = runTest {
        val store = replica()
        val server = FakeServer(now)
        val blobs = MemoryBlobs()
        server.addFit(byteArrayOf(7))
        store.link(home, "1")
        store.create("waypoint", mapOf("name" to str("mine")))
        val engine = ReplicaSyncEngine(store, server, blobs)
        engine.sync()
        val node = store.node

        engine.erase()
        assertTrue(store.allRows().isEmpty())
        assertTrue(blobs.files.isEmpty())
        assertNull(store.boundAccount())
        assertNull(store.server())
        assertEquals(0L, store.cursor())
        assertEquals(node, store.node)
        assertEquals(LinkResult.Linked, store.link(home, "2"))
    }

    /** The phone's clock is a day fast: the server refuses, and the edit waits rather than vanishing. */
    @Test
    fun `an edit refused for clock skew stays queued`() = runTest {
        val store = replica()
        val server = FakeServer(now = { clockMs - HLC_MAX_SKEW_MS - 60_000 })
        store.create("waypoint", mapOf("name" to str("from the future")))
        val report = ReplicaSyncEngine(store, server).sync()
        assertEquals(1, report.skewed)
        assertEquals(1, store.pendingCount())
        assertTrue(server.state().isEmpty())
    }

    // ── Blobs ───────────────────────────────────────────────────────────────

    private class MemoryBlobs : BlobStore {
        val files = HashMap<String, ByteArray>()
        override suspend fun has(sha256: String) = sha256 in files
        override suspend fun put(sha256: String, bytes: ByteArray) { files[sha256] = bytes }
        override suspend fun clear() { files.clear() }
    }

    private fun FakeServer.addFit(bytes: ByteArray): String {
        val sha = Digest.hex(Digest.sha256(bytes))
        blobs[sha] = bytes
        serverWrite(
            Change(
                "fit_file", Uids.v5("fit:$sha"),
                mapOf("sha256" to Stamped(JsonPrimitive(sha), stamp()), "size_bytes" to Stamped(JsonPrimitive(bytes.size), stamp())),
            ),
        )
        return sha
    }

    @Test
    fun `missing fit files are fetched and checked`() = runTest {
        val server = FakeServer(now)
        val a = server.addFit(byteArrayOf(1, 2, 3))
        val b = server.addFit(byteArrayOf(4, 5, 6))
        val blobs = MemoryBlobs()
        val report = ReplicaSyncEngine(replica(), server, blobs).sync()
        assertEquals(2, report.blobsFetched)
        assertEquals(setOf(a, b), blobs.files.keys)
    }

    /**
     * The progress popup counts files against the ones still missing, not
     * every file the account has — a phone that already holds most of its
     * history would otherwise show "Syncing 2,980 of 2,994" and jump.
     */
    @Test
    fun `file progress counts only the files still missing`() = runTest {
        val server = FakeServer(now)
        val held = server.addFit(byteArrayOf(1, 2, 3))
        server.addFit(byteArrayOf(4, 5, 6))
        server.addFit(byteArrayOf(7, 8, 9))
        val blobs = MemoryBlobs().apply { files[held] = byteArrayOf(1, 2, 3) }
        val seen = ArrayList<SyncProgress>()
        ReplicaSyncEngine(replica(), server, blobs, onProgress = { seen += it }).sync()
        val files = seen.filter { it.step == SyncProgress.Step.Files }
        assertEquals(listOf(0, 1), files.map { it.done })
        assertTrue(files.all { it.total == 2 })
        assertTrue(seen.any { it.step == SyncProgress.Step.Receiving && it.total == null })
    }

    @Test
    fun `a corrupt download is not stored and is retried`() = runTest {
        val server = FakeServer(now)
        val sha = server.addFit(byteArrayOf(9, 9, 9))
        server.blobs[sha] = byteArrayOf(9, 9) // truncated in transit
        val blobs = MemoryBlobs()
        val store = replica()
        val first = ReplicaSyncEngine(store, server, blobs).sync()
        assertEquals(1, first.blobsCorrupt)
        assertTrue(blobs.files.isEmpty())

        server.blobs[sha] = byteArrayOf(9, 9, 9)
        assertEquals(1, ReplicaSyncEngine(store, server, blobs).sync().blobsFetched)
    }

    /** No queue to persist: an interrupted download is simply still missing. */
    @Test
    fun `blob downloads resume after an interruption`() = runTest {
        val server = FakeServer(now)
        repeat(3) { server.addFit(byteArrayOf(it.toByte(), 1)) }
        val blobs = MemoryBlobs()
        val store = replica()
        var fetched = 0
        server.beforeCall = { if (it == "blob" && ++fetched == 2) throw IOException("dropped") }
        assertFailsWith<IOException> { ReplicaSyncEngine(store, server, blobs).sync() }
        assertEquals(1, blobs.files.size)
        server.beforeCall = {}
        ReplicaSyncEngine(store, server, blobs).sync()
        assertEquals(3, blobs.files.size)
    }

    @Test
    fun `a deleted fit file is not fetched`() = runTest {
        val server = FakeServer(now)
        val sha = server.addFit(byteArrayOf(1))
        server.serverWrite(Change("fit_file", Uids.v5("fit:$sha"), deleted = server.stamp()))
        val blobs = MemoryBlobs()
        ReplicaSyncEngine(replica(), server, blobs).sync()
        assertNull(blobs.files[sha])
    }
}
