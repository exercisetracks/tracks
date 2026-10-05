// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.device

import android.util.Log
import com.tracks.device.ConnectionState
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlin.math.min
import kotlin.random.Random

/**
 * Keeps the watch connected.
 *
 * ## Why this has to exist
 *
 * Until now the app connected to the watch only in order to sync, and dropped
 * the link the moment the sync finished — [com.tracks.app.WatchSyncService]
 * disconnects in its `finally`. With a six-hour sync interval that meant the
 * watch was reachable for a couple of minutes, four times a day.
 *
 * Everything that is not a sync silently inherited that window. The phone feeds
 * are the obvious casualty: [WatchManager.attachFeeds] installs the
 * notification sink when a connection opens and removes it when the connection
 * closes, so notifications only ever reached the wrist if one happened to
 * arrive during a sync. That reads as "the notification relay is broken" and is
 * really "there was nothing connected to relay to".
 *
 * ## Why a loop rather than `autoConnect`
 *
 * `BluetoothDevice.connectGatt(context, autoConnect = true)` is the cheap,
 * OS-managed way to hold a link — the stack reconnects on its own when the
 * device comes back into range, with no scanning by us. The vendored
 * `BtLEQueue` passes `false`, with an upstream comment saying `true` caused too
 * many connection problems, and vendored code is not ours to edit (see
 * `device-garmin/SHIMS.md`). So reconnection lives here instead.
 *
 * ## The backoff is the battery story
 *
 * A watch out of range fails to connect immediately and would otherwise be
 * retried in a tight loop, which is the most expensive thing this app could
 * possibly do. Delays double from [MIN_BACKOFF_MS] to [MAX_BACKOFF_MS] and stay
 * there, so a watch left at home costs one attempt every five minutes rather
 * than thousands. Jitter spreads retries that would otherwise line up with
 * whatever woke them.
 *
 * A successful connection resets the backoff, because the next drop is a new
 * event and should be answered promptly rather than inheriting the patience
 * built up by the last outage.
 */
class WatchLink(
    private val connection: Flow<ConnectionState>,
    private val connect: suspend () -> Boolean,
    /** Overridden in tests so backoff can be asserted without real waiting. */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val jitter: (Long) -> Long = { Random.nextLong(it + 1) },
    /**
     * Emits when retrying now is worth more than waiting out the backoff —
     * Bluetooth coming back on. The backoff assumes nothing has changed since
     * the last failure; a radio that was off and is now on is exactly the
     * change it cannot see, and without this a person who turns Bluetooth
     * back on waits up to [MAX_BACKOFF_MS] for the watch to reappear.
     */
    private val wake: Flow<Unit> = emptyFlow(),
) {

    /**
     * Hold the connection until cancelled.
     *
     * Returns only if [connect] reports that nothing is paired, which is not a
     * failure to retry — there is genuinely nothing to connect to, and the
     * caller starts a new link when the user pairs a watch.
     */
    suspend fun keepConnected() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            // Suspends for as long as the link is up. `Initializing` counts as
            // up: the handshake is in progress and starting a second connection
            // underneath it is how you get two conversations on one link.
            connection.first { it.needsConnecting }

            val outcome = runCatching { connect() }
            val connected = outcome.getOrDefault(false)

            when {
                connected -> attempt = 0

                // `connect()` returning false without throwing means no watch is
                // paired. Retrying cannot change that.
                outcome.isSuccess -> {
                    Log.i(TAG, "no watch paired; link supervisor stopping")
                    return
                }

                else -> {
                    attempt++
                    val wait = backoffFor(attempt)
                    Log.d(TAG, "reconnect attempt $attempt failed; retrying in ${wait}ms")
                    if (sleepOrWake(wait)) {
                        Log.d(TAG, "woken early; retrying now with a fresh backoff")
                        attempt = 0
                    }
                }
            }
        }
    }

    /** Sleep [wait], or less if [wake] fires first; true when woken. */
    private suspend fun sleepOrWake(wait: Long): Boolean = coroutineScope {
        val slept = async { sleep(wait); false }
        // An empty flow (the default) ends without emitting; that must mean
        // "never woken", not an error.
        val woken = async { wake.firstOrNull() ?: awaitCancellation(); true }
        select { slept.onAwait { it }; woken.onAwait { it } }.also { coroutineContext.cancelChildren() }
    }

    private fun backoffFor(attempt: Int): Long {
        val exponential = MIN_BACKOFF_MS shl (attempt - 1).coerceAtMost(BACKOFF_SHIFT_CAP)
        val capped = min(exponential, MAX_BACKOFF_MS)
        return capped + jitter(capped / JITTER_FRACTION)
    }

    companion object {
        private const val TAG = "TracksWatchLink"

        internal const val MIN_BACKOFF_MS = 2_000L
        internal const val MAX_BACKOFF_MS = 300_000L

        /** Enough doublings to pass the cap; more would overflow the shift. */
        private const val BACKOFF_SHIFT_CAP = 8

        /** Jitter up to a quarter of the delay. */
        private const val JITTER_FRACTION = 4

        /**
         * Whether this state is one the supervisor should act on.
         *
         * `Failed` is included: it is a terminal report of one attempt, not a
         * reason to stop trying. A watch that was off the charger and out of
         * range fails exactly this way, and it is the case the supervisor
         * exists for.
         */
        internal val ConnectionState.needsConnecting: Boolean
            get() = this is ConnectionState.Disconnected || this is ConnectionState.Failed
    }
}
