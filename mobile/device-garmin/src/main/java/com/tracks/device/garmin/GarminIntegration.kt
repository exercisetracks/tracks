// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.garmin

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.tracks.device.CompanionPairing
import com.tracks.device.ConnectionState
import com.tracks.device.DeviceCapabilities
import com.tracks.device.DeviceIntegration
import com.tracks.device.FindPhone
import com.tracks.device.DeviceNotification
import com.tracks.device.NotificationResponse
import com.tracks.device.MusicCommand
import com.tracks.device.MusicState
import com.tracks.device.MusicTrack
import com.tracks.device.PairedDevice
import com.tracks.device.PulledFile
import com.tracks.device.PulledFileKind
import com.tracks.device.WeatherReport
import java.io.File
import java.util.regex.Pattern
import kotlin.coroutines.resume
import nodomain.freeyourgadget.gadgetbridge.proto.garmin.GdiInstalledAppsService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import android.os.SystemClock
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventFindPhone
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventMusicControl
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventNotificationControl
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.service.AbstractDeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.FileTransferHandler
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.FileType
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.GarminSupport
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.deviceevents.FileDownloadedDeviceEvent
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.deviceevents.SyncFileDownloadedDeviceEvent
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.deviceevents.WeatherRequestDeviceEvent
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.FitFile
import nodomain.freeyourgadget.gadgetbridge.util.GB
import nodomain.freeyourgadget.gadgetbridge.proto.garmin.GdiSmartProto

/**
 * Tracks' side of the vendoring boundary.
 *
 * This is the only class that sees both worlds. Below it is
 * [GarminSupport] and ~200 files of vendored Gadgetbridge protocol code that
 * has never heard of Tracks; above it is the app, which has never heard of GFDI.
 * Everything crossing between them crosses here, which is what makes the
 * Coros-shaped hole in the design real rather than aspirational — a second
 * vendor writes a second class like this one and nothing above changes.
 *
 * ## What it deliberately does not do
 *
 * It does not touch the network, and it does not encrypt anything. A pulled
 * file is announced on [pulledFiles] with a path; sealing those bytes with the
 * user's key and getting them to the server belongs to the sync layer, which
 * already knows how. Keeping that out of here is what lets the whole transport
 * be reasoned about without also reasoning about the crypto session.
 */
class GarminIntegration(private val context: Context) : DeviceIntegration {

    override val vendorId: String = VENDOR_ID

    /**
     * Assisted GPS is off, and that is a real gap rather than a decision
     * against it: over BLE the watch fetches ephemeris by asking the phone to
     * proxy an HTTP request, and Tracks' HTTP handler refuses every request by
     * design — an app whose premise is self-hosting should not become an open
     * proxy to Garmin's servers. Serving *only* a server-supplied ephemeris file
     * from local storage would close this without reopening the proxy, and is
     * the obvious next step. Until then, saying false is honest.
     */
    override val capabilities = DeviceCapabilities(
        canPull = true,
        canPush = true,
        canArchive = true,
        notifications = true,
        notificationActions = true,
        weather = true,
        music = true,
        findPhone = true,
        assistedGps = false,
    )

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connection: Flow<ConnectionState> = _connection.asStateFlow()

    /**
     * Replay of 1 and a buffer sized for a whole backlog rather than a burst.
     *
     * The previous 64 was chosen against the wrong picture of how files arrive.
     * It assumed each emission was paced by its own BLE transfer — seconds
     * apart, plenty of time for the collector on the main thread to keep up. But
     * a file already on the phone is announced with no transfer at all, so a
     * first sync after an interrupted one emits the entire backlog in a tight
     * loop, hundreds of files in milliseconds. `tryEmit` cannot suspend, so a
     * full buffer means the value is simply gone.
     *
     * Measured on a fenix 6X: 364 files announced, 301 collected. Sixty-three
     * activities were pulled off the watch and then dropped on the floor between
     * this line and the uploader — no error, no warning, and the sync reported
     * success. The comment that used to sit here called that "the worst possible
     * failure mode", which was right; the buffer just was not sized for it.
     *
     * Hence both halves of the fix: a buffer larger than any real watch's
     * backlog, *and* a loud complaint in [emitPulled] if it ever fills anyway.
     * A silent drop here is invisible everywhere else.
     */
    private val _pulledFiles = MutableSharedFlow<PulledFile>(replay = 1, extraBufferCapacity = 4096)
    override val pulledFiles: Flow<PulledFile> = _pulledFiles.asSharedFlow()

    private val _battery = MutableStateFlow<Int?>(null)
    override val battery: Flow<Int?> = _battery.asStateFlow()

    private val _musicCommands = MutableSharedFlow<MusicCommand>(extraBufferCapacity = 8)
    override val musicCommands: Flow<MusicCommand> = _musicCommands.asSharedFlow()

    // Capacity of one: several capability queries can arrive in a burst when the
    // watch's music screen is opened, and they all mean the same thing.
    private val _musicRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val musicRequests: Flow<Unit> = _musicRequests.asSharedFlow()

    /**
     * No replay, and a small buffer.
     *
     * Replaying would be actively wrong here: a subscriber attaching after a
     * reconnect would be handed a find-my-phone request from an hour ago and
     * start ringing in somebody's pocket. The buffer covers a start and a stop
     * arriving back to back, which is the only burst this feed has.
     */
    private val _findPhone = MutableSharedFlow<FindPhone>(extraBufferCapacity = 4)
    override val findPhone: Flow<FindPhone> = _findPhone.asSharedFlow()

    /**
     * No replay, for the same reason as [findPhone]: a reply replayed into a
     * later subscriber would send somebody's message twice. The buffer covers
     * a quick run of clears, which is the only burst this feed has.
     */
    private val _notificationResponses = MutableSharedFlow<NotificationResponse>(extraBufferCapacity = 16)
    override val notificationResponses: Flow<NotificationResponse> = _notificationResponses.asSharedFlow()

    /**
     * No buffer replay and a capacity of one: a stale weather request is
     * pointless. If two arrive before anyone answers, answering once with the
     * current forecast is the right behaviour, not answering twice.
     */
    private val _weatherRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val weatherRequests: Flow<Unit> = _weatherRequests.asSharedFlow()

    private val pairing = CompanionPairing(context)

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var support: GarminSupport? = null
    private var device: GBDevice? = null
    private var connected: PairedDevice? = null

