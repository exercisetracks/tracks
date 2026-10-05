// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.flexibility

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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.tracks.app.MainActivity
import com.tracks.app.ui.strength.clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps a stretching flow playing with the screen off, and puts its controls
 * on the lock screen.
 *
 * A flow is done lying on a mat, phone out of reach, for ten to thirty
 * minutes. Without a foreground service the process is fair game the moment
 * the screen goes off, and the voice stops mid-flow. The notification is the
 * other half: pause, skip, +15 s and back, public on the lock screen, so the
 * flow can be driven without unlocking the phone with a hand that is holding a
 * foot.
 *
 * It plays nothing itself — [FlowPlayback] owns the session and its ticker — it
 * keeps the process alive and mirrors the state. Type `specialUse`: this is a
 * timer with spoken cues, which is none of the platform's named types, and the
 * reason is declared in the manifest as Android 14 requires. exported=false:
 * only this app starts it, and its buttons are this app's own PendingIntents.
 */
class FlowPlayerService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Stretching flow", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> FlowPlayback.togglePause()
            ACTION_SKIP -> FlowPlayback.skip()
            ACTION_BACK -> FlowPlayback.back()
            ACTION_MORE -> FlowPlayback.extend(EXTEND_SECONDS)
            ACTION_STOP -> {
                FlowPlayback.close()
                return START_NOT_STICKY
            }
        }
        val session = FlowPlayback.state.value
        if (session == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, build(session),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else 0,
        )
        if (intent?.action == null) observe()
        // Not sticky: a flow cannot be resumed by a restarted service with no
        // session, and a notification for a flow that no longer exists is worse
        // than none.
        return START_NOT_STICKY
    }

    private fun observe() {
        scope.launch {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // Redrawn only when what it shows changes — once a second, not on
            // every quarter-second tick.
            FlowPlayback.state
                .map { it?.let { s -> Triple(s.index, s.phase, s.remaining) to s.running } }
                .distinctUntilChanged()
                .collect {
                    val s = FlowPlayback.state.value
                    if (s == null) {
                        ServiceCompat.stopForeground(this@FlowPlayerService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        runCatching { nm.notify(NOTIFICATION_ID, build(s)) }
                    }
                }
        }
    }

    private fun build(s: FlowSession): Notification {
        val hold = s.hold
        val title = when (s.phase) {
            HoldPhase.Done -> "Flow complete"
            HoldPhase.Resting -> "Rest · next ${s.next?.name ?: ""}"
            HoldPhase.Holding -> listOfNotNull(hold?.name, hold?.side).joinToString(" · ")
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText("${clock(s.remaining)} · ${s.index + 1} of ${s.holds.size} · ${s.flow.name}")
            .setContentIntent(open)
            .setOngoing(s.phase != HoldPhase.Done)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            // Public: the whole point is driving the flow from the lock screen.
            // It shows a stretch's name and a clock, nothing about the user.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Back", command(ACTION_BACK))
            .addAction(0, if (s.running) "Pause" else "Play", command(ACTION_TOGGLE))
            .addAction(0, "+${EXTEND_SECONDS}s", command(ACTION_MORE))
            .addAction(0, "Skip", command(ACTION_SKIP))
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(0, 1, 3))
            .build()
    }

    private fun command(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(),
        Intent(this, FlowPlayerService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "flow_player"
        private const val NOTIFICATION_ID = 4202
        const val EXTEND_SECONDS = 15
        const val ACTION_TOGGLE = "com.tracks.app.flow.TOGGLE"
        const val ACTION_SKIP = "com.tracks.app.flow.SKIP"
        const val ACTION_BACK = "com.tracks.app.flow.BACK"
        const val ACTION_MORE = "com.tracks.app.flow.MORE"
        const val ACTION_STOP = "com.tracks.app.flow.STOP"
    }
}
