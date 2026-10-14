// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.map.packs

import android.util.Log
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Fetches every queued pack, as files, until there are none left.
 *
 * Every edge a download has, handled where it happens:
 *
 * - **Interrupted** (network gone, job stopped, process killed): the bytes so
 *   far stay in `<pack>.part`; the next run asks for the rest with `Range`,
 *   and `If-Range` with the ETag the part came from. The server answers 206
 *   with the rest only if the pack is still those bytes, and 200 with all of
 *   it otherwise, in which case the part is started over.
 * - **Corrupt**: the whole file is hashed and must equal the sha256 the server
 *   sent as its ETag before it is renamed into place. A mismatch deletes the
 *   part and counts a failure.
 * - **Rebuilt on the server** meanwhile: 409. The area's manifest is fetched
 *   again ([refreshManifest]) and the pack queued at its new version.
 * - **No room**: before each pack, the volume must keep its reserve free and
 *   the packs must stay inside the person's budget. Otherwise the pack fails
 *   with the reason and nothing else is started; the list says why.
 * - **Twice at once**: one [Mutex] for the whole queue, so a job and a
 *   foreground worker started together cannot fetch the same pack twice.
 *
 * Returns whether everything finished. False means: run again later (the
 * scheduler's network constraint decides when).
 */
class PackDownloader(
    private val store: PackStore,
    /** [TracksClient.downloadPack], as a function so tests can stand in for the network. */
    private val download: Fetch,
    private val refreshManifest: suspend (PackStore.Pack) -> Unit,
) {

    fun interface Fetch {
        suspend fun fetch(
            url: String,
            from: Long,
            ifRange: String?,
            onStatus: (Int, String?) -> Unit,
            sink: suspend (ByteArray, Int) -> Unit,
        ): com.tracks.core.api.PackResponse
    }

    suspend fun run(onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): Boolean = running.withLock {
        var clean = true
        while (true) {
            val batch = store.pending().take(PARALLEL)
            if (batch.isEmpty()) break
            val outcomes = mutableListOf<Boolean>()
            coroutineScope {
                for (pack in batch) launch {
                    val ok = runCatching { fetch(pack, onProgress) }.getOrElse { e ->
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.w(TAG, "Pack ${pack.key} failed", e)
                        store.markFailed(pack.key, e.message ?: e.javaClass.simpleName, retry = e is IOException)
                        false
                    }
                    synchronized(outcomes) { outcomes += ok }
                }
            }
            if (outcomes.any { !it }) {
                clean = false
                // A network failure leaves its packs queued; going round again
                // at once would spin. Stop, and let the job be rescheduled.
                if (store.pending().any { p -> batch.any { it.key == p.key } }) break
            }
        }
        clean && store.pending().isEmpty()
    }

    private suspend fun fetch(pack: PackStore.Pack, onProgress: (Long, Long) -> Unit): Boolean {
        val dest = store.destination(pack)
        dest.parentFile?.mkdirs()
        val part = store.partFile(pack)
        // Partial files of versions no longer wanted.
        dest.parentFile?.listFiles()?.forEach { f ->
            if (f.name.startsWith(File(pack.key).name + ".") && f.name.endsWith(".part") && f != part) f.delete()
        }

        checkRoom(pack, part.length())
        val etagFile = File(part.path + ".etag")
        var from = if (etagFile.exists()) part.length() else 0L
        val ifRange = etagFile.takeIf { from > 0 }?.readText()
        store.markDownloading(pack.key, from)

        var out: FileOutputStream? = null
        val response = try {
            download.fetch(pack.url, from, ifRange, { code, etag ->
                // 206: the rest of the same bytes. 200: the whole pack — either
                // nothing was held, or what was held is not this pack any more.
                if (code == 200) from = 0
                out = FileOutputStream(part, code == 206)
                // Written now, not at the end: a download killed halfway needs
                // it to resume.
                etag?.let { etagFile.writeText(it) }
            }) { buf, n ->
                out!!.write(buf, 0, n)
                from += n
                store.progress(pack.key, from)
                onProgress(from, pack.bytes)
            }
        } finally {
            out?.let { o -> runCatching { o.fd.sync() }; o.close() }
        }
        when (response.status) {
            200, 206, 416 -> Unit                                   // already had all of it
            409 -> {
                part.delete(); etagFile.delete()
                refreshManifest(pack)
                return false
            }
            404 -> {
                part.delete(); etagFile.delete()
                store.markFailed(pack.key, "The server no longer has this part of the map", retry = false)
                return false
            }
            else -> throw IOException("Server answered ${response.status}")
        }
        val answered = (response.etag ?: etagFile.takeIf { it.exists() }?.readText())?.trim('"')

        val expected = answered ?: throw IOException("No checksum from the server")
        if (sha256(part) != expected) {
            part.delete(); etagFile.delete()
            store.markFailed(pack.key, "Download was damaged in transit; will fetch again", retry = true)
            return false
        }
        if (!part.renameTo(dest)) throw IOException("Could not move ${dest.name} into place")
        etagFile.delete()
        store.markReady(pack.key, pack.version, dest)
        return true
    }

    private fun checkRoom(pack: PackStore.Pack, have: Long) {
        val need = (pack.bytes - have).coerceAtLeast(0)
        val free = store.freeBytes()
        if (free - need < store.reserveBytes()) {
            throw RoomException("Not enough space on this phone: ${format(need)} more needed, ${format(free)} free")
        }
        val s = store.state.value
        if (s.usedBytes + need > store.budget()) {
            throw RoomException("Over your offline-map storage limit of ${format(store.budget())}")
        }
    }

    /** Not an IOException: retrying the same pack cannot make room. */
    class RoomException(message: String) : Exception(message)

    companion object {
        private const val TAG = "TracksPacks"
        /** Packs fetched at once: enough to fill a fast link, few enough to finish. */
        const val PARALLEL = 3
        private val running = Mutex()

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        private fun format(bytes: Long) = when {
            bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
            else -> "${bytes / 1_000_000} MB"
        }
    }
}
