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
 * a backup — and the whole thing is encrypted by the platform (see
 * `com.tracks.app.backup.BackupCrypto`), so nothing here needs to be secret.
 *
 * ## Streamed, never whole
 *
 * Neither side ever holds the whole backup. A history of a few years is well
 * over 100 MB of FIT files, and building it as one array — then a doubling
 * buffer, then a copy, then a ciphertext — ran a phone out of memory at 134 MB.
 * So [write] hands bytes to a [ByteSink] one file at a time, and
 * [BackupReader] pulls them from a [ByteSource] the same way.
 */
object BackupFormat {
    const val FORMAT = "tracks-backup"
    const val VERSION = 1

    /**
     * Encode a backup into [out]. [read] fetches each named file; one that
     * cannot be read (its blob is damaged on this phone) is written empty
     * rather than left out, because the names are committed in the manifest
     * before any file is read — reading every file twice to filter first would
     * double the time a backup takes. An empty file is skipped on restore.
     */
    suspend fun write(
        out: ByteSink,
        binding: AccountBinding?,
        rows: List<Change>,
        names: List<String>,
        createdAtMs: Long,
        read: suspend (String) -> ByteArray?,
    ) {
        val manifest = buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("created_at_ms", createdAtMs)
            binding?.let {
                put("binding", buildJsonObject { put("server_id", it.serverId); put("account", it.account) })
            }
            put("rows", JsonArray(rows.map(Wire::encodeChange)))
            put("files", JsonArray(names.map { JsonPrimitive(it) }))
        }.toString().encodeToByteArray()
        out.int(manifest.size)
        out.write(manifest, 0, manifest.size)
        for (name in names) {
            val bytes = read(name) ?: ByteArray(0)
            out.int(bytes.size)
            out.write(bytes, 0, bytes.size)
        }
    }
}

/** Where an encoded backup goes, a piece at a time. */
fun interface ByteSink {
    fun write(bytes: ByteArray, offset: Int, length: Int)
}

/** Where an encoded backup comes from: fills up to [length] bytes, or returns -1 at the end. */
fun interface ByteSource {
    fun read(into: ByteArray, offset: Int, length: Int): Int
}

class ByteArraySource(private val data: ByteArray) : ByteSource {
    private var pos = 0
    override fun read(into: ByteArray, offset: Int, length: Int): Int {
        if (pos == data.size) return -1
        val n = minOf(length, data.size - pos)
        data.copyInto(into, offset, pos, pos + n)
        pos += n
        return n
    }
}

/**
 * Reads a backup written by [BackupFormat.write]: the manifest at once, the
 * files one at a time through [forEachFile]. Throws [BackupFormatException] on
 * anything malformed or truncated.
 */
class BackupReader(source: ByteSource) {
    private val input = Reader(source)
    val binding: AccountBinding?
    val rows: List<Change>
    val createdAtMs: Long
    private val names: List<String>

    init {
        val manifest = runCatching {
            Json.parseToJsonElement(input.bytes(input.int()).decodeToString()).jsonObject
        }.getOrElse { throw it as? BackupFormatException ?: BackupFormatException("The backup's contents are unreadable.") }
        if (manifest["format"]?.jsonPrimitive?.content != BackupFormat.FORMAT) {
            throw BackupFormatException("This is not a Tracks backup.")
        }
        val version = manifest["version"]?.jsonPrimitive?.int ?: 0
        if (version > BackupFormat.VERSION) {
            throw BackupFormatException("This backup was made by a newer version of Tracks.")
        }
        binding = (manifest["binding"] as? JsonObject)?.let {
            AccountBinding(
                it.getValue("server_id").jsonPrimitive.content,
                it.getValue("account").jsonPrimitive.content,
            )
        }
        rows = manifest.getValue("rows").jsonArray.map { Wire.decodeChange(it.jsonObject) }
        names = manifest.getValue("files").jsonArray.map { it.jsonPrimitive.content }
        createdAtMs = manifest["created_at_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0
    }

    /** Every file in order, then a check that nothing follows them. Call once. */
    suspend fun forEachFile(action: suspend (name: String, bytes: ByteArray) -> Unit) {
        for (name in names) action(name, input.bytes(input.int()))
        if (!input.atEnd()) throw BackupFormatException("The backup has trailing data.")
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
    suspend fun write(out: ByteSink) = BackupFormat.write(
        out,
        binding = replica.boundAccount(),
        rows = replica.allRows().map { it.asChange() },
        names = raw.list().sorted(),
        createdAtMs = now(),
        read = raw::get,
    )

    /**
     * Read a backup to its end without changing anything. A restore runs this
     * over the file first: streaming means the last chunk is only checked
     * after the first files are imported, and a damaged or truncated backup
     * must be refused before it has half-overwritten the phone, not after.
     */
    suspend fun verify(source: ByteSource) = BackupReader(source).forEachFile { _, _ -> }

    /**
     * Put a backup onto this phone. Returns how many FIT files were restored.
     *
     * Rows first, then files: importing a file derives activities whose uids
     * the rows already reference (a rename, a completed workout), and doing it
     * in this order means nothing is ever briefly orphaned.
     */
    suspend fun restore(source: ByteSource): Int {
        val reader = BackupReader(source)
        replica.restoreBackup(reader.rows, reader.binding)
        var restored = 0
        reader.forEachFile { _, bytes ->
            if (bytes.isNotEmpty()) runCatching { files.add(bytes) }.onSuccess { restored++ }
        }
        return restored
    }
}

// ── Length-prefixed bytes ────────────────────────────────────────────────────

private fun ByteSink.int(v: Int) {
    write(ByteArray(4) { (v ushr (24 - 8 * it)).toByte() }, 0, 4)
}

private class Reader(private val source: ByteSource) {
    fun int(): Int {
        val b = bytes(4)
        var v = 0
        for (x in b) v = (v shl 8) or (x.toInt() and 0xff)
        // The lengths are authenticated with the rest, so a huge one is a bug
        // rather than an attack — but refusing it beats an OutOfMemoryError
        // that names nothing. No FIT file, and no manifest, comes near this.
        if (v < 0 || v > MAX_LENGTH) throw BackupFormatException("The backup is corrupt.")
        return v
    }

    fun bytes(n: Int): ByteArray {
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = source.read(out, got, n - got)
            if (r < 0) throw BackupFormatException("The backup is truncated.")
            got += r
        }
        return out
    }

    fun atEnd(): Boolean {
        val probe = ByteArray(1)
        while (true) {
            val r = source.read(probe, 0, 1)
            if (r != 0) return r < 0
        }
    }

    private companion object {
        const val MAX_LENGTH = 256 shl 20
    }
}
