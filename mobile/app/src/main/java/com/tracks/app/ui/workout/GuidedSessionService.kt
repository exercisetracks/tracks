// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.workout

import android.app.Notification
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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.tracks.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps a strength or mobility session running with the screen off.
 *
 * A recorded run has its location service, and a stretching flow its player;
 * a guided strength or mobility session had nothing, so with the screen off
 * its countdown, its spoken cues and the step it was on all depended on a
 * process Android was free to put to sleep. This holds the process in the
 * foreground with a notification saying what is running, and a partial wake
 * lock so the one-second countdown keeps ticking. It does nothing itself: the
 * session's own ViewModel counts and speaks.
 *
 * Gone the moment the session is ([ActiveWorkout] clears), and the lock with
 * it. specialUse because no named foreground type fits a workout timer, as
 * for the stretching player.
 */
class GuidedSessionService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Workout"
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(title),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else 0,
        )
        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tracks:guided-session")
                .apply { acquire(MAX_HOLD_MS) }
            scope.launch {
                // Until the session ends -- finished, logged or deleted.
                ActiveWorkout.current.first { it == null }
                ServiceCompat.stopForeground(this@GuidedSessionService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(title: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText("Workout in progress")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "guided_session"
        private const val NOTIFICATION_ID = 4203
        private const val EXTRA_TITLE = "title"

        /** A ceiling, in case a session is abandoned without ending: four hours. */
        private const val MAX_HOLD_MS = 4 * 60 * 60 * 1000L

        fun start(context: Context, title: String) {
            ContextCompat.startForegroundService(
                context, Intent(context, GuidedSessionService::class.java).putExtra(EXTRA_TITLE, title),
            )
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Workout in progress", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }
}
