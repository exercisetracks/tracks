// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.tracks.app.MainActivity
import com.tracks.app.R
import com.tracks.app.TracksApplication
import java.text.NumberFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/** How far a backup has got: files written, of [total] — null until the list is known. */
data class BackupProgress(val done: Int, val total: Int?) {
    val fraction: Float? get() = total?.takeIf { it > 0 }?.let { (done.toFloat() / it).coerceIn(0f, 1f) }
}

/** What the pill and the notification say. */
fun backupLabel(p: BackupProgress): String {
    val n = NumberFormat.getIntegerInstance()
    return if (p.total == null) "Preparing backup" else "Backing up ${n.format(p.done)} of ${n.format(p.total)} files"
}

/**
 * Keeps a backup running after the person leaves the app.
 *
 * The backup itself runs in the app container's own scope (see
 * `AppContainer.startBackup`); what this adds is the two things that scope
 * cannot give itself. A foreground service, so a backgrounded process is not
 * reclaimed partway through a few hundred megabytes — `dataSync` is the type
 * Android provides for exactly this, and its price is the notification, which
 * here earns its place by carrying the progress. And a partial wake lock,
 * because a foreground service does not stop the CPU sleeping once the screen
 * locks, and a backup started just before pocketing the phone would otherwise
 * crawl along only when something else woke it.
 *
 * It watches `backupProgress` and stands down when that goes back to null,
 * leaving a notification saying how it ended — someone who left the app has
 * no other way to learn whether their backup exists.
 */
class BackupWriteService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            PROGRESS_ID,
            progressNotification(BackupProgress(0, null)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (watcher?.isActive == true) return START_NOT_STICKY

        // Bounded, so a bug that never clears the progress cannot hold the CPU
        // awake indefinitely; an hour is several times the largest backup.
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tracks:backup")
            ?.apply { setReferenceCounted(false); acquire(WAKE_LOCK_MS) }

        val container = (applicationContext as TracksApplication).container
        watcher = scope.launch {
            val manager = getSystemService(NotificationManager::class.java)
            container.backupProgress
                .takeWhile { it != null }
                .collect { p -> manager?.notify(PROGRESS_ID, progressNotification(p!!)) }
            // takeWhile ends on null, which is set only after the result.
            val result = container.backupResult.first { it != null }
            manager?.notify(RESULT_ID, resultNotification(result!!))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        // Not STICKY: a restart after the process died has no backup to resume —
        // the job died with it, and its half-written file was never finished.
        return START_NOT_STICKY
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_BACKUP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progressNotification(p: BackupProgress): android.app.Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.backup_progress_title))
            .setContentText(backupLabel(p))
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply {
                val fraction = p.fraction
                if (fraction != null) setProgress(100, (fraction * 100).toInt(), false) else setProgress(0, 0, true)
            }
            .build()

    private fun resultNotification(text: String): android.app.Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(getString(R.string.backup_progress_title))
            .setContentText(text)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "TracksBackup"
        private const val CHANNEL_ID = "backup_progress"
        private const val PROGRESS_ID = 4401
        private const val RESULT_ID = 4402
        private const val WAKE_LOCK_MS = 60 * 60 * 1000L

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, BackupWriteService::class.java))
            }.onFailure {
                // Only from the background on Android 12+, and a backup is
                // started from a visible screen. If it happens anyway the
                // backup still runs — it just loses its protection.
                Log.w(TAG, "could not start the backup service", it)
            }
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.backup_progress_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.backup_progress_channel_description)
                    setShowBadge(false)
                },
            )
        }
    }
}
