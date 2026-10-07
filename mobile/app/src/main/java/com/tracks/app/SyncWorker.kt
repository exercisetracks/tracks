// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tracks.core.api.IncompatibleServerException
import com.tracks.core.api.NotAuthenticatedException
import com.tracks.core.api.VaultLockedException
import java.util.concurrent.TimeUnit

/**
 * Keeps this phone and its server in step without the user opening the app.
 *
 * WorkManager rather than a service or a bare coroutine, because Doze and App
 * Standby will kill anything else — and because a phone in a pocket on a
 * mountain is exactly the situation this app exists for, so work that only runs
 * while the screen is on would miss the point entirely.
 *
 * ## Why the results are what they are
 *
 * `Result.retry()` and `Result.failure()` are not interchangeable here, and
 * getting them wrong is how an app ends up hammering a server it cannot use:
 *
 * - **Vault locked** → failure, not retry. The vault can only be reopened by a
 *   device key or a password. If the client had a key it would already have
 *   used it (see TracksClient), so retrying on a timer would call an endpoint
 *   that is guaranteed to 401 until a human intervenes.
 * - **Not authenticated** → failure. Same reasoning: no amount of waiting
 *   produces credentials.
 * - **Incompatible server** → failure. Retrying cannot make the app newer.
 * - **Anything else** → retry with backoff. Network failures are the expected
 *   case out here, and they are exactly what backoff is for.
 *
 * ## One sync, both directions
 *
 * [AppContainer.syncWithServer] pushes this phone's unsynced edits, pulls
 * everyone else's, fetches FIT files it lacks (on an unmetered network only),
 * and uploads files the server lacks. A standalone phone has no server, and
 * that is not a failure: the worker simply succeeds with nothing to do.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as TracksApplication).container

        if (container.serverUrl.value.isBlank()) {
            // Standalone, or not set up yet: nothing to sync with.
            return Result.success()
        }
        if (!container.tokens.load().isAuthenticated) {
            return Result.failure()
        }

        return try {
            // FIT history is the bulk of a first sync and none of it is urgent,
            // so it only moves on an unmetered network; edits always do.
            val cm = applicationContext.getSystemService(android.net.ConnectivityManager::class.java)
            val unmetered = cm?.isActiveNetworkMetered == false
            val report = container.syncWithServer(withHistory = unmetered)
            if (report != null) {
                Log.i(
                    TAG,
                    "sync: ${report.pushed} pushed, ${report.pulled} pulled, ${report.blobsFetched} file(s)" +
                        (if (report.newServer) ", new server" else "") +
                        (if (report.wiped) ", account wiped" else ""),
                )
            }
            // Tells the server which version this phone runs (the client sends
            // it on every request; /version is where it is recorded), so the
            // web's version panel is as current as the last sync. Best effort:
            // a server too old to have /version must not fail the sync.
            runCatching { container.client().versionStatus() }
            // A truncated walk means the page limit stopped it, not that
            // anything went wrong — succeed and let the next run continue from
            // the stored cursor.
            Result.success()
        } catch (e: VaultLockedException) {
            Log.w(TAG, "sync: vault locked; needs the user")
            Result.failure()
        } catch (e: NotAuthenticatedException) {
            Log.w(TAG, "sync: not authenticated")
            Result.failure()
        } catch (e: IncompatibleServerException) {
            Log.w(TAG, "sync: ${e.message}")
            Result.failure()
        } catch (e: Exception) {
            Log.w(TAG, "sync failed, will retry: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "TracksSync"
        private const val WORK_NAME = "tracks-delta-sync"

        /**
         * Schedule periodic sync.
         *
         * Six hours, not fifteen minutes. The data changes when a watch syncs,
         * which is a few times a day at most, and each wake-up costs radio time
         * — the one resource an expedition cannot spare. `KEEP` so re-running
         * this on every app start doesn't reset the schedule and effectively
         * prevent it from ever firing.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                // Never sync a phone that is nearly flat. The mirror is a
                // convenience; the battery may not be.
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * One sync as soon as there is a network — for edits made on this
         * phone (AppContainer pushes them a few seconds after the last one).
         * `KEEP`, so a burst of edits queues one run rather than a run each.
         */
        fun syncSoon(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$WORK_NAME-edits",
                androidx.work.ExistingWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
