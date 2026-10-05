// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.replica

import com.tracks.core.api.TracksClient
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Where FIT files live on this device. The platform decides how — on Android
 * in app-private storage, encrypted at rest — so core only says what it needs.
 */
interface BlobStore {
    suspend fun has(sha256: String): Boolean

    /** Store [bytes]; only called after they have been checked against [sha256]. */
    suspend fun put(sha256: String, bytes: ByteArray)

    /** Delete every file — for an account wiped on purpose, or an explicit erase. */
    suspend fun clear()
}

/**
 * One sync: push what this phone changed, pull what everyone else did, then
 * fetch any FIT files it does not have.
 *
 * ## The order
 *
 * Push first, so that a pull can never be the thing that tells this phone
 * about its own edits' fate before the server has heard them; pull second;
 * blobs last, because they are the bulk and the least urgent. Every step is
 * idempotent and each one's progress is stored with its data, so a sync cut
 * off anywhere — no signal, process killed — resumes correctly next time.
 *
 * ## Rules kept from the changefeed engine before this one
 *
 * - **The cursor moves with its data.** [ReplicaStore.applyPulled] writes a
 *   page's rows and its cursor in one transaction.
 * - **The walk is bounded.** A stuck cursor or a pathological server would
 *   otherwise pin the radio until the battery died. Truncation costs latency,
 *   never data: the next run resumes.
 * - **Every pull names the server and the account's epoch**, and two events
 *   that look alike are treated oppositely (spec/sync.yaml, "Wire"):
 *   - a new `server_id` is a recreated server, which gets this phone's data
 *     back ([ReplicaStore.resetForNewServer]);
 *   - a higher `epoch` is the user deleting their data on purpose, which this
 *     phone must follow by deleting its copy ([ReplicaStore.wipeForEpoch]) —
 *     otherwise it would quietly put everything back. A push the server
 *     rejects as `wiped` means the same.
 *
 * ## First contact
 *
 * A phone that has never pulled from this server does not know the account's
 * epoch, and a push stamped with a guess could be rejected as `wiped` — which
 * would delete a standalone phone's only copy of its data on first link. So
 * the first sync pulls before it pushes, learning the epoch, and then pushes.
 *
 * ## The blob queue
 *
 * Not stored anywhere, because it does not need to be: it is "every live
 * `fit_file` row whose file is not in the [BlobStore]", recomputed each run.
 * A download interrupted halfway was never put, so it is simply still
 * missing. That is what makes it resumable without a queue to corrupt.
 */