    /**
     * Drops the link when Bluetooth is switched off.
     *
     * Android does not report that to a GATT client: no connection-state
     * callback arrives, the client object just goes dead, and the next write
     * throws `DeadObjectException`. So this layer went on reporting Connected,
     * [connect] kept "reusing the link", and every sync hung on it until the
     * app was restarted (measured 2026-09-30: Bluetooth off for two minutes,
     * then a sync that never finished). Tearing the link down here makes the
     * state true again, which is what lets the link supervisor reconnect once
     * Bluetooth is back.
     *
     * Any waiter is failed first and outside the lock: a connect attempt in
     * progress holds [connectLock] while it waits, and would otherwise sit out
     * its whole timeout before the teardown could run.
     */
    private val adapterOff = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: android.content.Intent) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (state != BluetoothAdapter.STATE_TURNING_OFF && state != BluetoothAdapter.STATE_OFF) return
            if (support == null && _connection.value is ConnectionState.Disconnected) return
            Log.i(TAG, "Bluetooth switched off; dropping the link")
            readySignal?.completeExceptionally(DisconnectedException())
            syncSignal?.completeExceptionally(DisconnectedException())
            uploadSignal?.complete(false)
            lifecycle.launch { connectLock.withLock { disconnectLocked() } }
        }
    }

    /** For the teardown above, which has to leave the broadcast thread to take a lock. */
    private val lifecycle = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main,
    )

    init {
        androidx.core.content.ContextCompat.registerReceiver(
            context.applicationContext,
            adapterOff,
            android.content.IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private var readySignal: CompletableDeferred<Unit>? = null
    private var syncSignal: CompletableDeferred<Unit>? = null
    private var uploadSignal: CompletableDeferred<Boolean>? = null
    /** When the running upload last reported forward progress; drives the stall watchdog. */
    @Volatile private var lastPushProgressAt = 0L

    // ── Pairing ──────────────────────────────────────────────────────────────

    /**
     * Return an existing association, or ask for one.
     *
     * Existing associations are checked first and returned without a dialog:
     * the association outlives the app's process and its data, so a user who
     * clears storage should not be made to re-pair hardware they already
     * confirmed.
     */
    override suspend fun pair(picker: CompanionPairing.DevicePicker?): PairedDevice {
        // 1. An association we already have. Preferred, and the only one that
        //    gets presence callbacks from the OS.
        pairing.associated(vendorId).firstOrNull { looksLikeGarmin(it.name) }?.let { return it }

        // 2. A watch already bonded to this phone. This is the ordinary case for
        //    anyone arriving from Garmin Connect or Gadgetbridge: the bond
        //    exists, so the watch is not advertising, so a scan finds nothing.
        //    Skipping straight to the picker would show them an empty list and
        //    a watch they can see on their wrist.
        pairing.bonded(vendorId, GARMIN_NAME_PATTERN).firstOrNull()?.let { return it }

        // 3. Nothing known — ask, which needs the watch in pairing mode.
        requireNotNull(picker) {
            "No watch found, and pairing needs an Activity to show the system dialog"
        }

        return pairing.associate(picker, GARMIN_NAME_PATTERN, vendorId)
            ?: throw PairingCancelledException()
    }

    class PairingCancelledException : Exception("The user dismissed the pairing dialog")

    /**
     * Override which file-sync protocol to speak to a device.
     *
     * Garmin has two, and which one a watch can speak is a property of its
     * firmware rather than something either side negotiates. The older one
     * streams a directory listing and then the files, one at a time, each
     * acknowledged; the newer one asks over protobuf and hands each file down a
     * separate BLE channel, deflated. On the hardware measured here that is a
     * ten-minute backlog against most of a day, so the newer protocol is the
     * default and the device layer works out for itself when it cannot be used.
     *
     * Which means this is an override, not a switch: pass `null` — the default
     * — to hand the decision back. It exists for the case the automatic
     * decision cannot cover, a watch that answers the newer protocol badly
     * rather than not at all, because that looks like success from here.
     *
     * Stored per device, because two watches on one phone can disagree.
     * [reliableTransport] stays separate rather than implied: Gadgetbridge's
     * experience is that the newer sync needs it for large files, but it also
     * changes how the main protocol channel is opened — so on a watch that is
     * otherwise working, being able to move one variable at a time is worth the
     * extra argument.
     *
     * Not on [DeviceIntegration]: another vendor has no reason to have two
     * protocols, let alone these two.
     */
    fun setSyncProtocol(
        address: String,
        newProtocol: Boolean? = null,
        reliableTransport: Boolean? = newProtocol,
    ) {
        GBApplication.getDeviceSpecificSharedPrefs(address).edit().apply {
            if (newProtocol == null) {
                remove(GarminSupport.PREF_NEW_SYNC_PROTOCOL)
            } else {
                putBoolean(GarminSupport.PREF_NEW_SYNC_PROTOCOL, newProtocol)
            }
            if (reliableTransport == null) {
                remove(GarminSupport.PREF_MLR)
            } else {
                putBoolean(GarminSupport.PREF_MLR, reliableTransport)
            }
        }.apply()

        Log.i(
            TAG,
            "Sync protocol for $address: " + when (newProtocol) {
                null -> "decided per connection"
                true -> "forced to protobuf file sync"
                false -> "forced to the directory listing"
            } + when (reliableTransport) {
                null -> ""
                true -> ", service channels forced reliable"
                false -> ", service channels forced unreliable"
            },
        )
    }

    // ── Connection ───────────────────────────────────────────────────────────

    /**
     * Serialises connecting and disconnecting, and it is the reason a sync no
     * longer hangs on the handshake.
     *
     * Two things drive this layer independently and neither knows about the
     * other: `WatchLink`, which holds the link open so the phone feeds have
     * somewhere to go, and a sync, which wants a connection for as long as it
     * takes to move bytes. [com.tracks.app.device.WatchSyncRunner]'s mutex
     * guards sync against sync; nothing guarded either of them against the link
     * supervisor.
     *
     * The interleaving was fatal rather than merely wasteful. [connect] used to
     * begin by tearing down whatever was there, so a sync starting during the
     * supervisor's handshake disposed a half-initialised [GarminSupport] and
     * started a second one — and then the first call's `finally` cleared
     * [readySignal], which by that point belonged to the *second* attempt. The
     * watch duly finished its handshake, [GarminSupport.SyncListener.onWatchReady]
     * fired, and the signal it completed was nobody's: the sync sat there for
     * the full ninety seconds and reported that the watch had not answered.
     *
     * It then repeated, because a failed connect leaves the state disconnected
     * and noticing that is the supervisor's entire job. Opening the app and
     * pressing sync — the ordinary case, since [com.tracks.app.MainActivity.onStart]
     * brings the link up and the user taps the button seconds later — hit it
     * every time.
     *
     * With the lock the second caller waits out the first attempt and then finds
     * a connection already up, which is what it wanted in the first place.
     */
    private val connectLock = Mutex()

    /**
     * One file goes to the watch at a time.
     *
     * Both upload paths keep a single slot for "the transfer in flight" —
     * [uploadSignal] here, and `uploadListener` inside `FileSyncServiceHandler` —
     * and both are written from the caller's thread and read on the BLE callback
     * thread. Two pushes at once therefore do not queue, they overwrite: the
     * second caller's handle replaces the first's, and the first's
     * `CompletableDeferred` is never completed by anything, so it waits out the
     * whole of `PUSH_TIMEOUT_MS` before reporting a failure for a file that may
     * well have arrived.
     *
     * That is not hypothetical. A background sync pushing a schedule and the
     * user tapping "send to watch" on a course is two coroutines on different
     * dispatchers reaching [pushFile] with nothing between them.
     *
     * A mutex rather than making each slot a map: the watch does one transfer at
     * a time regardless, so overlapping pushes have nothing to win, and holding
     * them in order costs only the wait they would have spent anyway.
     */
    private val pushLock = Mutex()

    override suspend fun connect(device: PairedDevice) {
        connectLock.withLock {
            // A live link to the same watch is the answer, not something to
            // rebuild. Reconnecting here is what turned "sync while the feeds
            // are connected" into a dropped link and a fresh handshake.
            if (isLiveConnectionTo(device)) {
                Log.d(TAG, "already connected to ${device.name}; reusing the link")
                return
            }
            disconnectLocked()
            connectLocked(device)
        }
    }

    private fun isLiveConnectionTo(device: PairedDevice): Boolean {
        val state = _connection.value
        return support?.isConnected == true &&
            state is ConnectionState.Connected &&
            state.device.address == device.address
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectLocked(device: PairedDevice) {
        _connection.value = ConnectionState.Connecting

        val gbDevice = GBDevice(device.address, device.name, /* model = */ null)
        val support = GarminSupport()

        // Guarded on identity rather than wired straight through. A superseded
        // connection's GBDevice keeps this listener, and its BLE callbacks can
        // land after the replacement is up; a stale NOT_CONNECTED arriving then
        // reports a healthy link as dropped, and the supervisor tears it down
        // and rebuilds it for no reason.
        gbDevice.setStateListener { changed ->
            if (changed === this@GarminIntegration.device) onDeviceStateChanged(device, changed)
        }
        support.setContext(gbDevice, adapter, context)
        support.addEventListener(AbstractDeviceSupport.EventListener { event -> onDeviceEvent(event) })
        support.setSyncListener(object : GarminSupport.SyncListener {
            override fun onWatchReady() {
                readySignal?.complete(Unit)
            }

            override fun onSyncFinished() {
                syncSignal?.complete(Unit)
            }
        })
        support.setMusicRefreshListener(GarminSupport.MusicRefreshListener {
            _musicRequests.tryEmit(Unit)
        })
        GB.setInstallProgressListener { outcome, percentage ->
            onInstallProgress(outcome, percentage)
        }

        this.support = support
        this.device = gbDevice
        this.connected = device

        val ready = CompletableDeferred<Unit>()
        readySignal = ready

        try {
            if (!support.connect()) {
                error("Could not open a Bluetooth connection to ${device.name}")
            }
            // Generous, because this covers bonding, service discovery, MTU
            // negotiation and the watch's own handshake — and a watch that was
            // asleep in a pocket takes noticeably longer than one on a wrist.
            withTimeout(CONNECT_TIMEOUT_MS) { ready.await() }
        } catch (e: Exception) {
            // Every way this fails leaves the same wreckage — a GarminSupport
            // wired to a link that is not up — so they all clean up the same
            // way, cancellation included. That last one used to leak: a
            // supervisor whose scope was cancelled mid-handshake left the
            // support object behind for the next connect to trip over.
            //
            // Teardown before the reason, because disconnecting reports
            // Disconnected and that is otherwise the state the user is left
            // looking at in the link notification.
            disconnectLocked()
            fail(device, reasonFor(e))
            throw e
        } finally {
            // Identity-checked, not cleared outright. An attempt may only
            // retract its own signal — clearing whatever happened to be there is
            // precisely how a superseded attempt used to silence the one that
            // replaced it, and the watch's readiness then landed on nothing.
            if (readySignal === ready) readySignal = null
        }
    }

    /**
     * Turn a failed attempt into something a person can act on.
     *
     * These are read rather than logged — the watch screen shows the reason
     * verbatim next to the connection status — so they name what happened to
     * the watch, not what was thrown.
     */
    private fun reasonFor(e: Exception): String = when (e) {
        is TimeoutCancellationException -> "The watch did not finish its handshake"
        is DisconnectedException -> "The watch went out of range"
        else -> "Could not open a Bluetooth connection"
    }

    override suspend fun disconnect() {
        connectLock.withLock { disconnectLocked() }
    }

    /**
     * The body of [disconnect], for callers already holding [connectLock].
     *
     * Separate because [Mutex] is not reentrant: [connectLocked] tears the old
     * link down and cleans up after its own failures, and calling the public
     * method from in there would deadlock rather than fail visibly.
     */
    private fun disconnectLocked() {
        support?.let { support ->
            try {
                support.disconnect()
                support.dispose()
            } catch (e: Exception) {
                Log.w(TAG, "Error while disconnecting", e)
            }
        }
        // Detached before the reference is dropped, so a late callback from the
        // BLE thread cannot reach this object at all rather than merely being
        // ignored once it arrives.
        device?.setStateListener(null)
        GB.setInstallProgressListener(null)
        support = null
        device = null
        connected = null
        // Anything waiting is now waiting forever unless it is told; a dropped
        // link must surface as a failed sync, not a coroutine that never returns.
        readySignal?.completeExceptionally(DisconnectedException())
        syncSignal?.completeExceptionally(DisconnectedException())
        uploadSignal?.complete(false)
        _connection.value = ConnectionState.Disconnected
    }

    class DisconnectedException : Exception("The watch disconnected")

    private fun fail(device: PairedDevice, reason: String) {
        Log.w(TAG, "$reason (${device.name})")
        _connection.value = ConnectionState.Failed(reason)
    }

    private fun onDeviceStateChanged(paired: PairedDevice, changed: GBDevice) {
        // A link that is down cannot go on to finish a handshake, and nothing
        // else would ever tell the waiter so. Without this an out-of-range watch
        // costs the full CONNECT_TIMEOUT_MS on every attempt: the GATT connect
        // fails within seconds, and then ninety more pass before the supervisor
        // is allowed to back off — which is the opposite of what the backoff is
        // for, since those ninety seconds are spent holding the radio.
        //
        // Safe to treat as terminal because the vendored queue only reports this
        // state on a real drop: `BtLEQueue.connect` goes straight to CONNECTING
        // without passing through NOT_CONNECTED, and the reconnect states that
        // would be ambiguous (WAITING_FOR_RECONNECT, WAITING_FOR_SCAN) need an
        // autoReconnect this build never enables.
        if (changed.state == GBDevice.State.NOT_CONNECTED) {
            readySignal?.completeExceptionally(DisconnectedException())
            // The other two waiters, for the same reason and at much greater
            // cost. Only an explicit disconnect() reached disconnectLocked,
            // so a watch that dropped on its own left these hanging: a sync in
            // flight waited out SYNC_TIMEOUT_MS -- forty-five minutes, holding a
            // foreground service -- and a legacy push waited five, for a link
            // the callback above had already reported gone.
            //
            // This stopped being an edge case when the training-calendar push
            // started landing in GARMIN/NewFiles: importing that batch reboots
            // the watch, so a drop mid-run is now the ordinary end of a
            // successful push rather than a rare failure. See AGENTS.md.
            syncSignal?.completeExceptionally(DisconnectedException())
            uploadSignal?.complete(false)
        }

        _connection.value = when (changed.state) {
            GBDevice.State.NOT_CONNECTED -> ConnectionState.Disconnected
            GBDevice.State.WAITING_FOR_RECONNECT,
            GBDevice.State.WAITING_FOR_SCAN,
            GBDevice.State.CONNECTING -> ConnectionState.Connecting

            GBDevice.State.INITIALIZED -> ConnectionState.Connected(
                paired.copy(serialNumber = changed.firmwareVersion2 ?: paired.serialNumber)
            )
            // CONNECTED means the GATT link is up but the watch has not yet said
            // what it is. Reporting that as Connected would invite the app to
            // start pushing files into a device that will drop them.
            else -> ConnectionState.Initializing(paired)
        }
    }

    // ── Events out of the vendored layer ─────────────────────────────────────

    private fun onDeviceEvent(event: GBDeviceEvent) {
        when (event) {
            is FileDownloadedDeviceEvent -> onFileDownloaded(event)
            is GBDeviceEventBatteryInfo -> _battery.value = event.level.toInt().takeIf { it >= 0 }
            is GBDeviceEventVersionInfo -> onVersionInfo(event)
            is GBDeviceEventMusicControl -> toMusicCommand(event.event)?.let { _musicCommands.tryEmit(it) }
            is WeatherRequestDeviceEvent -> _weatherRequests.tryEmit(Unit)
            is GBDeviceEventFindPhone -> findPhoneFor(event.event)?.let { _findPhone.tryEmit(it) }
            is GBDeviceEventNotificationControl ->
                GarminNotifications.responseOf(event)?.let { _notificationResponses.tryEmit(it) }
                    ?: Log.d(TAG, "Unhandled notification control: $event")
            else -> Log.d(TAG, "Unhandled device event: $event")
        }
    }

    /**
     * Translate the vendored music event into the vendor-neutral command.
     *
     * FORWARD and REWIND are dropped rather than mapped onto NEXT and PREVIOUS.
     * Scrubbing within a track and skipping to a different one are different
     * intentions, and guessing wrong means the user's podcast jumps a chapter
     * when they wanted fifteen seconds.
     */
    private fun toMusicCommand(event: GBDeviceEventMusicControl.Event): MusicCommand? = when (event) {
        GBDeviceEventMusicControl.Event.PLAY -> MusicCommand.PLAY
        GBDeviceEventMusicControl.Event.PAUSE -> MusicCommand.PAUSE
        GBDeviceEventMusicControl.Event.PLAYPAUSE -> MusicCommand.PLAY_PAUSE
        GBDeviceEventMusicControl.Event.NEXT -> MusicCommand.NEXT
        GBDeviceEventMusicControl.Event.PREVIOUS -> MusicCommand.PREVIOUS
        GBDeviceEventMusicControl.Event.VOLUMEUP -> MusicCommand.VOLUME_UP
        GBDeviceEventMusicControl.Event.VOLUMEDOWN -> MusicCommand.VOLUME_DOWN
        GBDeviceEventMusicControl.Event.FORWARD,
        GBDeviceEventMusicControl.Event.REWIND,
        GBDeviceEventMusicControl.Event.UNKNOWN -> null
    }

    private fun onVersionInfo(event: GBDeviceEventVersionInfo) {
        device?.firmwareVersion = event.fwVersion
        // Garmin's "unit number" is the serial the server keys device claims on.
        device?.firmwareVersion2 = event.fwVersion2
        connected = connected?.copy(serialNumber = event.fwVersion2)
    }

    private fun onFileDownloaded(event: FileDownloadedDeviceEvent) {
        val path = event.localPath ?: return
        val file = File(path)
        if (!file.exists() || file.length() == 0L) {
            Log.w(TAG, "Downloaded file is missing or empty: $path")
            return
        }
        val kind: PulledFileKind
        val deviceFileId: String?
        if (event is SyncFileDownloadedDeviceEvent) {
            val syncFile = event.syncFile
            // hasType()/hasName() rather than a null check: these are proto2
            // optionals, so an absent field reads back as "" rather than null
            // and would quietly become a file type named nothing.
            val typeName = if (syncFile.hasType() && syncFile.type.hasName()) {
                syncFile.type.name
            } else {
                null
            }
            kind = kindOf(typeName?.let { FileType.FILETYPE.findByTypeName(it) })
            deviceFileId = GarminSyncFileId.encode(syncFile.id.id1, syncFile.id.id2, typeName)
        } else {
            val entry = event.directoryEntry
            kind = kindOf(entry?.filetype)
            deviceFileId = entry?.fileIndex?.toString()
        }
        emitPulled(
            PulledFile(
                localPath = path,
                name = file.name,
                sizeBytes = file.length(),
                kind = kind,
                deviceFileId = deviceFileId,
            )
        )
    }

    /**
     * Announce a pulled file, and say so if the announcement is lost.
     *
     * `tryEmit` rather than `emit` because this runs on the BLE callback thread,
     * which must not be suspended — blocking it stalls the protocol for every
     * other message on the connection. The cost of that choice is that a full
     * buffer drops the value, so the only defence is to notice. This is an error
     * rather than a warning on purpose: a dropped file is an activity that came
     * off the watch and will never reach the server, and the watch is not told
     * to keep it either, because nothing downstream ever hears about it.
     */
    private fun emitPulled(file: PulledFile) {
        // Recorded before the flow, and the reason [pullAll] can return a
        // trustworthy list: this runs on the transport's own thread, the same
        // one that later ends the run, so the list is complete by then. The
        // flow has no such ordering — that is what the 54 unuploaded files were.
        synchronized(pulledThisRun) { pulledThisRun.add(file) }

        if (!_pulledFiles.tryEmit(file)) {
            Log.e(TAG, "Dropped ${file.name}: nothing is keeping up with the sync. " +
                "It stays on the phone and on the watch, and the next sync will offer it again.")
        }
    }

    /** Files handed over by the run currently in flight. See [pullAll]. */
    private val pulledThisRun = mutableListOf<PulledFile>()

    /**
     * Map Garmin's file types onto the vendor-neutral ones.
     *
     * Coarse on purpose. Tracks does not parse FIT on the phone, so this only
     * decides how a file is described in the UI and whether the sync layer
     * treats it as an activity worth prioritising — the server reads the real
     * type out of the file itself. Anything unrecognised becomes OTHER and is
     * still uploaded, because a file type this build has never seen is exactly
     * the case where guessing would lose data.
     */
    private fun kindOf(type: FileType.FILETYPE?): PulledFileKind = when (type) {
        FileType.FILETYPE.ACTIVITY, FileType.FILETYPE.ACTIVITY_GCPD -> PulledFileKind.ACTIVITY
        FileType.FILETYPE.SLEEP, FileType.FILETYPE.SLP_DISR -> PulledFileKind.SLEEP
        FileType.FILETYPE.MONITOR, FileType.FILETYPE.MONITOR_A,
        FileType.FILETYPE.MONITOR_DAILY -> PulledFileKind.MONITORING

        FileType.FILETYPE.HRV_STATUS -> PulledFileKind.HRV
        FileType.FILETYPE.METRICS -> PulledFileKind.METRICS
        FileType.FILETYPE.COURSES -> PulledFileKind.COURSE
        FileType.FILETYPE.LOCATION -> PulledFileKind.PLACES
        FileType.FILETYPE.SCHEDULES -> PulledFileKind.SCHEDULE
        else -> PulledFileKind.OTHER
    }

    /**
     * Ask the next sync for what the user *put* on the watch, not just what it
     * recorded.
     *
     * Off by default and meant to be turned on for one sync. Garmin marks
     * courses and locations as files a companion app does not fetch, which is
     * the right default — they do not change on their own, so pulling them
     * every time would move the same bytes forever and slow down the sync that
     * matters. But it also means a watch full of routes loaded from Garmin
     * Connect is invisible to Tracks until somebody asks, and this is the ask.
     *
     * Applies to both file-sync protocols: the newer one lists files over
     * protobuf and the older one streams a directory, and a watch on either
     * should answer the same question the same way.
     */
    fun setPullSavedItems(wanted: Boolean) {
        FileType.FILETYPE.setAlsoPull(
            if (wanted) {
                // The schedule is not here: it is pulled on every sync, not
                // just this one. See FileType.FILETYPE.ALWAYS_ALSO_PULL.
                java.util.EnumSet.of(FileType.FILETYPE.COURSES, FileType.FILETYPE.LOCATION)
            } else {
                java.util.EnumSet.noneOf(FileType.FILETYPE::class.java)
            }
        )
        Log.i(TAG, "Saved items (courses, locations) will " +
            if (wanted) "be pulled" else "be skipped")
    }

    // ── Pulling ──────────────────────────────────────────────────────────────

    override suspend fun pullAll(): List<PulledFile> {
        val support = requireConnected()
        val signal = CompletableDeferred<Unit>()
        synchronized(pulledThisRun) { pulledThisRun.clear() }
        syncSignal = signal
        try {
            support.fetchRecordedData()
            withTimeout(SYNC_TIMEOUT_MS) { signal.await() }
        } finally {
            // Identity-checked for the reason given in [connectLocked]: a
            // disconnect that lands mid-run replaces nothing, but a *second*
            // run starting after one was abandoned must not have its signal
            // cleared by the first one unwinding.
            if (syncSignal === signal) syncSignal = null
        }
        sweepOrphanedFiles(support)
        return synchronized(pulledThisRun) { pulledThisRun.toList() }
    }

    /**
     * Pick up bytes this run's own machinery left behind.
     *
     * A file the watch is still offering should always be caught by
     * [GarminSupport]'s own "already on disk" check, which announces it
     * instead of a fresh BLE pull. This exists for the case that check cannot
     * cover: the newer protocol's type name is not guaranteed to resolve to
     * the same string on every connection — measured on a fenix 6X, one
     * connection named a file "UNKNOWN" and the next named the identical
     * watch file "FIT_TYPE_60" — and the local filename is built from that
     * name. Offered again under a different resolved name, the file gets
     * pulled again under a different path, and the earlier, differently-named
     * copy becomes an orphan nothing would otherwise ever look at again.
     *
     * By the time this runs the whole queue has drained — [pullAll] only gets
     * here after the sync-finished signal fires, which [GarminSupport] does
     * not send until its download queue is empty — so anything still sitting
     * in the export directory and not already in [pulledThisRun] was never
     * touched by this run's machinery at all: a genuine leftover, not a file
     * mid-transfer.
     *
     * [PulledFile.deviceFileId] is deliberately null: an orphan's exact
     * watch-side identity is what got lost, so there is no way to tell the
     * watch to reclaim it. [confirmPulled] treats a null id as "nothing to
     * tell the watch" and just uploads the bytes and deletes the local copy —
     * which is the actual goal here: get it onto the server exactly once,
     * then stop carrying a phone-only copy of a complete GPS trace.
     */
    private fun sweepOrphanedFiles(support: GarminSupport) {
        val dir = runCatching { support.writableExportDirectory }.getOrNull() ?: return
        val alreadyCaught = synchronized(pulledThisRun) { pulledThisRun.map { it.localPath }.toSet() }
        dir.walkTopDown()
            .filter { it.isFile && it.extension == "fit" && it.absolutePath !in alreadyCaught }
            .forEach { file ->
                // Classified by the type directory the file sits in, rather
                // than assumed to be a recording. Calling everything OTHER sent
                // whatever was lying about to the activity ingest, which is
                // wrong for the two kinds that are not recordings at all: an
                // orphaned course would be filed as a ride that never happened.
                val kind = kindOf(FileType.FILETYPE.findByTypeName(file.parentFile?.name))

                if (kind == PulledFileKind.SCHEDULE) {
                    // Never resurrected from disk. A schedule is evidence, and
                    // it is only evidence of anything if the watch handed it
                    // over on *this* run — a copy left by an earlier sync would
                    // be reported with a fresh timestamp and read as the current
                    // calendar. That is precisely the "a stale calendar looks
                    // identical to a fresh one" trap the read-back exists to
                    // close, so the sweep must not reopen it.
                    return@forEach
                }

                Log.w(TAG, "${file.name} was left behind by an earlier sync under a name the " +
                    "watch no longer uses for it; uploading it now instead of losing it silently")
                emitPulled(
                    PulledFile(
                        localPath = file.absolutePath,
                        name = file.name,
                        sizeBytes = file.length(),
                        kind = kind,
                        deviceFileId = null,
                    )
                )
            }
    }

    /**
     * Let the watch reclaim the file, then drop the phone's copy.
     *
     * Both halves matter. The watch keeps its copy until we say otherwise,
     * which is the whole point of not archiving on download; and the phone's
     * copy is app-private storage holding a complete GPS trace, so keeping it
     * after the server has it is retaining sensitive data for no reason.
     */
    override suspend fun confirmPulled(file: PulledFile) {
        val support = support
        val id = file.deviceFileId
        when {
            support == null || id == null -> Unit
            GarminSyncFileId.matches(id) -> releaseSyncFile(support, id)
            else -> id.toIntOrNull()?.let(support::archiveOnWatch)
        }
        val local = File(file.localPath)
        if (local.exists() && !local.delete()) {
            Log.w(TAG, "Could not delete ${file.localPath} after upload")
        }
    }

    /**
     * Unpack what [GarminSyncFileId] wrote and tell the watch it may reclaim
     * the file.
     *
     * A malformed id is logged and dropped rather than throwing. This runs after
     * the bytes are safely on the server, so the only thing at stake is whether
     * the watch keeps a copy it did not need to — and the recovery for that is
     * the next sync offering it again, not an exception on the upload path.
     */
    override suspend fun keepPulledOnDevice(file: PulledFile) {
        val support = support
        val id = file.deviceFileId
        if (support != null && id != null) {
            GarminSyncFileId.decode(id)?.let { support.rememberKeptOnWatch(it.id1, it.id2, it.typeName) }
        }
        val local = File(file.localPath)
        if (local.exists() && !local.delete()) {
            Log.w(TAG, "Could not delete ${file.localPath} after upload")
        }
    }

    private fun releaseSyncFile(support: GarminSupport, id: String) {
        val decoded = GarminSyncFileId.decode(id)
        if (decoded == null) {
            Log.w(TAG, "Cannot release a sync file from a malformed id: $id")
            return
        }
        support.markSyncedOnWatch(decoded.id1, decoded.id2, decoded.typeName)
    }

    // ── Pushing ──────────────────────────────────────────────────────────────

    /**
     * Send a server-produced file to the watch.
     *
     * The destination type comes from the file's own `file_id` message rather
     * than from [folder]. That is not distrust of the server — it is that
     * `folder` describes a *filesystem* destination for the USB/MTP path, and
     * BLE has no filesystem: `CreateFileMessage` addresses a type. `GARMIN/NewFiles`
     * carries workouts, schedules and race plans indiscriminately, so the folder
     * alone cannot name the type. The FIT header can, and the parser for it is
     * already vendored.
     */
    override suspend fun pushFile(
        folder: String,
        filename: String,
        bytes: ByteArray,
        lastInBatch: Boolean,
    ) = pushLock.withLock { pushFileLocked(folder, filename, bytes, lastInBatch) }

    /**
     * Send the next files the way a cable does, rather than the way Garmin
     * Connect does.
     *
     * The two BLE upload paths do not put a file in the same place.
     * `FileSyncService` hands the watch a typed object it files immediately: a
     * pushed workout turns up in `GARMIN/Workouts` under its own title, alone.
     * The legacy `CREATE_FILE` path addresses a numeric type whose
     * `InputToUnit` in `GarminDevice.xml` is `Garmin/NewFiles` -- the same inbox
     * `garmin-sync` writes to over USB, and the one the watch's own importer
     * scans as a batch.
     *
     * That distinction is what makes a training calendar build: a schedule binds
     * only to workouts delivered in the same batch, and over `FileSyncService`
     * there is no batch to be in. See AGENTS.md.
     *
     * Set it around a bundle and clear it in a `finally` -- a leaked flag would
     * quietly move every later push in the process onto the older protocol.
     */
    @Volatile
    private var forceLegacyPush = false

    /** See GarminSupport.takeActivitiesSyncedElsewhere. */
    fun takeActivitiesSyncedElsewhere(): Int = support?.takeActivitiesSyncedElsewhere() ?: 0

    fun setForceLegacyPush(wanted: Boolean) {
        forceLegacyPush = wanted
        Log.i(TAG, "pushes will use " + if (wanted) "the legacy NewFiles path" else "file-sync")
    }

    /**
     * The body of [pushFile], with [pushLock] already held.
     *
     * Split for the same reason [connectLocked] is: the lock is not reentrant,
     * and the transfer slots this guards are claimed deep inside the vendored
     * stack where taking it again would deadlock rather than fail visibly.
     */
    private suspend fun pushFileLocked(
        folder: String,
        filename: String,
        bytes: ByteArray,
        lastInBatch: Boolean,
    ) {
        val support = requireConnected()
        val fileType = GarminFileTypes.resolve(folder, filename, bytes)
            ?: error("Could not determine a Garmin file type for $filename in $folder")

        // Every FIT file goes the way Garmin's own apps send them, when the
        // watch speaks that protocol at all. Not only schedules any more: a
        // schedule names its workouts by their file_id, so a calendar built from
        // workouts that arrived by one route and a schedule that arrived by
        // another is asking the watch to join two sets of identities it had no
        // reason to relate. Sending everything one way removes the question.
        //
        // Falls back to the legacy path rather than failing. Courses and
        // workouts demonstrably import from it, so a watch that will not take a
        // file this way should still get the file.
        val typeName = fileType.typeName
        if (support.newSyncProtocol() && typeName != null && !forceLegacyPush) {
            Log.i(TAG, "pushing $filename as $typeName over file-sync (${bytes.size} bytes)")
            val accepted = CompletableDeferred<Boolean>()
            support.uploadFileViaFileSync("push $filename", bytes, typeName, lastInBatch) { ok ->
                accepted.complete(ok)
            }
            val ok = try {
                withTimeout(PUSH_TIMEOUT_MS) { accepted.await() }
            } catch (e: TimeoutCancellationException) {
                false
            }
            if (ok) {
                Log.i(TAG, "watch accepted $filename as $typeName (${bytes.size} bytes)")
                return
            }
            Log.w(TAG, "file-sync would not take $filename as $typeName; falling back to the legacy upload")
        }

        val signal = CompletableDeferred<Boolean>()
        uploadSignal = signal
        support.clearLastUploadRefusal()
        try {
            Log.i(TAG, "pushing $filename from $folder as $fileType (${bytes.size} bytes)")
            lastPushProgressAt = SystemClock.elapsedRealtime()
            support.uploadFile("push $filename", bytes, fileType)
            if (fileType == FileType.FILETYPE.SETTINGS) {
                // The vendored handler reports no progress for a settings file
                // at all (upstream hides its notification), so the signal below
                // would never fire and a file the watch took in full would be
                // reported refused after a minute — measured 2026-09-30 on the
                // Wi-Fi settings push. Its in-flight marker is cleared on both
                // the last block and a refusal, and the refusal is recorded, so
                // the two together are the answer.
                val idle = withTimeoutOrNull(PUSH_STALL_MS) {
                    while (support.isUploadInFlight()) delay(SETTINGS_POLL_MS)
                    true
                } ?: false
                val refusal = support.lastUploadRefusal
                if (idle && refusal == null) {
                    Log.i(TAG, "watch accepted $filename as $fileType (${bytes.size} bytes)")
                    return
                }
                error(
                    if (refusal != null) "The watch rejected $filename as $fileType — it failed $refusal"
                    else "The watch stopped answering while taking $filename",
                )
            }
            // Wait on progress, not a wall clock. A .prg over the legacy path
            // on a watch that negotiated a small MTU crawls — a 140 KB app took
            // nine minutes at MTU 23 on a fēnix 6X — but every second of it is
            // forward motion. A flat timeout gave up mid-transfer and reported
            // a refusal for a push that then completed on the watch anyway. So
            // give up only when the bytes actually stop.
            val succeeded = awaitUploadProgress(signal)
            if (succeeded) {
                Log.i(TAG, "watch accepted $filename as $fileType (${bytes.size} bytes)")
            }
            if (!succeeded) {
                // The watch says *why*, and the difference is the whole message:
                // UNSUPPORTED means this model will never take this kind of file
                // over Bluetooth and the cable is the answer, while NO_SPACE
                // clears itself once something is deleted. "The watch rejected
                // it" sent people looking for a corrupt file in both cases.
                val refusal = support.lastUploadRefusal
                error(
                    if (refusal == null) {
                        // No step recorded a reason, which is itself the
                        // finding: the transfer was abandoned somewhere that
                        // reports failure without one. The file type is named
                        // because resolving it wrongly is the other way a
                        // perfectly good file gets refused.
                        "The watch rejected $filename (sent as $fileType, no reason given)"
                    } else {
                        "The watch rejected $filename as $fileType — it failed $refusal"
                    }
                )
            }
        } finally {
            if (uploadSignal === signal) uploadSignal = null
        }
    }

    /**
     * Take a course off the watch, by the name the watch shows for it.
     *
     * Not a message the watch answers: a removal is a tombstone in the library
     * the phone serves during an Explore sync, so this marks it and kicks a
     * sync. True means the tombstone was recorded and a sync started, not that
     * the watch has finished acting on it: the watch never acknowledges a
     * removal, it just stops listing the course, which the next full sync's
     * digest is where that gets noticed.
     */
    suspend fun removeCourse(courseName: String): Boolean {
        val support = requireConnected()
        return support.removeCourseFromWatch(courseName)
    }

    /**
     * The connected watch's Garmin product number, null until its handshake has
     * said. Picks which Connect IQ build to install; see GarminSupport.
     */
    val productNumber: Int?
        get() = support?.productNumber?.takeIf { it >= 0 }

    /**
     * Side-load a Connect IQ app onto the watch and arm the phone's answer to
     * its configuration request.
     *
     * Media apps are relocated into an encrypted store on install; that is what
     * success looks like, not a failed transfer.
     */
    suspend fun installWatchApp(
        filename: String,
        bytes: ByteArray,
        config: WatchAppConfig.Config,
    ) {
        WatchAppConfig.set(config)
        // The folder is ignored for a .prg — BLE has no filesystem, and the
        // type comes from the extension — but the signature is shared with
        // every other push, so name the one a cable would use.
        pushFile(folder = "GARMIN/APPS", filename = filename, bytes = bytes, lastInBatch = true)
    }

    /**
     * Tell this phone what to answer when the watch app asks who it belongs to,
     * and which server the proxy may reach on its behalf.
     *
     * Set on connect (and whenever music settings change) so an app installed
     * on an earlier run can pick up the current values without being
     * reinstalled. Null means the phone has no music server as of [revision]:
     * the proxy is revoked, and the watch is told to sign out if it last
     * heard from an older revision.
     */
    fun setWatchAppConfig(config: WatchAppConfig.Config?, revision: Long) {
        if (config == null) {
            WatchAppConfig.signOut(revision)
        } else {
            WatchAppConfig.set(config.copy(revision = revision))
        }
    }

    // ── installed-app management ─────────────────────────────────────────────

    /**
     * One Connect IQ app the watch has installed. [type] is Garmin's own app
     * type number (7 = audio content provider, which is what Tracks Music is);
     * [id] is the opaque handle the watch names it by, needed to delete it.
     */
    data class InstalledApp(val id: ByteArray, val name: String, val type: Int) {
        val isAudioProvider: Boolean get() = type == AUDIO_CONTENT_PROVIDER
        /** Our own music app, as the watch lists it. */
        val isTracksMusic: Boolean get() = isAudioProvider && name.startsWith("Tracks")

        override fun equals(other: Any?): Boolean =
            other is InstalledApp && id.contentEquals(other.id)
        override fun hashCode(): Int = id.contentHashCode()
    }

    /**
     * The music apps on the watch — Tracks Music, Spotify and the like.
     *
     * Only audio providers, and only because that is all this is for: removing
     * and replacing the music app. Asking the watch for every app type instead
     * brings back its entire furniture — every activity profile, widget, face
     * and built-in — which is a lot of bytes over a 23-byte-MTU link and, worse,
     * a list that would offer a Delete button beside things that must never be
     * deleted.
     *
     * Filtered again on arrival: a real app carries a 16-byte store UUID, which
     * the watch's built-ins leave empty or zeroed.
     */
    suspend fun listInstalledApps(): List<InstalledApp> {
        val support = requireConnected()
        val result = CompletableDeferred<List<GdiInstalledAppsService.InstalledAppsService.InstalledApp>>()
        support.setAppListListener { result.complete(it) }
        try {
            support.requestInstalledApps(GdiInstalledAppsService.InstalledAppsService.AppType.AUDIO_CONTENT_PROVIDER)
            return withTimeout(APP_LIST_TIMEOUT_MS) { result.await() }
                .filter { isManageableApp(it) }
                .map { InstalledApp(it.storeAppId.toByteArray(), it.name, it.type.number) }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("The watch did not answer with its app list")
        } finally {
            support.setAppListListener(null)
        }
    }

    /** True for a music app the user installed and could sensibly remove. */
    private fun isManageableApp(app: GdiInstalledAppsService.InstalledAppsService.InstalledApp): Boolean {
        if (app.type.number != AUDIO_CONTENT_PROVIDER) return false
        val id = app.storeAppId.toByteArray()
        return id.size == STORE_APP_ID_BYTES && id.any { it.toInt() != 0 }
    }

    /**
     * Delete one app from the watch. Returns true when the watch confirms it;
     * false if it refused (usually because the app is currently open).
     */
    suspend fun deleteInstalledApp(app: InstalledApp): Boolean {
        val support = requireConnected()
        val type = GdiInstalledAppsService.InstalledAppsService.AppType.forNumber(app.type)
            ?: GdiInstalledAppsService.InstalledAppsService.AppType.UNKNOWN_APP_TYPE
        val result = CompletableDeferred<Boolean>()
        support.setAppDeleteListener { result.complete(it) }
        try {
            support.deleteInstalledApp(app.id, type)
            return withTimeout(APP_DELETE_TIMEOUT_MS) { result.await() }
        } catch (e: TimeoutCancellationException) {
            return false
        } finally {
            support.setAppDeleteListener(null)
        }
    }


    /**
     * Resolve an upload's outcome.
     *
     * The vendored upload state machine reports only through the install
     * notification, and the shim that receives it classifies success from
     * failure — see [GB.InstallOutcome]. An outcome it could not classify is
     * treated as failure: a push we cannot confirm must not be reported to the
     * server as delivered, because the server would then stop offering the file
     * and the workout would silently never reach the watch.
     */
    private fun onInstallProgress(outcome: GB.InstallOutcome, percentage: Int) {
        val signal = uploadSignal ?: return
        when (outcome) {
            GB.InstallOutcome.IN_PROGRESS -> {
                lastPushProgressAt = SystemClock.elapsedRealtime()
                Log.d(TAG, "push $percentage%")
            }
            GB.InstallOutcome.SUCCESS -> signal.complete(true)
            GB.InstallOutcome.FAILURE -> signal.complete(false)
            GB.InstallOutcome.UNKNOWN -> {
                Log.w(TAG, "Unclassifiable upload outcome at $percentage%; treating as failed")
                signal.complete(false)
            }
        }
    }

    /**
     * Wait for an upload to finish, giving up only once it stops progressing.
     *
     * The watch reports a percentage as each block lands; [lastPushProgressAt]
     * tracks the newest. As long as that keeps moving the transfer is alive,
     * however slow the link, so this only fails after [PUSH_STALL_MS] of true
     * silence — and never blocks longer than [PUSH_MAX_MS] as a backstop.
     */
    private suspend fun awaitUploadProgress(signal: CompletableDeferred<Boolean>): Boolean {
        val deadline = SystemClock.elapsedRealtime() + PUSH_MAX_MS
        while (true) {
            withTimeoutOrNull(PUSH_STALL_MS) { signal.await() }?.let { return it }
            val now = SystemClock.elapsedRealtime()
            if (now - lastPushProgressAt >= PUSH_STALL_MS) {
                Log.w(TAG, "upload stalled — no progress for ${PUSH_STALL_MS / 1000}s")
                return false
            }
            if (now >= deadline) {
                Log.w(TAG, "upload exceeded the ${PUSH_MAX_MS / 60000}-minute ceiling")
                return false
            }
        }
    }


    // ── Phone feeds ──────────────────────────────────────────────────────────

    override suspend fun sendNotification(notification: DeviceNotification) {
        support?.sendNotification(GarminNotifications.toSpec(notification))
    }

    override suspend fun dismissNotification(id: Int) {
        support?.deleteNotification(id)
    }

    override suspend fun setQuickReplies(replies: List<String>) {
        support?.setQuickReplies(replies.toTypedArray())
    }

    override suspend fun sendWeather(weather: WeatherReport) {
        support?.sendWeather(GarminWeatherEncoder.encode(weather))
    }

    override suspend fun sendMusic(track: MusicTrack?, state: MusicState?) {
        val support = support ?: return
        track?.let { support.sendMusicTrack(it.artist, it.album, it.title, state?.duration ?: 0) }
        state?.let {
            support.sendMusicState(it.isPlaying, it.position)
            it.volumePercent?.let { volume -> support.sendPhoneVolume(volume.toFloat()) }
        }
    }

    private fun requireConnected(): GarminSupport =
        support?.takeIf { it.isConnected }
            ?: throw DisconnectedException()

    private fun looksLikeGarmin(name: String?): Boolean =
        name != null && GARMIN_NAME_PATTERN.matcher(name).find()

    private companion object {
        const val TAG = "TracksGarmin"
        const val VENDOR_ID = "garmin"

        /**
         * Garmin watches advertise as their model name — "fenix 7X", "Forerunner
         * 965", "Instinct 2", "Venu 3" — with no common "Garmin" prefix, which
         * is why this is a list of families rather than one word. Case-insensitive
         * because the casing varies between models and firmware versions.
         */
        val GARMIN_NAME_PATTERN: Pattern = Pattern.compile(
            "(garmin|fenix|forerunner|instinct|venu|vivoactive|vívoactive|epix|enduro|" +
                "tactix|quatix|marq|descent|approach|edge|lily)",
            Pattern.CASE_INSENSITIVE
        )

        const val CONNECT_TIMEOUT_MS = 90_000L

        /**
         * A week of unsynced activities on a slow BLE link genuinely takes
         * tens of minutes. This bound exists to stop a wedged transfer holding
         * a foreground service open forever, not to express an expectation.
         */
        const val SYNC_TIMEOUT_MS = 45 * 60 * 1000L

        const val PUSH_TIMEOUT_MS = 5 * 60 * 1000L
        // Legacy-upload watchdog: fail after this much silence, not this much
        // total time, so a slow-but-live transfer runs to completion.
        private const val SETTINGS_POLL_MS = 200L
        const val PUSH_STALL_MS = 60 * 1000L
        const val PUSH_MAX_MS = 30 * 60 * 1000L
        const val APP_LIST_TIMEOUT_MS = 15 * 1000L
        const val APP_DELETE_TIMEOUT_MS = 15 * 1000L
        const val AUDIO_CONTENT_PROVIDER = 7

        /** A Connect IQ store id is a UUID; the built-ins have no real one. */
        const val STORE_APP_ID_BYTES = 16


    }
}

/**
 * Translate the vendored find-phone event into the vendor-neutral one.
 *
 * Garmin only ever produces START and STOP — the request message hard-codes
 * START — so the vibrate-only cases are upstream's other devices arriving
 * through the same event class. They are mapped rather than folded into RING,
 * because a device that asked for silence and got a ringtone is a worse bug
 * than one that asked for a ringtone and got nothing.
 *
 * UNKNOWN is dropped. It means a message this stack could not read, and
 * guessing that an unreadable message meant "ring" would make a parser bug
 * audible in a cinema.
 *
 * A plain function rather than a method: it is the whole of the translation,
 * every branch of it is something a person can hear, and out here it can be
 * tested without a watch or a Bluetooth adapter.
 */
internal fun findPhoneFor(event: GBDeviceEventFindPhone.Event): FindPhone? = when (event) {
    GBDeviceEventFindPhone.Event.START,
    GBDeviceEventFindPhone.Event.RING -> FindPhone.RING
    GBDeviceEventFindPhone.Event.START_VIBRATE,
    GBDeviceEventFindPhone.Event.VIBRATE -> FindPhone.VIBRATE
    GBDeviceEventFindPhone.Event.STOP -> FindPhone.STOP
    GBDeviceEventFindPhone.Event.UNKNOWN -> null
}
