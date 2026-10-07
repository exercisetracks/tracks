// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.backup

import com.tracks.core.replica.Change
import com.tracks.core.replica.LinkResult
import com.tracks.core.replica.ServerIdentity
import com.tracks.core.replica.newReplica
import com.tracks.core.replica.str
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackupTest {
    private var clock = 1_000_000L
    private val now = { clock }

    /** Collects what a backup writes, in order. */
    private class Collected : ByteSink {
        val chunks = mutableListOf<ByteArray>()
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            chunks += bytes.copyOfRange(offset, offset + length)
        }
        fun bytes(): ByteArray = chunks.fold(ByteArray(0)) { a, b -> a + b }
    }

    private suspend fun encode(files: Map<String, ByteArray?>, rows: List<Change> = emptyList(), at: Long = 0) =
        Collected().also { BackupFormat.write(it, null, rows, files.keys.toList(), at, read = { name -> files[name] }) }.bytes()

    private suspend fun readAll(bytes: ByteArray): Pair<BackupReader, List<Pair<String, ByteArray>>> {
        val reader = BackupReader(ByteArraySource(bytes))
        val files = mutableListOf<Pair<String, ByteArray>>()
        reader.forEachFile { name, b -> files += name to b }
        return reader to files
    }

    @Test
    fun a_backup_survives_a_round_trip_byte_for_byte() = runTest {
        val (source, _) = newReplica(now, 1)
        source.create("waypoint", mapOf("name" to str("Camp 2")))
        val rows = source.allRows().map { it.asChange() }
        val (back, files) = readAll(encode(mapOf("aa" to byteArrayOf(1, 2, 3), "bb" to ByteArray(0)), rows, at = 42))
        assertEquals(rows, back.rows)
        assertEquals(listOf("aa", "bb"), files.map { it.first })
        assertContentEquals(byteArrayOf(1, 2, 3), files[0].second)
        assertEquals(42, back.createdAtMs)
    }

    /**
     * Each file reaches the sink before the next is read. Holding them all
     * first is what ran a phone with a 134 MB history out of memory.
     */
    @Test
    fun a_backup_is_written_one_file_at_a_time() = runTest {
        val out = Collected()
        val written = mutableListOf<Int>()
        BackupFormat.write(out, null, emptyList(), listOf("a", "b", "c"), 0, read = {
            written += out.chunks.size
            ByteArray(10)
        })
        // manifest length + manifest, then a length and a body per file.
        assertEquals(listOf(2, 4, 6), written)
    }

    /** What the progress pill and notification count: every file, from none to all. */
    @Test
    fun progress_counts_every_file_from_none_to_all() = runTest {
        val seen = mutableListOf<Pair<Int, Int>>()
        BackupFormat.write(Collected(), null, emptyList(), listOf("a", "b"), 0, { ByteArray(1) }) { done, total ->
            seen += done to total
        }
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), seen)
    }

    /** A blob this phone cannot decrypt is written empty, and skipped rather than failing a restore. */
    @Test
    fun an_unreadable_file_is_written_empty() = runTest {
        val (_, files) = readAll(encode(mapOf("aa" to null, "bb" to byteArrayOf(7))))
        assertEquals(0, files[0].second.size)
        assertContentEquals(byteArrayOf(7), files[1].second)
    }

    /** A cut-off file must say so, not restore half a history as if it were whole. */
    @Test
    fun a_truncated_backup_is_refused() = runTest {
        val bytes = encode(mapOf("aa" to ByteArray(100)))
        assertFailsWith<BackupFormatException> { readAll(bytes.copyOf(bytes.size - 10)) }
    }

    @Test
    fun trailing_data_is_refused() = runTest {
        val bytes = encode(mapOf("aa" to ByteArray(100)))
        assertFailsWith<BackupFormatException> { readAll(bytes + byteArrayOf(0)) }
    }

    /** A source that hands back a few bytes at a time, as a stream may, reads the same. */
    @Test
    fun short_reads_are_reassembled() = runTest {
        val bytes = encode(mapOf("aa" to ByteArray(1000) { it.toByte() }))
        val whole = ByteArraySource(bytes)
        val trickle = ByteSource { into, off, len -> whole.read(into, off, minOf(len, 3)) }
        val reader = BackupReader(trickle)
        var got: ByteArray? = null
        reader.forEachFile { _, b -> got = b }
        assertContentEquals(ByteArray(1000) { it.toByte() }, got)
    }

    @Test
    fun restored_rows_keep_their_stamps_deletes_and_are_queued_to_push() = runTest {
        val (source, _) = newReplica(now, 1)
        val (uid, _) = source.create("waypoint", mapOf("name" to str("Camp 2")))
        val (gone, _) = source.create("waypoint", mapOf("name" to str("Old")))
        source.delete("waypoint", gone)
        val original = source.allRows().associateBy { it.uid }

        val (fresh, _) = newReplica(now, 2)
        fresh.restoreBackup(source.allRows().map { it.asChange() }, binding = null)

        val restored = fresh.allRows().associateBy { it.uid }
        assertEquals(original[uid]!!.clock, restored[uid]!!.clock)
        assertTrue(restored[gone]!!.isTombstone, "a deletion must survive a restore, or it comes back")
        assertEquals(2L, fresh.pendingCount(), "a restored phone re-sends everything; the server dedupes")
    }

    /** The data belongs to one account; a restore must not let it be linked to another. */
    @Test
    fun a_restored_phone_can_only_be_linked_back_to_its_account() = runTest {
        val home = ServerIdentity("5e5e5e5e5e5e5e5e", 0)
        val (source, _) = newReplica(now, 1)
        source.create("waypoint", mapOf("name" to str("Camp 2")))
        source.link(home, "acct-1")

        val (fresh, _) = newReplica(now, 2)
        fresh.restoreBackup(source.allRows().map { it.asChange() }, source.boundAccount())

        assertEquals(LinkResult.Resumed, fresh.link(home, "acct-1"))
        assertTrue(fresh.link(home, "acct-2") is LinkResult.OtherAccount)
    }
}
