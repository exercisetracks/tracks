// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.sync

import com.tracks.core.api.CourseIngestRequest
import com.tracks.core.api.IngestBatchRequest
import com.tracks.core.api.IngestMissingRequest
import com.tracks.core.api.IngestRequest
import com.tracks.core.api.TracksClient
import com.tracks.core.crypto.IngestCrypto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Gets a file off the phone and onto the server without needing a session.
 *
 * This is the half of watch sync that makes the expedition case work. The
 * transport layer hands over bytes; this seals them with the user's public key
 * and posts them to `/sync/ingest`, which is authenticated by a sync-agent token
 * rather than the JWT and requires no open vault. The server queues a blob it
 * cannot itself read and drains the queue the next time the user logs in.
 *
 * So a phone that has not seen a password in a month, talking to a server whose
 * Redis restarted last week, still delivers today's ride.
 */
@OptIn(ExperimentalEncodingApi::class)
class WatchUploader(
    private val client: TracksClient,
    private val crypto: IngestCrypto,
    private val agentToken: String,
) {

    /**
     * The user's sealing key, fetched once.
     *
     * It is a public key and it does not rotate in normal operation, so
     * re-fetching it per file would be a network round trip per activity for no
     * new information — and on a sync of twenty files with no signal, twenty
     * chances to fail.
     */
    private var publicKey: ByteArray? = null

    /**
     * Upload one file.
     *
     * Returns what the server did with it, which the caller needs in order to
     * decide whether the watch may discard its copy.
     */
    suspend fun upload(
        filename: String,
        bytes: ByteArray,
        deviceSerial: String? = null,
    ): IngestOutcome {
        val key = key(deviceSerial)

        val response = client.ingest(
            agentToken,
            IngestRequest(
                deviceSerial = deviceSerial,
                filename = filename,
                // The hash is of the plaintext, not of what we send. A sealed
                // box is randomised, so hashing the ciphertext would make every
                // retry of the same file look like a new activity — which is the
                // exact bug the server's dedup exists to prevent.
                contentHash = crypto.sha256Hex(bytes),
                sealedB64 = Base64.encode(crypto.seal(key, bytes)),
            ),
        )

        return when (response.status) {
            "queued" -> IngestOutcome.Queued
            "duplicate" -> IngestOutcome.AlreadyHeld
            else -> IngestOutcome.Rejected(response.status)
        }
    }

    /**
     * Of these plaintext hashes, the ones the server does not hold.
     *
     * Needs the `sync_ingest_batch` feature. One request answers thousands of
     * files, which is what keeps a phone meeting a new server from sending its
     * whole library just to be told "duplicate" for most of it.
     */
    suspend fun missing(hashes: List<String>, deviceSerial: String? = null): List<String> =
        client.ingestMissing(agentToken, IngestMissingRequest(deviceSerial, hashes)).missing

    /**
     * [upload], for many files in one request. Returns one outcome per file,
     * in the order given.
     *
     * Needs the `sync_ingest_batch` feature. Throws if the request as a whole
     * fails, in which case none of these files may be counted delivered; a
     * server answering with the wrong number of results is treated the same
     * way, since there is then no saying which file an answer belongs to.
     */
    suspend fun uploadBatch(
        files: List<Pair<String, ByteArray>>,
        deviceSerial: String? = null,
    ): List<IngestOutcome> {
        if (files.isEmpty()) return emptyList()
        val key = key(deviceSerial)
        val requests = files.map { (filename, bytes) ->
            IngestRequest(
                deviceSerial = deviceSerial,
                filename = filename,
                // Of the plaintext, for the reason given in [upload].
                contentHash = crypto.sha256Hex(bytes),
                sealedB64 = Base64.encode(crypto.seal(key, bytes)),
            )
        }
        val results = client.ingestBatch(agentToken, IngestBatchRequest(deviceSerial, requests)).results
        check(results.size == requests.size) {
            "server answered ${results.size} results for ${requests.size} files"
        }
        return results.map {
            when (it.status) {
                "queued" -> IngestOutcome.Queued
                "duplicate" -> IngestOutcome.AlreadyHeld
                else -> IngestOutcome.Rejected(it.status)
            }
        }
    }

    /**
     * Send a course the watch was carrying.
     *
     * Not through [upload], and the difference is not cosmetic. That path seals
     * the bytes and queues them for the activity importer, which would read a
     * route as a workout that never took place. This one hands the file to the
     * endpoint that parses it into a saved track, in the clear — a course is a
     * route the user chose to carry, not a record of where they went.
     *
     * Returns whether the server took it, in the same shape as [upload] so the
     * caller's "may the watch drop its copy" decision reads the same either way.
     * There is no duplicate answer here: re-ingesting a course the server already
     * has updates the track it already made, which is fine and is still delivery.
     */
    suspend fun uploadCourse(
        filename: String,
        bytes: ByteArray,
        deviceSerial: String? = null,
    ): IngestOutcome = runCatching {
        client.ingestCourse(
            agentToken,
            CourseIngestRequest(
                filename = filename,
                fitB64 = Base64.encode(bytes),
                size = bytes.size.toLong(),
            ),
            deviceSerial = deviceSerial,
        )
        IngestOutcome.Queued
    }.getOrElse { IngestOutcome.Rejected(it.message ?: "course ingest failed") }

    /**
     * Send the saved places the watch is carrying.
     *
     * Unsealed for the same reason as [uploadCourse]: these are places somebody
     * marked and chose to carry, and the server reconciles its own list against
     * the file immediately rather than queueing it behind a login.
     */
    suspend fun uploadLocations(
        bytes: ByteArray,
        deviceSerial: String? = null,
    ): IngestOutcome = runCatching {
        client.ingestLocations(
            agentToken,
            fitB64 = Base64.encode(bytes),
            deviceSerial = deviceSerial,
        )
        IngestOutcome.Queued
    }.getOrElse { IngestOutcome.Rejected(it.message ?: "locations ingest failed") }

    // Locked because batches go up several at a time: without it each one
    // starting together would fetch the key for itself.
    private val keyLock = Mutex()

    private suspend fun key(deviceSerial: String?): ByteArray = keyLock.withLock {
        publicKey ?: fetchPublicKey(deviceSerial).also { publicKey = it }
    }

    private suspend fun fetchPublicKey(deviceSerial: String?): ByteArray =
        Base64.decode(client.syncPubkey(agentToken, deviceSerial).publicKey)

    /** Forget the cached key, for the rare case where the user's keys rotate. */
    fun invalidateKey() {
        publicKey = null
    }
}

/**
 * What the server did with an uploaded file.
 *
 * The distinction that matters is not queued-versus-duplicate — it is
 * [delivered] versus not. A duplicate means the server already holds these
 * bytes, which is a *success*: it happens whenever an upload succeeded but the
 * acknowledgement was lost, and treating it as failure would leave the file
 * pinned on the watch forever, re-uploaded on every sync.
 */
sealed interface IngestOutcome {
    val delivered: Boolean

    /** Accepted and waiting for the user's next login to be imported. */
    data object Queued : IngestOutcome {
        override val delivered = true
    }

    /** The server already had it. Same practical meaning as [Queued]. */
    data object AlreadyHeld : IngestOutcome {
        override val delivered = true
    }

    data class Rejected(val status: String) : IngestOutcome {
        override val delivered = false
    }
}