class ReplicaSyncEngine(
    private val store: ReplicaStore,
    private val transport: SyncTransport,
    private val blobs: BlobStore? = null,
    private val config: Config = Config(),
    /**
     * Told how far each step has got, for the "Syncing 412 of 2,994" a person
     * watches during a first sync or a long catch-up. Called on the syncing
     * coroutine; cheap, so it is called often.
     */
    private val onProgress: (SyncProgress) -> Unit = {},
) {
    data class Config(
        /** Changes per push request — small enough to finish over cellular. */
        val pushBatch: Long = 200,
        /** Push requests per run; bounds a phone with a vast backlog, not a normal one. */
        val maxPushBatches: Int = 50,
        val pullLimit: Int = PULL_DEFAULT_LIMIT,
        /** 200 x 500 rows is far beyond any real account; this bounds a broken server. */
        val maxPages: Int = 200,
        /**
         * FIT files per run. Five years of one person's files is under 10 MB,
         * so this bounds a broken loop rather than a real history. The caller
         * decides *whether* to fetch at all — Wi-Fi, charging — by passing a
         * [BlobStore] or not.
         */
        val maxBlobs: Int = 5000,
    )

    data class Report(
        val pushed: Int = 0,
        val pulled: Int = 0,
        val pages: Int = 0,
        val blobsFetched: Int = 0,
        /** Files whose bytes did not match their hash; left missing, retried next run. */
        val blobsCorrupt: Int = 0,
        /** The server was recreated and was given this phone's data back. */
        val newServer: Boolean = false,
        /** The account was wiped on purpose, and this phone's copy with it. */
        val wiped: Boolean = false,
        /** A bound stopped this run early. Not an error; the next run carries on. */
        val truncated: Boolean = false,
        /** Changes the server refused for a clock too far ahead — still queued. */
        val skewed: Int = 0,
    )

    /**
     * Run a sync. Network and session exceptions propagate unchanged — the
     * caller (WorkManager) decides between retry and failure — and every step
     * that completed before one stays completed.
     */
    suspend fun sync(): Report {
        var report = Report()
        if (store.server() == null) report = pull(report)
        report = push(report)
        if (report.wiped) return fetchBlobs(pull(report, allowReset = false))
        report = pull(report)
        if (report.newServer) {
            // The reset marked everything dirty: give the new server its data
            // back now rather than a sync later, then read what it makes of it.
            report = push(report)
            report = pull(report, allowReset = false)
        }
        return fetchBlobs(report)
    }

    /**
     * Erase everything this phone holds for sync — rows, FIT files, binding.
     * The explicit "erase this phone's data" the UI offers when a different
     * account signs in; nothing calls it implicitly.
     */
    suspend fun erase() {
        store.erase()
        blobs?.clear()
    }

    private suspend fun push(start: Report): Report {
        var report = start
        val total = store.pendingCount().toInt()
        repeat(config.maxPushBatches) {
            val changes = store.dirtyChanges(config.pushBatch)
            if (changes.isEmpty()) return report
            onProgress(SyncProgress(SyncProgress.Step.Sending, report.pushed, total))
            val response = transport.push(PushRequest(store.node, store.currentClock(), changes, store.epoch()))
            if (response.results.any { it.reason == RejectReason.WIPED }) {
                store.wipeForEpoch(null)
                blobs?.clear()
                return report.copy(wiped = true)
            }
            store.receiveClock(response.clock)
            store.acknowledge(changes, response.results)
            val skewed = response.results.count { it.reason == RejectReason.CLOCK_SKEW }
            report = report.copy(pushed = report.pushed + changes.size - skewed, skewed = report.skewed + skewed)
            // Everything left is waiting on a clock; pushing again now would
            // send the same batch forever.
            if (skewed == changes.size) return report
        }
        return report.copy(truncated = store.pendingCount() > 0 || report.truncated)
    }

    /**
     * Walk the pull. [allowReset] is false on the second walk of a run: a
     * server that looks recreated or wiped twice in one sync is broken, and
     * reacting again would loop.
     */
    private suspend fun pull(start: Report, allowReset: Boolean = true): Report {
        var report = start
        var cursor = store.cursor()
        while (report.pages < config.maxPages) {
            val page = transport.pull(cursor, config.pullLimit)
            val identity = ServerIdentity(page.serverId, page.epoch)
            val known = store.server()
            when {
                known == null -> store.recordServer(identity)
                known.serverId != identity.serverId -> {
                    if (!allowReset) return report.copy(truncated = true)
                    store.resetForNewServer(identity)
                    return report.copy(newServer = true, pages = report.pages + 1)
                }
                identity.epoch > known.epoch -> {
                    if (!allowReset) return report.copy(truncated = true)
                    store.wipeForEpoch(identity)
                    blobs?.clear()
                    // This page was read from the old cursor; start again at 0.
                    return pull(report.copy(wiped = true, pages = report.pages + 1), allowReset = false)
                }
                // A lower epoch means the server was restored from a backup
                // older than a wipe. Nothing here can tell which copy is
                // wanted, so the phone takes the server's number and carries
                // on; its rows merge as usual.
                identity.epoch != known.epoch -> store.recordServer(identity)
            }
            store.applyPulled(page.changes, page.next)
            report = report.copy(pulled = report.pulled + page.changes.size, pages = report.pages + 1)
            // The server does not say how many rows remain, so this step
            // counts up with no total.
            onProgress(SyncProgress(SyncProgress.Step.Receiving, report.pulled, null))
            if (!page.hasMore) return report
            // A page that claims more but did not move the cursor would be
            // fetched again forever.
            if (page.next <= cursor) return report.copy(truncated = true)
            cursor = page.next
        }
        return report.copy(truncated = true)
    }

    private suspend fun fetchBlobs(start: Report): Report {
        val blobs = blobs ?: return start
        var report = start
        val wanted = store.rows("fit_file")
            .mapNotNull { (it.fields["sha256"] as? JsonPrimitive)?.contentOrNull }
            .filterNot { blobs.has(it) }
        for ((i, sha) in wanted.withIndex()) {
            onProgress(SyncProgress(SyncProgress.Step.Files, i, wanted.size))
            if (report.blobsFetched + report.blobsCorrupt >= config.maxBlobs) {
                return report.copy(truncated = true)
            }
            val bytes = transport.blob(sha)
            if (Digest.hex(Digest.sha256(bytes)) != sha) {
                // Truncated or tampered with in transit. Not stored, so it is
                // still missing and the next run asks again.
                report = report.copy(blobsCorrupt = report.blobsCorrupt + 1)
                continue
            }
            blobs.put(sha, bytes)
            report = report.copy(blobsFetched = report.blobsFetched + 1)
        }
        return report
    }
}

/** [SyncTransport] over the real server. */
class HttpSyncTransport(private val client: TracksClient) : SyncTransport {
    override suspend fun push(request: PushRequest): PushResponse =
        Wire.decodePushResponse(client.syncPush(Wire.encodePush(request)))

    override suspend fun pull(since: Long, limit: Int): PullPage =
        Wire.decodePull(client.syncPull(since, limit.coerceAtMost(PULL_MAX_LIMIT)))

    override suspend fun blob(sha256: String): ByteArray = client.syncBlob(sha256)

    /**
     * A pull from past the end of any sequence: no rows, just the identity
     * every pull response carries. Saves an endpoint of its own for two fields.
     */
    override suspend fun identify(): ServerIdentity =
        pull(Long.MAX_VALUE, 1).let { ServerIdentity(it.serverId, it.epoch) }
}

/**
 * How far a sync has got. [total] is null for a step that cannot know its
 * size in advance (a pull pages until the server says it is done).
 */
data class SyncProgress(val step: Step, val done: Int, val total: Int?) {
    enum class Step { Sending, Receiving, Files, Uploading }

    /** 0..1, or null when there is no total to measure against. */
    val fraction: Float? get() = total?.takeIf { it > 0 }?.let { (done.toFloat() / it).coerceIn(0f, 1f) }
}
