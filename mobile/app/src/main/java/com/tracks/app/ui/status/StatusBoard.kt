// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.status

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Everything the app is doing out of sight, and what came of it — the one
 * source [StatusHost] draws from. See docs/mobile-status-ui.md for the rules.
 *
 * This exists because each feature had grown its own way of saying so: a pill
 * for server sync, another for the watch, another for backup, a banner for map
 * downloads, a toast for the map's receipts and a Material snackbar for
 * everything else — drawn in two columns that overlapped, so one watch sync
 * could show up three times at once. Here a running operation has one slot per
 * [StatusSource], and a result from a source settles that source's slot, so
 * the same operation cannot be on screen twice.
 */
class StatusBoard {

    private val _jobs = MutableStateFlow<Map<StatusSource, StatusJob>>(emptyMap())

    /** What is running now, at most one per source. */
    val jobs: StateFlow<Map<StatusSource, StatusJob>> = _jobs

    private val _notes = MutableStateFlow<List<StatusNote>>(emptyList())

    /** Results, newest first: failures until dismissed, successes briefly. */
    val notes: StateFlow<List<StatusNote>> = _notes

    private var nextId = 1L

    /**
     * How tall [StatusHost] is drawn right now, so a screen that puts its own
     * chip at the top (the map's "N marked") can sit below it, not under it.
     */
    var hostHeightPx by androidx.compose.runtime.mutableIntStateOf(0)

    /** Set or clear (null) what [source] is doing. */
    fun setJob(source: StatusSource, job: StatusJob?) {
        _jobs.update { if (job == null) it - source else it + (source to job) }
    }

    /**
     * Report something. A message ending in "…" is work starting rather than a
     * result, and becomes [source]'s running job until its result arrives — so
     * "Sending “Loop” to the watch…" and the watch's own progress are one slot,
     * not two pills.
     *
     * A result from a source answers that source's earlier failures: a watch
     * sync that worked means "restart your watch" no longer applies.
     */
    fun post(text: String, source: StatusSource = StatusSource.guess(text), failed: Boolean = isFailure(text)) {
        val message = text.trim()
        if (message.isEmpty()) return
        if (message.endsWith("…")) {
            setJob(source, StatusJob(message, fraction = null, posted = true))
            return
        }
        // A result ends the "…" this source posted, never the progress a
        // runner is reporting: that one clears itself when the runner stops.
        _jobs.update { jobs -> if (jobs[source]?.posted == true) jobs - source else jobs }
        _notes.update { notes ->
            val kept = if (failed || source == StatusSource.App) notes
            else notes.filterNot { it.source == source && it.failed }
            // The same words again are the same news, not a second item.
            (listOf(StatusNote(nextId++, source, message, failed)) + kept.filterNot { it.text == message })
                .take(MAX_NOTES)
        }
    }

    fun dismiss(id: Long) {
        _notes.update { notes -> notes.filterNot { it.id == id } }
    }

    /** A "…" job that never heard back, cleared so it cannot spin forever. */
    fun expirePosted(source: StatusSource) {
        _jobs.update { jobs -> if (jobs[source]?.posted == true) jobs - source else jobs }
    }

    companion object {
        const val MAX_NOTES = 6

        /**
         * Whether a message reports a failure, read from its words because the
         * app's messages are plain strings from many places. The guide makes
         * this a writing rule rather than a guess: a failure starts with "Could
         * not" or names what went wrong, and a success never does.
         */
        fun isFailure(text: String): Boolean {
            val t = text.trim().lowercase().replace('’', '\'')
            return FAILURE_STARTS.any { t.startsWith(it) } || FAILURE_WORDS.any { it in t }
        }

        private val FAILURE_STARTS = listOf("could not", "couldn't", "cannot", "can't", "no watch", "not ")
        private val FAILURE_WORDS = listOf(
            "failed", "refused", "couldn't", "could not", "restart the watch", "restart your watch",
            "needs a connection", "unreachable", "went wrong", "error",
        )
    }
}

/** Who is doing the work: one running slot each, and the label in the expanded list. */
enum class StatusSource(val title: String) {
    Server("Server"),
    Watch("Watch"),
    Maps("Maps"),
    Backup("Backup"),
    /** Edits and imports made on this phone: results only, nothing to supersede. */
    App("Tracks");

    companion object {
        /** Which source a plain message is about, for callers that only have the words. */
        fun guess(text: String): StatusSource {
            val t = text.lowercase()
            return when {
                "watch" in t -> Watch
                "server" in t || "sync agent" in t || "signed out" in t -> Server
                else -> App
            }
        }
    }
}

/**
 * Work in progress. [fraction] is null while it cannot say how far along it
 * is. [posted] marks a job made from a "…" message rather than a runner's
 * progress, which a result from the same source ends.
 */
data class StatusJob(val label: String, val fraction: Float? = null, val posted: Boolean = false)

data class StatusNote(
    val id: Long,
    val source: StatusSource,
    val text: String,
    val failed: Boolean,
    val at: Long = System.currentTimeMillis(),
)
