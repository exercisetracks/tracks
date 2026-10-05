// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.backup

import com.tracks.core.local.LocalFiles
import com.tracks.core.local.RawBlobs
import com.tracks.core.replica.AccountBinding
import com.tracks.core.replica.Change
import com.tracks.core.replica.ReplicaStore
import com.tracks.core.replica.Stamped
import com.tracks.core.replica.SyncedRow
import com.tracks.core.replica.Wire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * What a backup holds, before encryption: everything a fresh install needs to
 * become this phone again.
 *
 * ## Why these three things and nothing else
 *
 * For a phone that has never been linked to a server this is the only copy of
 * someone's training history, so the question is what rebuilds it, not what
 * the phone happens to store. That is:
 *
 * - **Every synced row**, with its per-field stamps and tombstones. Stamps,
 *   because restored rows must merge correctly with a server or another phone
 *   later — a row restored without them would lose every conflict it ever met.
 *   Tombstones, because without them a deleted workout comes back the first
 *   time the restored phone meets a device that still has it.
 * - **Every FIT file**, as plain bytes. Activities, sleep, health days and
 *   training load are all derived from these by [LocalFiles.add] on restore,
 *   exactly as they were the first time — the derived tables are deliberately
 *   not in the backup, so a backup can never disagree with its own files.
 * - **The account binding**, so a restored phone can be linked back to the
 *   account the data belongs to and to no other (see [ReplicaStore.link]).
 *
 * Not the node id or the clock: see [ReplicaStore.restoreBackup].
 *
 * ## Format
 *
 * A length-prefixed JSON manifest, then each file length-prefixed in manifest
 * order. Plain enough to read with no Tracks code at all, which is the point of
 * a backup — and the whole thing is encrypted as one unit by the platform (see
 * `com.tracks.app.backup.BackupCrypto`), so nothing here needs to be secret.
 */
data class BackupPayload(
    val binding: AccountBinding?,
    val rows: List<Change>,
    val files: List<Pair<String, ByteArray>>,
    val createdAtMs: Long,
) {
    fun encode(): ByteArray {
        val manifest = buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("created_at_ms", createdAtMs)
            binding?.let {
                put("binding", buildJsonObject { put("server_id", it.serverId); put("account", it.account) })
            }
            put("rows", JsonArray(rows.map(Wire::encodeChange)))
            put("files", JsonArray(files.map { JsonPrimitive(it.first) }))
        }.toString().encodeToByteArray()
        val out = Bytes()
        out.int(manifest.size)
        out.bytes(manifest)
        for ((_, bytes) in files) {
            out.int(bytes.size)
            out.bytes(bytes)
        }
        return out.toByteArray()
    }

    companion object {
        const val FORMAT = "tracks-backup"
        const val VERSION = 1

        /** Throws [BackupFormatException] on anything malformed or truncated. */
        fun decode(data: ByteArray): BackupPayload {
            val input = Reader(data)
            val manifest = runCatching {
                Json.parseToJsonElement(input.bytes(input.int()).decodeToString()).jsonObject
            }.getOrElse { throw BackupFormatException("The backup's contents are unreadable.") }
            if (manifest["format"]?.jsonPrimitive?.content != FORMAT) {
                throw BackupFormatException("This is not a Tracks backup.")
            }
            val version = manifest["version"]?.jsonPrimitive?.int ?: 0
            if (version > VERSION) {
                throw BackupFormatException("This backup was made by a newer version of Tracks.")
            }
            val binding = (manifest["binding"] as? JsonObject)?.let {
                AccountBinding(
                    it.getValue("server_id").jsonPrimitive.content,
                    it.getValue("account").jsonPrimitive.content,
                )
            }
            val rows = manifest.getValue("rows").jsonArray.map { Wire.decodeChange(it.jsonObject) }
            val names = manifest.getValue("files").jsonArray.map { it.jsonPrimitive.content }
            val files = names.map { it to input.bytes(input.int()) }
            if (!input.atEnd) throw BackupFormatException("The backup has trailing data.")
            return BackupPayload(
                binding = binding,
                rows = rows,
                files = files,
                createdAtMs = manifest["created_at_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
            )
        }
    }
}

class BackupFormatException(message: String) : Exception(message)

/** A full synced row as a change that writes every field it has — the shape a pull delivers. */
fun SyncedRow.asChange(): Change = Change(
    entity = entity,
    uid = uid,
    fields = fields.mapNotNull { (name, value) -> clock[name]?.let { name to Stamped(value, it) } }.toMap(),
    deleted = deleted,
)

/**
 * Taking and restoring backups over the phone's own stores.
 *
 * The encryption is the platform's job and happens outside this class; what
 * is here is platform-free so it can be tested against real stores on the JVM.
 */
class BackupService(
    private val replica: ReplicaStore,
    private val raw: RawBlobs,
    private val files: LocalFiles,
    private val now: () -> Long,
) {
    suspend fun snapshot(): BackupPayload = BackupPayload(
        binding = replica.boundAccount(),
        rows = replica.allRows().map { it.asChange() },
        files = raw.list().sorted().mapNotNull { sha -> raw.get(sha)?.let { sha to it } },
        createdAtMs = now(),
    )

    /**
     * Put a backup onto this phone. Returns how many FIT files were restored.
     *
     * Rows first, then files: importing a file derives activities whose uids
     * the rows already reference (a rename, a completed workout), and doing it
     * in this order means nothing is ever briefly orphaned.
     */
    suspend fun restore(payload: BackupPayload): Int {
        replica.restoreBackup(payload.rows, payload.binding)
        var restored = 0
        for ((_, bytes) in payload.files) {
            runCatching { files.add(bytes) }.onSuccess { restored++ }
        }
        return restored
    }
}

// ── Length-prefixed bytes ────────────────────────────────────────────────────

private class Bytes {
    private var buf = ByteArray(1 shl 16)
    private var size = 0

    private fun ensure(extra: Int) {
        if (size + extra <= buf.size) return
        var cap = buf.size
        while (cap < size + extra) cap *= 2
        buf = buf.copyOf(cap)
    }

    fun int(v: Int) {
        ensure(4)
        for (shift in intArrayOf(24, 16, 8, 0)) buf[size++] = (v ushr shift).toByte()
    }

    fun bytes(b: ByteArray) {
        ensure(b.size)
        b.copyInto(buf, size)
        size += b.size
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

private class Reader(private val data: ByteArray) {
    private var pos = 0
    val atEnd: Boolean get() = pos == data.size

    fun int(): Int {
        if (pos + 4 > data.size) throw BackupFormatException("The backup is truncated.")
        var v = 0
        repeat(4) { v = (v shl 8) or (data[pos++].toInt() and 0xff) }
        if (v < 0) throw BackupFormatException("The backup is corrupt.")
        return v
    }

    fun bytes(n: Int): ByteArray {
        if (n > data.size - pos) throw BackupFormatException("The backup is truncated.")
        return data.copyOfRange(pos, pos + n).also { pos += n }
    }
}
