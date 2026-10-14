// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.map.packs

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tracks.app.R
import com.tracks.app.TracksApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Running [PackDownloader] where Android lets a large download run.
 *
 * - **Android 14+: a user-initiated data transfer job.** The platform's API
 *   for exactly this — a download the person started and is waiting for. No
 *   six-hour cap (a `dataSync` foreground service gets six hours a day on
 *   Android 15, then a crash), not subject to App Standby quotas, rescheduled
 *   by the system when stopped, and run under the network constraint chosen:
 *   Wi-Fi only by default. It must be scheduled while the app is on screen,
 *   which is when a person taps Download.
 * - **Below 14, or scheduled from the background** (an update check): a
 *   WorkManager worker running in the foreground as `dataSync`, under the same
 *   network constraint.
 *
 * Either way the work is the same call, and the downloader's mutex keeps the
 * two from fetching the same pack if both start.
 */
object PackJobs {

    const val CHANNEL_ID = "map_downloads"
    private const val JOB_ID = 0x7061636B   // "pack"
    private const val NOTIFICATION_ID = 4107
    private const val WORK_NAME = "map-packs"
    private const val TAG = "TracksPacks"

    fun schedule(context: Context, userInitiated: Boolean = true) {
        val store = (context.applicationContext as TracksApplication).container.packs
        val wifiOnly = store.state.value.settings.wifiOnly
        if (store.pending().isEmpty()) return
        if (userInitiated && Build.VERSION.SDK_INT >= 34) {
            val ok = runCatching {
                val info = JobInfo.Builder(JOB_ID, ComponentName(context, PackJobService::class.java))
                    .setUserInitiated(true)
                    .setRequiredNetworkType(if (wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
                    .setEstimatedNetworkBytes(store.state.value.pendingBytes, 0)
                    .build()
                context.getSystemService(JobScheduler::class.java).schedule(info) == JobScheduler.RESULT_SUCCESS
            }.getOrElse { e ->
                Log.w(TAG, "User-initiated job refused; using WorkManager", e)
                false
            }
            if (ok) return
        }
        val request = OneTimeWorkRequestBuilder<PackWorker>()
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.map_download_channel), NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = context.getString(R.string.map_download_channel_description)
                    setShowBadge(false)
                }
        )
    }

    fun notification(context: Context, done: Long, total: Long) = run {
        ensureChannel(context)
        val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.map_download_title))
            .setContentText(if (total > 0) "%.1f of %.1f GB".format(done / 1e9, total / 1e9) else context.getString(R.string.map_download_starting))
            .setProgress(100, pct, total <= 0)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    internal suspend fun work(context: Context, notify: (Long, Long) -> Unit): Boolean {
        val container = (context.applicationContext as TracksApplication).container
        val total = container.packs.state.value.pendingBytes
        var last = 0L
        val finished = container.packDownloader.run { _, _ ->
            val now = System.currentTimeMillis()
            if (now - last > 1500) {
                last = now
                val s = container.packs.state.value
                notify(total - s.pendingBytes, total)
            }
        }
        fetchExtras(container)
        return finished
    }

    /**
     * Routing data and the offline search index for every area whose packs are
     * all in. Run here, by the job, rather than by the screen that started
     * the download: that screen is usually closed long before a state's packs
     * finish, and these used to be skipped whenever it was (audit F6).
     * Best effort: a failure leaves a good offline map that routes and
     * searches through the server instead, and is tried again next run.
     */
    private suspend fun fetchExtras(container: com.tracks.app.AppContainer) {
        val s = container.packs.state.value
        for (area in s.areas.filter { !it.extras }) {
            if (!s.packsFor(area).all { it.current }) continue
            val (w, so, e, n) = area.bbox
            val ok = runCatching {
                container.offlineRoutingData().download(container.client(), w, so, e, n).collect { }
                container.offlinePoiData().download(container.client(), w, so, e, n)
            }.isSuccess
            if (ok) container.packs.markExtras(area.id)
        }
    }

    const val NOTIFICATION = NOTIFICATION_ID
}

/** The Android 14+ path: see [PackJobs]. */
class PackJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT >= 34) {
            setNotification(params, PackJobs.NOTIFICATION, PackJobs.notification(this, 0, 0),
                            JOB_END_NOTIFICATION_POLICY_REMOVE)
        }
        job = scope.launch {
            val finished = runCatching {
                PackJobs.work(this@PackJobService) { done, total ->
                    if (Build.VERSION.SDK_INT >= 34) {
                        setNotification(params, PackJobs.NOTIFICATION, PackJobs.notification(this@PackJobService, done, total),
                                        JOB_END_NOTIFICATION_POLICY_REMOVE)
                    }
                }
            }.getOrDefault(false)
            jobFinished(params, !finished && stillWanted())
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        // Network lost, or the person stopped it from Task Manager: the parts
        // stay, and the system runs the job again when it can.
        return stillWanted()
    }

    private fun stillWanted() =
        (applicationContext as TracksApplication).container.packs.pending().isNotEmpty()
}

/** The path below Android 14, and for background updates: see [PackJobs]. */
class PackWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        setForeground(info(0, 0))
        val finished = PackJobs.work(applicationContext) { done, total ->
            setForegroundAsync(info(done, total))
        }
        return if (finished) Result.success() else Result.retry()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = info(0, 0)

    private fun info(done: Long, total: Long) = ForegroundInfo(
        PackJobs.NOTIFICATION, PackJobs.notification(applicationContext, done, total),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
}
