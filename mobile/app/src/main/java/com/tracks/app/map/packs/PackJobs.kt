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
        val waitingOnServer = com.tracks.app.map.PendingRegions.requests(context).isNotEmpty()
        if (store.pending().isEmpty() && !waitingOnServer) return
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

    /** What the job's notification says: building on the server, or saving. */
    data class Progress(val title: String? = null, val text: String? = null, val fraction: Float? = null)

    fun notification(context: Context, done: Long, total: Long) =
        notification(context, Progress(
            text = if (total > 0) "%.2f of %.2f GB".format(done / 1e9, total / 1e9) else null,
            fraction = if (total > 0) done.toFloat() / total else null,
        ))

    fun notification(context: Context, p: Progress) = run {
        ensureChannel(context)
        val pct = ((p.fraction ?: 0f) * 100).toInt().coerceIn(0, 100)
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(p.title ?: context.getString(R.string.map_download_title))
            .setContentText(p.text ?: context.getString(R.string.map_download_starting))
            .setProgress(100, pct, p.fraction == null)
            // Tapping it opens the app, where the download is listed.
            .setContentIntent(android.app.PendingIntent.getActivity(
                context, 0, context.packageManager.getLaunchIntentForPackage(context.packageName),
                android.app.PendingIntent.FLAG_IMMUTABLE))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    internal suspend fun work(context: Context, notify: (Progress) -> Unit): Boolean {
        val container = (context.applicationContext as TracksApplication).container
        awaitServerBuilds(container, notify)
        val total = container.packs.state.value.pendingBytes
        var last = 0L
        val finished = container.packDownloader.run { _, _ ->
            val now = System.currentTimeMillis()
            if (now - last > 1500) {
                last = now
                val s = container.packs.state.value
                val done = total - s.pendingBytes
                notify(Progress(
                    text = if (total > 0) "%.2f of %.2f GB".format(done / 1e9, total / 1e9) else null,
                    fraction = if (total > 0) done.toFloat() / total else null,
                ))
            }
        }
        fetchExtras(container)
        return finished
    }

    /**
     * Wait for areas this phone asked the server to build, then save them.
     *
     * It used to be the downloads sheet that waited, in a loop that died with
     * the app — so closing Tracks during the server's build (minutes, often)
     * left nothing waiting and nothing on screen until it was opened again.
     * Now the job waits, saying how far the server has got in its
     * notification, and queues the packs the moment the build is done.
     */
    private suspend fun awaitServerBuilds(container: com.tracks.app.AppContainer, notify: (Progress) -> Unit) {
        val ctx = container.appContext
        while (true) {
            val requests = com.tracks.app.map.PendingRegions.requests(ctx)
            if (requests.isEmpty()) return
            val regions = runCatching { container.client().mapRegions() }.getOrNull()
            if (regions == null) {
                kotlinx.coroutines.delay(15_000)
                continue
            }
            val byId = regions.associateBy { it.id }
            var building: com.tracks.core.api.MapRegion? = null
            for ((id, req) in requests) {
                val r = byId[id]
                when {
                    r == null || r.isFailed -> com.tracks.app.map.PendingRegions.settled(ctx, id)
                    r.isReady -> PackSaver.save(container, req.name, req.bbox, id)
                    else -> if (building == null) building = r
                }
            }
            building?.let { r ->
                notify(Progress(
                    title = "Building ${requests[r.id]?.name ?: r.name} on the server",
                    text = r.detail ?: "Waiting to start…",
                    fraction = if (r.status == "queued") null else (r.progress / 100.0).toFloat().coerceIn(0f, 1f),
                ))
                kotlinx.coroutines.delay(5_000)
            }
        }
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
            setNotification(params, PackJobs.NOTIFICATION, PackJobs.notification(this, PackJobs.Progress()),
                            JOB_END_NOTIFICATION_POLICY_REMOVE)
        }
        job = scope.launch {
            val finished = runCatching {
                PackJobs.work(this@PackJobService) { p ->
                    if (Build.VERSION.SDK_INT >= 34) {
                        setNotification(params, PackJobs.NOTIFICATION, PackJobs.notification(this@PackJobService, p),
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
        (applicationContext as TracksApplication).container.packs.pending().isNotEmpty() ||
            com.tracks.app.map.PendingRegions.requests(applicationContext).isNotEmpty()
}

/** The path below Android 14, and for background updates: see [PackJobs]. */
class PackWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        setForeground(info(PackJobs.Progress()))
        val finished = PackJobs.work(applicationContext) { p -> setForegroundAsync(info(p)) }
        return if (finished) Result.success() else Result.retry()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = info(PackJobs.Progress())

    private fun info(p: PackJobs.Progress) = ForegroundInfo(
        PackJobs.NOTIFICATION, PackJobs.notification(applicationContext, p),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
}
