// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Decides *when* to attempt a watch sync; [WatchSyncService] does the syncing.
 *
 * The split matters. WorkManager gives up on a worker after a bounded
 * execution window — fine for [SyncWorker]'s HTTP round trip, wrong for a BLE
 * transfer that has taken up to ten minutes on real hardware. So this worker's
 * entire job is to start the foreground service and return immediately; the
 * service then runs under its own, effectively unbounded lifecycle.
 *
 * Deliberately no `setRequiredNetworkType` constraint, unlike [SyncWorker].
 * Pulling from the watch needs no network at all — it is the expedition case
 * this app exists for — and gating the *attempt* on connectivity would skip
 * pulling data off the watch on exactly the trip where there is nothing but
 * the watch to pull from. [WatchSyncRunner] already degrades gracefully when
 * the upload half has nothing to talk to: files land on the phone and wait
 * for the next sync that does have signal.
 */
class WatchSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as TracksApplication).container
        // Checked here rather than left to the service, so that a phone with
        // nothing paired never shows the brief "connecting…" notification
        // this would otherwise produce every hour for no reason.
        if (container.watch.paired.first() == null) {
            return Result.success()
        }
        WatchSyncService.start(applicationContext)
        return Result.success()
    }
}

object WatchSyncScheduler {
    private const val WORK_NAME = "tracks-watch-sync"
    private const val NOW_WORK_NAME = "tracks-watch-sync-now"

    /**
     * Hourly, not on the six-hour cadence [SyncWorker] uses for the server
     * delta. The two are not the same kind of work: a server delta is cheap
     * to check and cheap to skip, while attempting a watch connection costs
     * real radio time whether or not the watch answers — but data sitting
     * unsynced on the watch is also the thing an expedition sync exists to
     * minimise, so this leans more frequent than the server sync while still
     * far short of anything that would look like scanning.
     *
     * `requiresBatteryNotLow` is the only hard constraint, matching
     * [SyncWorker] — a watch sync is a convenience, not something worth
     * spending the last of a phone's charge on.
     */
    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<WatchSyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * One sync as soon as the constraints allow, for [SmartWatchSync] when the
     * plan changed while the app was not on screen (a server pull in the
     * background, say).
     *
     * Through the worker rather than starting [WatchSyncService] directly,
     * because Android 12+ refuses a foreground-service start from a process
     * that is not visible — see the note on `MainActivity.onStart`. The worker
     * is the route the hourly sync already takes from the same state. `KEEP`:
     * a second request while one is queued would only ask for the same sync.
     */
    fun syncSoon(context: Context) {
        val request = OneTimeWorkRequestBuilder<WatchSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW_WORK_NAME, ExistingWorkPolicy.KEEP, request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(NOW_WORK_NAME)
    }
}
