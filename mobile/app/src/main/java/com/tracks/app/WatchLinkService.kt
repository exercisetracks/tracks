// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.tracks.app.device.BluetoothPermissions
import com.tracks.app.device.WatchLink
import com.tracks.device.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/**
 * Holds the watch connection open so the phone feeds actually have somewhere to
 * go.
 *
 * ## Why a foreground service
 *
 * The connection has to outlive the Activity. A notification arriving while the
 * user's phone is in their pocket is the entire point of the relay, and that is
 * exactly when no Activity exists. Android will not let a backgrounded app hold
 * a GATT connection open — Doze and App Standby exist to stop precisely that —
 * so the only construct that can is a foreground service with a declared type.
 * `connectedDevice` is the type built for this, and it is already declared for
 * [WatchSyncService].
 *
 * The cost is an ongoing notification the user cannot dismiss, which is a real
 * cost and the reason this is a **user-visible choice** rather than something
 * the app switches on by itself. See [WatchLinkPreference].
 *
 * ## Why it is separate from WatchSyncService
 *
 * They want opposite things. A sync connects, moves bytes, and gets off the
 * radio as fast as possible; this one wants the link up indefinitely. Merging
 * them would mean a single service whose disconnect behaviour depended on why
 * it had been started, which is how the sync path grew its `finally { disconnect
 * }` in the first place.
 *
 * They do have to agree, though: a sync that finishes while this service is
 * running must not tear the link down. [WatchSyncService] asks
 * [isRunning] before disconnecting.
 *
 * Main dispatcher for the same reason [WatchSyncService] uses it —
 * `WatchManager.connect()` attaches the phone feeds, and `MusicMonitor` builds a
 * bare `Handler` that needs a prepared Looper.
 */
class WatchLinkService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onCreate() {
        super.onCreate()
        WatchSyncNotifications.ensureChannel(this)
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Checked here and not merely at the caller, because the system starts
        // this service on its own — see the START_STICKY below — and the user
        // can revoke Bluetooth access between one start and the next.
        //
        // Android refuses a `connectedDevice` foreground service to an app that
        // cannot use Bluetooth, and it refuses by throwing SecurityException out
        // of startForeground rather than by returning anything. On the main
        // thread, in a service, that is a fatal crash: a fresh install died on
        // every launch, because MainActivity starts the link from onStart and
        // nobody has been asked for Bluetooth yet at that point. The app went
        // straight back to the launcher and never showed a frame.
        //
        // stopSelf before startForeground is the sanctioned way out — the five
        // second deadline is satisfied by the service going away instead.
        if (!BluetoothPermissions.granted(this)) {
            Log.i(TAG, "not holding the link open: Bluetooth permission not granted yet")
            stopSelf()
            return START_NOT_STICKY
        }

        ServiceCompat.startForeground(
            this,
            WatchSyncNotifications.LINK_ID,
            WatchSyncNotifications.link(this, getString(R.string.watch_link_connecting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )

        if (job?.isActive == true) return START_STICKY

        val container = (applicationContext as TracksApplication).container
        job = scope.launch {
            // Reflect the real state in the notification rather than claiming
            // "connected" from the moment the service starts — the user is
            // looking at a permanent notification and it should not lie.
            scope.launch {
                container.watch.connection.collect { updateNotification(it) }
            }
            WatchLink(
                connection = container.watch.connection,
                connect = { container.watch.connect() },
                wake = bluetoothTurnedOn(),
            ).keepConnected()
            // Only reached when nothing is paired.
            stopSelf()
        }

        // STICKY, unlike the sync service: if the system kills this under
        // memory pressure the user's intent — keep the watch connected — is
        // still true, and a null restart intent is enough to act on it.
        return START_STICKY
    }

    /**
     * One emission each time Bluetooth comes back on — the supervisor's cue to
     * stop waiting out its backoff (see WatchLink's `wake`). Registered for the
     * life of the collector, so it ends with the supervisor.
     */
    private fun bluetoothTurnedOn(): Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_ON) {
                    trySend(Unit)
                }
            }
        }
        ContextCompat.registerReceiver(
            this@WatchLinkService, receiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        awaitClose { unregisterReceiver(receiver) }
    }

    private fun updateNotification(state: ConnectionState) {
        val text = when (state) {
            is ConnectionState.Connected -> getString(R.string.watch_link_connected, state.device.name)
            is ConnectionState.Initializing -> getString(R.string.watch_link_connecting)
            ConnectionState.Connecting -> getString(R.string.watch_link_connecting)
            ConnectionState.Disconnected, is ConnectionState.Failed ->
                getString(R.string.watch_link_waiting)
        }
        val manager = getSystemService(android.app.NotificationManager::class.java) ?: return
        manager.notify(WatchSyncNotifications.LINK_ID, WatchSyncNotifications.link(this, text))
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        // Deliberately does not disconnect. Stopping the supervisor is not the
        // same as wanting the watch dropped, and a sync may still be running.
        // WatchLinkPreference.disable() is the path that means "let go".
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "TracksWatchLink"

        @Volatile
        private var running = false

        /** Whether the link is being supervised — see the note in [onDestroy]. */
        fun isRunning(): Boolean = running

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, WatchLinkService::class.java),
                )
            }.onFailure { Log.w(TAG, "could not start the watch link service", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatchLinkService::class.java))
        }
    }
}
