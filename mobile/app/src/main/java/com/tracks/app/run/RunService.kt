// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.run

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.tracks.app.MainActivity
import com.tracks.core.format.distance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The thing that keeps a run being recorded once the screen goes off.
 *
 * ## Why this has to be a service
 *
 * Android will not let an app receive location in the background without one.
 * That is not a technicality to work around — it is the deal: an app that
 * follows someone for an hour has to say so, visibly and continuously, in a
 * notification the user cannot swipe away. `FOREGROUND_SERVICE_TYPE_LOCATION` is
 * the declaration of exactly that, and this is the only place in Tracks that
 * asks for it. The map does not: it shows where you are while you are looking at
 * it, and stops when you are not.
 *
 * ## The location request, and what it costs
 *
 * One fix a second from the GPS provider, with no displacement filter. That is
 * the expensive setting, and it is the right one here for the same reason the
 * map's idle setting is the cheap one: this is the case where the fixes *are*
 * the product. A minute of ten-second fixes is a minute of a run drawn as a
 * straight line through whatever the road actually did.
 *
 * The GPS provider specifically, not the fused or network provider. On a run in
 * the hills there is no network to fuse with, and a fused provider that quietly
 * falls back to cell towers produces a track with kilometre-wide jumps in it
 * that looks like data and is not. [com.tracks.core.run.RunTrack] would drop
 * most of those anyway; not asking for them is better.
 *
 * ## Why the notification counts
 *
 * It is the only interface a run has for most of its length. Distance and time,
 * updated once a second — enough to answer "is it still recording", which is the
 * question that makes people take the phone out of the armband.
 */
class RunService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var ticker: Job? = null
    private var cues: RunCues? = null
    private var listening = false

    private val locationManager: LocationManager?
        get() = getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val listener = LocationListener { location: Location ->
        RunRecorder.onLocation(
            timestampMs = location.time,
            lat = location.latitude,
            lng = location.longitude,
            altitudeM = if (location.hasAltitude()) location.altitude else null,
            accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
        )
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        cues = RunCues(this).also { RunRecorder.cues = it }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                RunRecorder.pause()
                cues?.announcePause()
                stopListening()
                update()
                return START_STICKY
            }
            ACTION_RESUME -> {
                RunRecorder.resume()
                cues?.announceResume()
                startListening()
                update()
                return START_STICKY
            }
            ACTION_STOP -> {
                finishRun()
                return START_NOT_STICKY
            }
            ACTION_DISCARD -> {
                discardRun()
                return START_NOT_STICKY
            }
        }

        if (!hasLocationPermission()) {
            // Nothing to record and nothing to say: the screen that starts a run
            // asks for the permission first, so reaching here means it was
            // revoked underneath us.
            Log.w(TAG, "started without location permission — stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundNotification()
        if (!RunRecorder.isActive) {
            RunRecorder.start()
            cues?.announceStart()
        }
        startListening()
        startTicking()
        return START_STICKY
    }

    private fun finishRun() {
        val state = RunRecorder.state.value
        RunRecorder.finish()
        cues?.announceFinish(state.distanceM, state.movingMs)
        stopListening()
        ticker?.cancel()
        // The upload is the screen's job, not the service's: it needs the API
        // client and a place to show a failure, and a run that failed to upload
        // must stay on the phone rather than vanish with the notification.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Throw the run away: the screen's "delete and quit", confirmed there.
     *
     * Silent, unlike [finishRun]. "Run finished, four kilometres" spoken over a
     * run the user has just deleted would sound like it had been kept.
     */
    private fun discardRun() {
        RunRecorder.reset()
        stopListening()
        ticker?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startListening() {
        if (listening || !hasLocationPermission()) return
        val manager = locationManager ?: return
        runCatching {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                FIX_INTERVAL_MS,
                // No displacement filter: filtering here would hand the decision
                // to the platform, which cannot see the accuracy-aware rules in
                // RunTrack and would silently drop fixes that matter.
                0f,
                listener,
                mainLooper,
            )
            listening = true
        }.onFailure { Log.w(TAG, "could not request location updates", it) }
    }

    private fun stopListening() {
        if (!listening) return
        runCatching { locationManager?.removeUpdates(listener) }
        listening = false
    }

    /**
     * Once a second, so the clock on the notification moves.
     *
     * Cheap next to the GPS: this wakes only to recompute numbers already in
     * memory and redraw a notification, and it stops the moment the run does.
     */
    private fun startTicking() {
        ticker?.cancel()
        ticker = scope.launch {
            while (RunRecorder.isActive) {
                RunRecorder.tick()
                update()
                delay(1000)
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun startForegroundNotification() {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            },
        )
    }

    private fun update() {
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val state = RunRecorder.state.value
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val paused = state.phase == RunPhase.Paused
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(if (paused) "Run paused" else "Recording a run")
            .setContentText("${distance(state.distanceM)} · ${clock(state.movingMs)}")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            // Low: this updates every second, and a notification that pings or
            // peeks once a second for an hour is unusable.
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                0,
                if (paused) "Resume" else "Pause",
                command(if (paused) ACTION_RESUME else ACTION_PAUSE),
            )
            .addAction(0, "Finish", command(ACTION_STOP))
            .build()
    }

    private fun command(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(),
        Intent(this, RunService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun onDestroy() {
        stopListening()
        ticker?.cancel()
        scope.cancel()
        RunRecorder.cues = null
        cues?.release()
        cues = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "TracksRun"
        private const val CHANNEL_ID = "run_recording"
        private const val NOTIFICATION_ID = 4201

        const val ACTION_PAUSE = "com.tracks.app.run.PAUSE"
        const val ACTION_RESUME = "com.tracks.app.run.RESUME"
        const val ACTION_STOP = "com.tracks.app.run.STOP"
        const val ACTION_DISCARD = "com.tracks.app.run.DISCARD"

        /**
         * One fix a second.
         *
         * The interval a running watch uses, and for the same reason: at four
         * minutes a kilometre this is a point every four metres, which is enough
         * to draw a switchback. Every longer interval trades the shape of the
         * track for battery that a phone recording a run has already committed.
         */
        const val FIX_INTERVAL_MS = 1000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RunService::class.java))
        }

        fun send(context: Context, action: String) {
            context.startService(Intent(context, RunService::class.java).setAction(action))
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Run recording", NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shows distance and time while a run is being recorded."
                    setShowBadge(false)
                }
            )
        }
    }
}

/** "42:07", or "1:02:07" once it has been going an hour. */
internal fun clock(millis: Long): String {
    val total = millis / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}
