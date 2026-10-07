// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.sync

import com.tracks.core.api.Limits
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sends the server every file in a backlog, as fast as the link allows.
 *
 * The phone used to send its backlog one file per request, each waiting for
 * the last: read, seal, post, wait for the answer, mark it, next. On a
 * first upload of several years of history to a server across the internet
 * that ran at about two files a second — latency, not bandwidth, since the
 * files are small — so 3,700 files took half an hour. Worse, a phone meeting
 * a server it had not uploaded to re-queues everything
 * ([com.tracks.core.local.LocalLibrary.uploadsGoTo]), and every file the
 * server already had still went up in full to be answered "duplicate".
 *
 * So, in two steps:
 *
 * 1. **Ask first.** One request names thousands of hashes and the server says
 *    which it lacks; the rest are marked sent without leaving the phone.
 * 2. **Send in batches, several at once.** Files are packed into requests of
 *    up to [batchBytes], and [inFlight] of those travel together, so the
 *    round trip is paid per batch and overlapped rather than paid per file
 *    in series.
 *
 * Memory is bounded on purpose. Files are read as batches are packed, not
 * all up front: a long history does not fit in a phone's heap (the backup
 * path learned this first), and a batch in flight holds its plaintext, its
 * sealed copy and its base64 at once. At most [inFlight] + 2 batches are
 * alive: one per worker, one waiting in the channel, one being packed.
 *
 * A file is reported delivered only from its own entry in a batch's answer,
 * and as soon as its batch returns, so a run that dies half-way keeps
 * everything it got through. The first failed request ends the run; what
 * was not delivered stays queued for the next.
 */
class BulkUpload(
    private val uploader: WatchUploader,
    /** A stored file's bytes by hash, or null if it has gone missing. */
    private val read: suspend (String) -> ByteArray?,
    limits: Limits = Limits(),
    private val inFlight: Int = 4,
    batchBytes: Long = 2L * 1024 * 1024,
) {
    // The server's caps are a ceiling, not a target: 2 MB is enough to make
    // the round trip negligible beside the transfer, and small enough that a
    // batch lost to a dropped connection is cheap to send again.
    private val batchBytes = batchBytes.coerceAtMost(limits.ingestBatchBytes)
    private val batchFiles = limits.ingestBatchFiles.coerceAtLeast(1)
    private val askChunk = limits.ingestMissingHashes.coerceAtLeast(1)

    /**
     * Send [pending]. [onDelivered] is called with each group of hashes the
     * server now holds — already had, or just took — and never concurrently
     * with itself. [onProgress] counts files dealt with, out of all of
     * [pending].
     *
     * Throws on the first request that fails; everything reported to
     * [onDelivered] before that is genuinely on the server.
     */
    suspend fun send(
        pending: List<String>,
        onDelivered: suspend (List<String>) -> Unit,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int = coroutineScope {
        val total = pending.size
        var done = 0
        var sent = 0
        val report = Mutex()

        val missing = LinkedHashSet<String>()
        for (chunk in pending.chunked(askChunk)) missing += uploader.missing(chunk)
        val held = pending.filter { it !in missing }
        if (held.isNotEmpty()) onDelivered(held)
        done += held.size
        onProgress(done, total)

        val batches = Channel<List<Pair<String, ByteArray>>>(capacity = 1)
        launch {
            var batch = mutableListOf<Pair<String, ByteArray>>()
            var bytes = 0L
            for (sha in missing) {
                // Gone from storage: nothing to send, and nothing to mark —
                // the old loop skipped these the same way.
                val file = read(sha)
                if (file == null) {
                    report.withLock { done++; onProgress(done, total) }
                    continue
                }
                if (batch.isNotEmpty() && (bytes + file.size > batchBytes || batch.size >= batchFiles)) {
                    batches.send(batch)
                    batch = mutableListOf()
                    bytes = 0
                }
                batch += sha to file
                bytes += file.size
            }
            if (batch.isNotEmpty()) batches.send(batch)
            batches.close()
        }

        List(inFlight.coerceAtLeast(1)) {
            async {
                for (batch in batches) {
                    val outcomes = uploader.uploadBatch(batch.map { (sha, bytes) -> "$sha.fit" to bytes })
                    val delivered = batch.zip(outcomes).filter { it.second.delivered }.map { it.first.first }
                    report.withLock {
                        if (delivered.isNotEmpty()) onDelivered(delivered)
                        sent += delivered.size
                        done += batch.size
                        onProgress(done, total)
                    }
                }
            }
        }.awaitAll()
        sent
    }
}
