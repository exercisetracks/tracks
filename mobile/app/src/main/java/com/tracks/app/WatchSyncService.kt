// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.tracks.app.device.BluetoothPermissions
import com.tracks.app.device.WatchLinkPreference
import com.tracks.app.device.WatchSyncRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs one watch sync with no Activity in sight, and the reason the rest of
 * this app can now sync from a schedule rather than only a button.
 *
 * ## Why a service and not just a coroutine on a timer
 *
 * A BLE transfer of a real backlog takes minutes — measured on a fenix 6X at
 * up to ten for a few hundred files — and Android does not let ordinary
 * background work run that long undisturbed. [WatchSyncScheduler] uses
 * WorkManager to decide *when* to sync, but a `CoroutineWorker`'s own
 * execution budget is shorter than a real sync can take, and neither Doze nor
 * App Standby buckets are lenient with a plain background coroutine holding a
 * GATT connection open. A foreground service is the one construct Android
 * lets run for as long as the work needs, in exchange for a visible,
 * non-dismissible notification — declared `connectedDevice` in the manifest,
 * which is the type built for exactly this.
 *
 * ## Why it disconnects afterward
 *
 * [com.tracks.app.MainViewModel.syncWatch] leaves the connection open after a
 * sync, because the UI is live and the phone feeds (notifications, weather,
 * music) should keep working while the user is looking at the app. This
 * service has no such reason: it exists to move bytes and then get off the
 * radio, so it disconnects once [WatchSyncRunner] returns, regardless of
 * outcome.
 */
class WatchSyncService : Service() {

    // Main, not Default — found on hardware. WatchManager.connect() attaches
    // the phone feeds, and MusicMonitor.observe() registers a
    // MediaSessionManager listener that constructs a bare android.os.Handler
    // internally, which requires a thread that has called Looper.prepare().
    // The manual sync path never hit this because ViewModel's viewModelScope
    // already runs on the main thread; nothing here needs to block it, since
    // the actual network/BLE work is all suspending, not thread-blocking.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onCreate() {
        super.onCreate()
        WatchSyncNotifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Same guard, same reason as WatchLinkService: startForeground with the
        // connectedDevice type throws SecurityException rather than failing
        // quietly when Bluetooth has not been granted, and this one is started
        // from a WorkManager schedule that has no idea what the user has agreed
        // to. A sync with no Bluetooth had nothing to do anyway.
        if (!BluetoothPermissions.granted(this)) {
            Log.i(TAG, "skipping the scheduled sync: Bluetooth permission not granted yet")
            stopSelf()
            return START_NOT_STICKY
        }

        ServiceCompat.startForeground(
            this,
            WatchSyncNotifications.ONGOING_ID,
            WatchSyncNotifications.ongoing(this, getString(R.string.watch_sync_connecting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )

        // A second start (WorkManager firing again before the first run
        // finished) restarts nothing — the running job keeps going, and this
        // command has nothing left to do but let the service stay foreground.
        if (job?.isActive == true) {
            return START_NOT_STICKY
        }

        job = scope.launch { runSync() }

        // Not STICKY: if the system kills this service under memory pressure,
        // restarting it with a null intent and no watch context to act on
        // would be pointless. The next scheduled run tries again on its own.
        return START_NOT_STICKY
    }

    private suspend fun runSync() {
        val container = (applicationContext as TracksApplication).container
        val progress = scope.launch {
            var count = 0
            container.watch.pulledFiles.collect {
                count++
                updateNotification(getString(R.string.watch_sync_progress, count))
            }
        }
        try {
            val outcome = container.watchSync.runOnce()
            updateNotification(summarize(outcome))
        } catch (e: Exception) {
            Log.w(TAG, "background watch sync failed", e)
            updateNotification(getString(R.string.watch_sync_failed))
        } finally {
            progress.cancel()
            // Only if nothing else wants the link. WatchLinkService holds the
            // connection open on purpose so the phone feeds have somewhere to
            // go; disconnecting here regardless would let a scheduled sync
            // silently switch the user's notification relay off every six
            // hours, and the link supervisor would then reconnect — producing a
            // connect/disconnect cycle nobody asked for.
            if (WatchLinkService.isRunning()) {
                Log.d(TAG, "leaving the connection open: the link service holds it")
            } else if (WatchLinkPreference.isEnabled(this)) {
                // Hand the connection over rather than dropping it.
                //
                // This is also the only way the link starts on a phone that has
                // rebooted and not been opened since: starting a foreground
                // service from the background is forbidden, but starting one
                // from a service that is *already* foreground is allowed, and
                // this one is. Without this the link would wait for the user to
                // open the app, which on an expedition tool could be days.
                Log.d(TAG, "handing the connection to the link service")
                WatchLinkService.start(this)
            } else {
                runCatching { container.watch.disconnect() }
                    .onFailure { Log.w(TAG, "could not disconnect after background sync", it) }
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun summarize(outcome: WatchSyncRunner.Outcome): String = when {
        outcome.alreadyRunning -> getString(R.string.watch_sync_already_running)
        !outcome.paired -> getString(R.string.watch_sync_not_paired)
        outcome.pulled.isEmpty() -> getString(R.string.watch_sync_nothing_new)
        outcome.notRegistered -> getString(R.string.watch_sync_not_registered, outcome.pulled.size)
        else -> getString(
            R.string.watch_sync_result,
            outcome.pulled.size,
            outcome.uploadResult?.delivered ?: 0,
        )
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(android.app.NotificationManager::class.java) ?: return
        manager.notify(WatchSyncNotifications.ONGOING_ID, WatchSyncNotifications.ongoing(this, text))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "TracksWatchSyncService"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WatchSyncService::class.java))
        }
    }
}
