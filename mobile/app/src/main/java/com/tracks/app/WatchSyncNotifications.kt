// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * The one notification a background watch sync is allowed to show.
 *
 * A foreground service must post a notification the instant it starts — that
 * is the trade Android makes for letting it run at all — but nothing about a
 * routine sync deserves a sound or a heads-up interruption. [CHANNEL_ID] is
 * created at [IMPORTANCE_LOW] specifically so a phone in a pocket stays quiet
 * for it while the notification itself remains visible, which is the whole
 * point of an ongoing-progress notification: proof the app is doing
 * something, not a demand for attention.
 */
object WatchSyncNotifications {
    const val CHANNEL_ID = "watch_sync"
    const val ONGOING_ID = 1001

    /**
     * The persistent link notification, distinct from [ONGOING_ID].
     *
     * Two ids because the two can be live at once — a scheduled sync runs while
     * the link service is holding the connection — and sharing one would have
     * each service's updates overwrite the other's text, ending with whichever
     * finished last describing a service that had already stopped.
     */
    const val LINK_ID = 1002

    /**
     * Idempotent by construction — `createNotificationChannel` with the same ID
     * updates rather than duplicates, so calling this on every service start
     * costs nothing and needs no "have I already done this" flag of its own.
     */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.watch_sync_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.watch_sync_channel_description)
            }
        )
    }

    /**
     * The notification a [android.app.Service] must show within seconds of
     * calling `startForeground`. [text] is replaceable — the service posts a
     * fresh one as the sync progresses — but the notification's identity
     * ([ONGOING_ID]) stays fixed, which is what makes an update replace it
     * in place instead of stacking a second one.
     */
    fun ongoing(context: Context, text: String): android.app.Notification {
        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(context.getString(R.string.watch_sync_title))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * The connection-is-being-held notification.
     *
     * Deliberately says which watch and what the link is doing. This one sits
     * in the shade indefinitely, so it has to earn its place by being the
     * answer to "is my watch actually connected?" rather than a bare "Tracks is
     * running".
     */
    fun link(context: Context, text: String): android.app.Notification {
        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(context.getString(R.string.watch_link_title))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
