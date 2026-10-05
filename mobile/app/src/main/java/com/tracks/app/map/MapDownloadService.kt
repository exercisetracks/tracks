// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.map

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.tracks.app.MainActivity
import com.tracks.app.TracksApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps a map download alive while the phone is in somebody's pocket.
 *
 * ## Why a service for a download this app does not run
 *
 * MapLibre owns the transfer — it has its own thread, its own queue and its own
 * retries — so nothing here moves a byte. What it does is keep the *process*
 * alive, which is the one thing MapLibre cannot do for itself.
 *
 * An area worth carrying is tens of thousands of tile requests, which is tens
 * of minutes on anything but a good connection. Nobody watches that. They lock
 * the phone and put it away, and a backgrounded app with no foreground
 * component is exactly what Android kills first under memory pressure — at
 * which point the download stops mid-region, silently, with the sheet still
 * describing it as in progress. That is the failure this exists to prevent, and
 * it is not hypothetical: it is what "the download failed if I did anything
 * else" was.
 *
 * The `dataSync` type is the one Android provides for this, and the price it
 * asks is the notification below: visible, non-dismissible while it runs, and
 * saying which area and how far along.
 *
 * ## Why it also resumes
 *
 * Because it is the one place that always runs when a download should be
 * running. Anything MapLibre left inactive — killed process, reboot, a stop the
 * user never asked for — is set going again on start, and the service stops
 * itself once there is nothing left to keep alive.
 */
class MapDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(getString(com.tracks.app.R.string.map_download_starting), null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        // A second start while one is already watching changes nothing: there is
        // a single set of regions and a single notification describing it.
        if (watcher?.isActive == true) return START_NOT_STICKY

        watcher = scope.launch { watch() }

        // Not STICKY. A restart with a null intent would resume downloads with
        // no user anywhere near the app, which is the opposite of the deal a
        // foreground service makes; the next time the map is opened resumes
        // them anyway.
        return START_NOT_STICKY
    }

    private suspend fun watch() {
        val tiles = (applicationContext as TracksApplication).container.offlineTiles()
        runCatching { tiles.resumeIncomplete() }
            .onFailure { Log.w(TAG, "could not resume offline regions", it) }

        var lastBytes = -1L
        var quietFor = 0L
        while (true) {
            val stored = runCatching { tiles.stored() }.getOrDefault(emptyList())
            val unfinished = stored.filter { !it.complete }
            if (unfinished.isEmpty()) {
                Log.i(TAG, "every area is stored — standing down")
                break
            }

            val bytes = unfinished.sumOf { it.bytes }
            // A download that has not moved a byte in a quarter of an hour is
            // not downloading. Rather than hold a foreground service open
            // against an unreachable server for the rest of the day — which is
            // a real cost to the user's battery and a budget Android meters —
            // it stands down and lets the next visit to the map pick it up.
            quietFor = if (bytes == lastBytes) quietFor + POLL_MS else 0
            lastBytes = bytes
            if (quietFor >= STALL_MS) {
                Log.i(TAG, "no progress in ${STALL_MS / 60_000} minutes — standing down")
                break
            }

            update(unfinished)
            delay(POLL_MS)
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun update(unfinished: List<OfflineTiles.Stored>) {
        val leader = unfinished.maxByOrNull { it.fraction } ?: return
        val title = when (unfinished.size) {
            1 -> leader.metadata.name
            else -> getString(com.tracks.app.R.string.map_download_areas, unfinished.size)
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, notification(title, leader.fraction))
    }

    private fun notification(title: String, fraction: Float?): android.app.Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(com.tracks.app.R.string.map_download_title))
            .setContentText(title)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply {
                if (fraction != null) {
                    setProgress(100, (fraction * 100).toInt().coerceIn(0, 100), false)
                } else {
                    setProgress(0, 0, true)
                }
            }
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "TracksMapDownload"
        private const val CHANNEL_ID = "map_downloads"
        private const val NOTIFICATION_ID = 4301

        /** Often enough for a bar to move, rarely enough to cost nothing. */
        private const val POLL_MS = 4000L

        /** How long a download may make no progress before this gives up on it. */
        private const val STALL_MS = 15 * 60 * 1000L

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context, Intent(context, MapDownloadService::class.java)
                )
            }.onFailure {
                // Starting a foreground service from the background throws on
                // Android 12+. Every path here is user-initiated from a visible
                // screen, so it should not happen — and if it does, the
                // download still runs, it simply loses its protection from
                // being killed. Not worth taking the map down for.
                Log.w(TAG, "could not start the map download service", it)
            }
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(com.tracks.app.R.string.map_download_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description =
                        context.getString(com.tracks.app.R.string.map_download_channel_description)
                    setShowBadge(false)
                }
            )
        }
    }
}
