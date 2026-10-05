// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.device

import android.content.Context
import android.util.Log
import com.tracks.app.AppContainer
import com.tracks.app.WatchSyncScheduler
import com.tracks.core.sync.SmartSyncPolicy
import com.tracks.device.ConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.TimeZone

/**
 * Syncs the watch at the moments a person notices it, instead of waiting for
 * the hourly worker: the first time the app is opened each morning, a little
 * while after the training plan changes, and when the watch comes back into
 * range after long enough away to have recorded something.
 *
 * *When* is decided by [SmartSyncPolicy], which is plain arithmetic and is
 * tested without a phone. This class is only the Android half: what counts as
 * "opened", how to reach the watch from the current process state, and what
 * to remember between runs.
 *
 * Everything here goes through [WatchSyncRunner], the same sequence as the
 * Sync button and the hourly worker, so these triggers cannot drift from the
 * manual path and cannot talk to the watch at the same time as either — the
 * runner's mutex answers "already running" rather than queueing.
 */
class SmartWatchSync(
    private val container: AppContainer,
    private val scope: CoroutineScope,
) {
    private val context: Context get() = container.appContext

    private val prefs by lazy {
        context.getSharedPreferences("tracks_smart_sync", Context.MODE_PRIVATE)
    }

    /** Whether an Activity is on screen. Decides how the watch may be reached. */
    @Volatile
    private var visible = false

    @Volatile
    private var lastMorningAttemptMs = 0L

    /** Watch the plan. Once, from `Application.onCreate`. */
    fun start() {
        scope.launch {
            SmartSyncPolicy.planSettled(container.sources.version, ::planFingerprint)
                .collect { onPlanSettled() }
        }
        scope.launch {
            val linkUp = container.watch.connection.mapNotNull {
                when (it) {
                    is ConnectionState.Connected -> true
                    ConnectionState.Disconnected, is ConnectionState.Failed -> false
                    // Mid-attempt: neither a return nor a departure.
                    ConnectionState.Connecting, is ConnectionState.Initializing -> null
                }
            }
            SmartSyncPolicy.returnedAfterAbsence(linkUp, System::currentTimeMillis)
                .collect { onWatchReturned() }
        }
    }

    // ── The watch coming back ────────────────────────────────────────────────

    /**
     * Run in this process rather than through [WatchSyncScheduler.syncSoon].
     *
     * A reconnect with nobody looking only happens because
     * [com.tracks.app.WatchLinkService] is holding the link, and that service
     * is foreground, so this process is already allowed to run and the
     * connection the sync needs is already open. A WorkManager job would add
     * nothing but delay — and under Doze, which is exactly the state of a phone
     * left on a counter during a run, a job can wait for the next maintenance
     * window while the watch sits connected with the activity on it.
     */
    private suspend fun onWatchReturned() {
        Log.i(TAG, "watch back in range after an absence; syncing")
        val outcome = syncIfPossible() ?: return
        if (outcome.alreadyRunning) Log.i(TAG, "a sync was already running; leaving it to that one")
    }

    /**
     * The app came to, or left, the foreground. Called from `MainActivity`'s
     * `onStart` and `onStop`.
     *
     * `onStart` and not `onResume`: resume fires again after every dialog and
     * permission prompt, and this must not get a chance to run more than once
     * per real visit. It still fires more than once — rotation recreates the
     * Activity — which is what the once-a-day record and [MORNING_RETRY_MS] are
     * for.
     */
    fun appVisible(isVisible: Boolean) {
        visible = isVisible
        if (isVisible) scope.launch { morningSyncIfDue() }
    }

    // ── The first open of the morning ────────────────────────────────────────

    private suspend fun morningSyncIfDue() {
        val now = System.currentTimeMillis()
        val offset = TimeZone.getDefault().getOffset(now) / 1000
        val last = prefs.getLong(KEY_MORNING_DAY, NEVER).takeIf { it != NEVER }
        if (!SmartSyncPolicy.morningSyncDue(now, offset, last)) return

        // A failed attempt (watch out of range, asleep in a drawer) must not be
        // retried on every screen rotation — each try costs a BLE scan — but it
        // must be retried on the next real open, because the day is only
        // recorded when a pull actually happened.
        if (now - lastMorningAttemptMs < MORNING_RETRY_MS) return
        lastMorningAttemptMs = now

        val outcome = syncIfPossible() ?: return
        if (outcome.phases.any { it.phase == WatchSyncRunner.Phase.PULL && it.status == WatchSyncRunner.Status.RAN }) {
            // Recorded only for a sync that got as far as reading the watch. A
            // morning sync that never connected has not fetched last night's
            // sleep, and calling the day done would leave the user waiting for
            // the hourly worker — the thing this exists to beat.
            prefs.edit().putLong(KEY_MORNING_DAY, SmartSyncPolicy.syncDay(now, offset)).apply()
        } else {
            Log.i(TAG, "morning sync did not reach the watch; will try on the next open")
        }
    }

    // ── A plan that has stopped changing ─────────────────────────────────────

    /**
     * What the watch's calendar is built from: the planned workouts it covers.
     *
     * Hashed through the model rather than the raw rows, so the `watch_*`
     * bookkeeping a push writes back (see `WatchManager.pushSchedule`) is not
     * part of it. If it were, every push would change the fingerprint and
     * schedule another sync, which would push again — the loop this avoids.
     */
    private suspend fun planFingerprint(): Int {
        val today = LocalDate.now()
        return container.sources.plannedWorkouts(
            today.toString(),
            today.plusDays(WatchManager.SCHEDULE_CACHE_DAYS.toLong()).toString(),
        ).hashCode()
    }

    private suspend fun onPlanSettled() {
        if (container.watch.paired.first() == null) return
        if (!visible) {
            // The plan changed with nobody looking — a pull from the server in
            // the background. A service cannot be started from here; the worker
            // can.
            WatchSyncScheduler.syncSoon(context)
            return
        }
        // A sync already in flight may have read the plan before this edit, and
        // "already running" would otherwise end the matter: the edit would sit
        // unsent until the hourly worker. Try again once it has had time to
        // finish.
        repeat(PLAN_SYNC_TRIES) { attempt ->
            val outcome = syncIfPossible() ?: return
            if (!outcome.alreadyRunning) return
            Log.i(TAG, "plan changed while a sync was running; retrying (${attempt + 1}/$PLAN_SYNC_TRIES)")
            delay(PLAN_RETRY_MS)
        }
    }

    // ── Shared ───────────────────────────────────────────────────────────────

    /**
     * Run a sync now, or return null when there is nothing to run one against.
     *
     * Nothing paired and no Bluetooth permission are both "not yet", not
     * failures: a person who has no watch must never see a prompt or a
     * notification from a feature they did not turn on, and the permission
     * prompt belongs to the button that explains why it is needed.
     */
    private suspend fun syncIfPossible(): WatchSyncRunner.Outcome? {
        if (container.watch.paired.first() == null) return null
        if (!BluetoothPermissions.granted(context)) return null
        return try {
            container.watchSync.runOnceDetached()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "unprompted watch sync failed", e)
            null
        }
    }

    private companion object {
        const val TAG = "TracksSmartSync"
        const val KEY_MORNING_DAY = "morning_sync_day"
        const val NEVER = Long.MIN_VALUE
        const val MORNING_RETRY_MS = 10 * 60_000L
        const val PLAN_SYNC_TRIES = 3
        const val PLAN_RETRY_MS = 45_000L
    }
}
