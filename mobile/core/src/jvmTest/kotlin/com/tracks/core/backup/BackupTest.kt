// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.backup

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

    @Test
    fun a_payload_survives_a_round_trip_byte_for_byte() = runTest {
        val (source, _) = newReplica(now, 1)
        source.create("waypoint", mapOf("name" to str("Camp 2")))
        val payload = BackupPayload(
            binding = null,
            rows = source.allRows().map { it.asChange() },
            files = listOf("aa" to byteArrayOf(1, 2, 3), "bb" to ByteArray(0)),
            createdAtMs = 42,
        )
        val back = BackupPayload.decode(payload.encode())
        assertEquals(payload.rows, back.rows)
        assertEquals(listOf("aa", "bb"), back.files.map { it.first })
        assertContentEquals(byteArrayOf(1, 2, 3), back.files[0].second)
        assertEquals(42, back.createdAtMs)
    }

    /** A cut-off file must say so, not restore half a history as if it were whole. */
    @Test
    fun a_truncated_payload_is_refused() {
        val bytes = BackupPayload(null, emptyList(), listOf("aa" to ByteArray(100)), 0).encode()
        assertFailsWith<BackupFormatException> { BackupPayload.decode(bytes.copyOf(bytes.size - 10)) }
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
