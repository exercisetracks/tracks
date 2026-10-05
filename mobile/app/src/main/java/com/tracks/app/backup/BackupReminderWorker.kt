// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.backup

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tracks.app.MainActivity
import com.tracks.app.R
import com.tracks.app.TracksApplication
import com.tracks.app.ui.profile.backupOverdue
import java.util.concurrent.TimeUnit

/**
 * The backup nudge, as a notification — for a phone that is the only copy.
 *
 * The dashboard banner only reaches someone who opens the app; a standalone
 * phone that is carried for weeks and never opened still holds everything, and
 * losing it loses everything. So once a day this checks the banner's own rule
 * ([backupOverdue]: no server linked and no backup in 14 days) and, at most
 * once a week, says so.
 *
 * Its own channel, so it can be switched off in the system settings without
 * losing medication reminders or watch sync notices — a nudge nobody asked for
 * is only tolerable when it is easy to silence.
 */
class BackupReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as TracksApplication).container
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastNotified = prefs.getLong(KEY_LAST_NOTIFIED, 0L).takeIf { it > 0 }
        if (!shouldNotify(container.isLinked(), container.lastBackupAt.value, lastNotified, now)) {
            return Result.success()
        }
        if (post(applicationContext)) prefs.edit().putLong(KEY_LAST_NOTIFIED, now).apply()
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "tracks-backup-reminder"
        private const val CHANNEL_ID = "backup_reminder"
        private const val NOTIFICATION_ID = 7_401
        private const val PREFS = "tracks_backup_reminder"
        private const val KEY_LAST_NOTIFIED = "last_notified_at"
        private const val WEEK_MS = 7 * 86_400_000L

        /**
         * Whether to post now: overdue by the banner's rule, and nothing posted
         * in the last week. Pure, so the schedule can be tested without a phone.
         */
        fun shouldNotify(linked: Boolean, lastBackupAtMs: Long?, lastNotifiedAtMs: Long?, nowMs: Long): Boolean =
            backupOverdue(linked, lastBackupAtMs, nowMs) &&
                (lastNotifiedAtMs == null || nowMs - lastNotifiedAtMs >= WEEK_MS)

        /** Daily. `KEEP`, like the sync worker, so each launch does not reset it. */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BackupReminderWorker>(1, TimeUnit.DAYS).build(),
            )
        }

        private fun post(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.backup_reminder_channel_name),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply { description = context.getString(R.string.backup_reminder_channel_description) },
                )
            }
            val open = PendingIntent.getActivity(
                context, NOTIFICATION_ID,
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_BACKUP)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(context.getString(R.string.backup_reminder_title))
                .setContentText(context.getString(R.string.backup_reminder_text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            return true
        }
    }
}
