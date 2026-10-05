// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import com.tracks.core.replica.BlobStore
import com.tracks.core.replica.Digest

/**
 * Raw file storage, keyed by SHA-256. The platform decides where and how; on
 * Android that is app-private storage encrypted with a Keystore key, because a
 * FIT file carries precise GPS the server itself only ever stores sealed.
 */
interface RawBlobs {
    suspend fun has(sha256: String): Boolean
    suspend fun get(sha256: String): ByteArray?
    suspend fun put(sha256: String, bytes: ByteArray)
    suspend fun list(): List<String>
    suspend fun clear()
}

/**
 * Every FIT file on this phone, and the one door they come in by.
 *
 * Whether a file arrived from the watch over Bluetooth or from the server's
 * history download, it is stored and then imported in the same call. Keeping
 * those two steps together is what makes "the phone holds a file" and "the
 * phone shows what is in it" the same fact; a file stored but not imported
 * would be data the user owns and cannot see.
 *
 * It is also the [BlobStore] the sync engine downloads into, so the server's
 * history lands on the same path.
 */
class LocalFiles(
    private val raw: RawBlobs,
    private val library: LocalLibrary,
    private val importer: LocalImporter,
) : BlobStore {

    override suspend fun has(sha256: String): Boolean = raw.has(sha256)

    /** Store and import. Called by the sync engine after it verified the hash. */
    override suspend fun put(sha256: String, bytes: ByteArray) {
        raw.put(sha256, bytes)
        importer.import(sha256, bytes)
        // A file that came *from* the server does not need to go back up.
        library.markUploaded(sha256)
    }

    /**
     * A file this phone produced — pulled off the watch, or recorded itself.
     * Returns its hash. Not yet on any server, so it is queued for upload.
     */
    suspend fun add(bytes: ByteArray): Pair<String, LocalImporter.Outcome> {
        val sha = Digest.hex(Digest.sha256(bytes))
        raw.put(sha, bytes)
        return sha to importer.import(sha, bytes)
    }

    suspend fun get(sha256: String): ByteArray? = raw.get(sha256)

    /** See [LocalImporter.backfillSummaries]. Once per launch is plenty; it is a no-op when current. */
    suspend fun backfillSummaries(): Int = importer.backfillSummaries { raw.get(it) }

    /**
     * Import anything stored but never imported — a process killed between
     * the two steps, or derived tables dropped by a schema change (which
     * drops them on purpose: every row here can be rebuilt from the files).
     */
    suspend fun importMissing(): Int {
        var n = 0
        for (sha in raw.list()) {
            if (library.file(sha) != null) continue
            val bytes = raw.get(sha) ?: continue
            importer.import(sha, bytes)
            n++
        }
        return n
    }

    override suspend fun clear() {
        raw.clear()
        library.clear()
    }
}

/** In memory — for tests, and nothing else. */
class MemoryBlobs : RawBlobs {
    private val files = LinkedHashMap<String, ByteArray>()
    override suspend fun has(sha256: String) = sha256 in files
    override suspend fun get(sha256: String) = files[sha256]
    override suspend fun put(sha256: String, bytes: ByteArray) { files[sha256] = bytes }
    override suspend fun list() = files.keys.toList()
    override suspend fun clear() = files.clear()
}
