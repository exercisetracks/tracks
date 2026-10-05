// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Consumer;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.devices.garmin.GarminCapability;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.CallSpec;
import nodomain.freeyourgadget.gadgetbridge.model.CannedMessagesSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.proto.garmin.GdiDeviceStatus;
import nodomain.freeyourgadget.gadgetbridge.proto.garmin.GdiFileSyncService;
import com.google.protobuf.ByteString;
import nodomain.freeyourgadget.gadgetbridge.proto.garmin.GdiInstalledAppsService;
import nodomain.freeyourgadget.gadgetbridge.proto.garmin.GdiSettingsService;
import nodomain.freeyourgadget.gadgetbridge.proto.garmin.GdiSmartProto;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.communicator.ICommunicator;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.communicator.v1.CommunicatorV1;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.communicator.v2.CommunicatorV2;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.deviceevents.CapabilitiesDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.deviceevents.FileDownloadedDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.deviceevents.IncomingFitDefinitionDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.deviceevents.MaxPacketSizeDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.deviceevents.NotificationSubscriptionDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.deviceevents.ProtobufResponseEvent;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.deviceevents.SupportedFileTypesDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.deviceevents.SyncFileDownloadedDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.deviceevents.WeatherRequestDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.FitLocalMessageBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.DeviceInformationMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.DownloadRequestMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.GFDIMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.MusicControlCapabilitiesMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.MusicControlEntityUpdateMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.SetDeviceSettingsMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.SetFileFlagsMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.SupportedFileTypesMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.SystemEventMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.status.NotificationSubscriptionStatusMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.status.SetFileFlagsStatusMessage;
import nodomain.freeyourgadget.gadgetbridge.util.ArrayUtils;
import nodomain.freeyourgadget.gadgetbridge.util.CompressionUtils;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

/**
 * The seam between Gadgetbridge's Garmin protocol code and Tracks.
 *
 * <p>Upstream's {@code GarminSupport} is the most entangled file in the entire
 * project — it reaches into the application singleton, the greenDAO session,
 * the preference screens, the notification manager and the activity UI, because
 * in Gadgetbridge it *is* the application's view of the watch. That is why it
 * is the one file in the Garmin package we do not vendor.
 *
 * <p>This is Tracks' replacement, wearing upstream's package and class name so
 * that the ~30 vendored call sites resolve without editing a single vendored
 * file. The protocol sequencing below is a deliberate port of upstream's, since
 * the watch is the thing that decides what is correct; what is replaced is
 * everything upstream does *with* the results — the database writes, the
 * broadcasts, the notifications, the FIT parsing.
 *
 * <p>Deciding what a downloaded FIT file *means* — sealing it, queueing it,
 * uploading it to the Tracks server — belongs to
 * {@code com.tracks.device.garmin.GarminIntegration}, on the other side of the
 * boundary, which is the only side that knows about servers and encryption.
 *
 * <p>The rule that keeps this honest: nothing in this class may import from
 * {@code com.tracks}. Events go out, commands come in.
 */
public class GarminSupport extends AbstractBTLESingleDeviceSupport implements ICommunicator.Callback {
    private static final Logger LOG = LoggerFactory.getLogger(GarminSupport.class);

    /**
     * Files the watch has told us about and we have not yet pulled.
     *
     * <p>Concurrent because the protocol code appends from the BLE callback
     * thread while the transfer loop drains it.
     */
    private final Queue<FileToDownload> filesToDownload = new ConcurrentLinkedQueue<>();

    /**
     * Copy-on-write for the same reason upstream uses it: {@link FitLocalMessageHandler}
     * registers and unregisters itself from inside {@link #onMessage}, which is
     * iterating this list at the time.
     */
    private final List<MessageHandler> messageHandlers = new CopyOnWriteArrayList<>();

    private final List<FileType> supportedFileTypes = new ArrayList<>();

    private final ProtocolBufferHandler protocolBufferHandler;
    private final FileTransferHandler fileTransferHandler;
    private final NotificationsHandler notificationsHandler;

    /**
     * Whether the watch has subscribed to notification delivery.
     *
     * The watch asks for this during its handshake; until it does, the
     * notification handler discards everything it is given. Tracked here purely
     * so the relay can log a cause rather than dropping in silence.
     */
    private volatile boolean notificationsSubscribed;

    /**
     * The watch's Garmin product number, from its DEVICE_INFORMATION message;
     * -1 until it has sent one. 3291 is a fēnix 6X Pro.
     *
     * It is what picks the Connect IQ build to install (see the phone's
     * WatchAppBundle): every model needs a build compiled for its own screen
     * and memory, and this is the one identifier the watch reports that the
     * user cannot rename.
     */
    private volatile int productNumber = -1;

    private ICommunicator communicator;
    private volatile FileToDownload currentlyDownloading;
    private boolean busyFetching;

    /**
     * Guards the download queue and everything that decides when a run ends.
     *
     * <p>Until the watchdog existed this needed no lock: every mutation
     * happened on the one BLE callback thread. The watchdog is a second thread
     * by necessity — it exists precisely for the case where the BLE thread is
     * never called again — so the state it touches has to be shared properly
     * rather than by convention.
     */
    private final Object downloadLock = new Object();

    /**
     * Fires only when a transfer has gone completely silent. Single-threaded and
     * daemon: it must never keep the process alive, since an expedition phone
     * killing a lingering thread is not a scenario worth having.
     */
    private final ScheduledExecutorService watchdogExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                final Thread thread = new Thread(runnable, "garmin-download-watchdog");
                thread.setDaemon(true);
                return thread;
            });
    private ScheduledFuture<?> downloadWatchdog;
    private volatile long lastDownloadActivityAt;

    /**
     * Long on purpose. Firing early abandons a file that was on its way; firing
     * late only makes a failure slower, and the run is already lost by then.
     */
    private static final long DOWNLOAD_SILENCE_TIMEOUT_MS = 45_000L;

    /**
     * How long an upload waits for the watch to close the loop on a file whose
     * bytes it already has.
     *
     * Measured at 100-260ms across a whole push, so this is generous by more
     * than an order of magnitude. It is a stuck-watch guard, not a deadline.
     */
    private static final long TRANSFER_COMPLETE_TIMEOUT_MS = 10_000L;

    /**
     * True between asking the watch for its protobuf file list and hearing the
     * end of it. Only meaningful on the newer sync protocol, where an empty
     * queue does not mean an empty watch.
     */
    private volatile boolean awaitingFileList;

    /**
     * Fires if a file-list request is never answered. See
     * {@link #onFileListWatchdogFired} — this is the net under defaulting the
     * newer protocol on.
     */
    private ScheduledFuture<?> fileListWatchdog;

    /**
     * The opening directory request of a run, still unanswered.
     *
     * <p>Watched because a watch that never answers it left the run waiting on
     * the app's whole sync timeout: on the newer protocol the file listing is
     * only requested once the directory arrives, so nothing else ever moved.
     * Measured 2026-09-30 on a fenix 6X, straight after a Bluetooth restart —
     * the request went out, an unrelated status message failed to parse, and
     * the sync sat silent until it was abandoned.
     */
    private volatile boolean awaitingDirectory;
    /** Set once this run has asked for the protobuf listing, so a late directory cannot ask twice. */
    private volatile boolean fileListRequestedThisRun;
    private ScheduledFuture<?> directoryWatchdog;
    private static final long DIRECTORY_TIMEOUT_MS = 20_000L;
    private volatile long lastFileListActivityAt;

    /**
     * Short, unlike {@link #DOWNLOAD_SILENCE_TIMEOUT_MS}. Nothing is in flight
     * while we wait — a watch that knows the protocol answers the first page in
     * well under a second, so this is not a transfer that might be slow, it is
     * a question that will never be answered.
     */
    private static final long FILE_LIST_TIMEOUT_MS = 20_000L;

    /**
     * Which file-sync protocol this connection speaks, and whether its service
     * channels are multi-link reliable. Decided once, when the communicator is
     * chosen, and read everywhere after that — see {@link #decideSyncProtocol}.
     *
     * <p>Fields rather than preference reads on each call because the answer
     * must not change under a live connection. {@link #mlrEnabled} in
     * particular decides how the main GFDI channel is re-registered when the
     * watch closes it, so a value that moved mid-connection could re-open the
     * one channel everything else depends on in a mode the watch is not in.
     */
    private volatile boolean newSyncProtocol;
    private volatile boolean mlrEnabled;

    /** Whether we have already asked for the fast radio. See {@link #updateConnectionPriority}. */
    private boolean fastConnection;

    /**
     * Type code to type name, as the watch states it. See {@link #nameType}.
     *
     * <p>Loaded from {@link #PREF_TYPE_NAMES_BY_CODE} at the start of every
     * connection and written back whenever a new mapping is learned. In-memory
     * only was not enough: the newer protocol's listing states a code's name
     * only on some entry, not necessarily one this connection ever sees, so a
     * cache that started empty on every reconnect left some codes permanently
     * unresolved. Measured on this fenix — the exact same watch file resolved
     * to "UNKNOWN" on one connection and "FIT_TYPE_60" on the next, which is
     * worse than cosmetic: the two names produce two different local paths, so
     * the file was pulled twice, and the first, mis-named copy became a
     * local-storage orphan nothing would ever revisit again.
     */
    private final Map<Integer, String> typeNamesByCode = new HashMap<>();

    private static final String PREF_TYPE_NAMES_BY_CODE = "garmin_type_names_by_code";

    /** Output paths already taken this run. See {@link #queueForThisRun}. */
    private final Set<String> queuedThisRun = new HashSet<>();

    /**
     * Whether a sync run is outstanding. Distinct from {@link #busyFetching},
     * which only becomes true once a file is actually moving — a run that finds
     * nothing to pull still has to end.
     */
    private boolean syncRequested;

    /** Guards {@link #signalWatchReady} so a run starts at most once per connection. */
    private boolean watchReadySignalled;

    /**
     * Repeat-storm detection. See {@link #isRepeatStorm}. Touched only from the
     * BLE callback thread, which is the single thread {@link #onMessage} runs on.
     */
    private GFDIMessage.GarminMessage stormType;
    private long stormWindowStart;
    private int stormCount;

    /**
     * Twenty of the same message in three seconds is far outside anything a
     * healthy exchange produces — a file transfer sends many fragments, but as
     * FILE_TRANSFER_DATA with real progress, and the counter resets whenever a
     * different type arrives.
     */
    private static final int STORM_LIMIT = 20;
    private static final long STORM_WINDOW_MS = 3_000L;
    private Set<GarminCapability> watchCapabilities = EnumSet.noneOf(GarminCapability.class);

    public GarminSupport() {
        // The base class logs against the concrete subclass, so BLE transport
        // messages are attributable to the device driver that caused them.
        super(LOG);

        addSupportedService(CommunicatorV1.UUID_SERVICE_GARMIN_GFDI_V0);
        addSupportedService(CommunicatorV1.UUID_SERVICE_GARMIN_GFDI_V1);
        addSupportedService(CommunicatorV2.UUID_SERVICE_GARMIN_ML_GFDI);

        protocolBufferHandler = new ProtocolBufferHandler(this);
        fileTransferHandler = new FileTransferHandler(this);
        notificationsHandler = new NotificationsHandler();

        // Registration order is upstream's and is load-bearing: the first
        // handler to produce a follow-up wins, so file transfer must see a
        // message before the protobuf handler gets a chance to claim it.
        messageHandlers.add(fileTransferHandler);
        messageHandlers.add(protocolBufferHandler);
        messageHandlers.add(notificationsHandler);
    }

    @Override
    public void setContext(final GBDevice gbDevice, final BluetoothAdapter btAdapter, final Context context) {
        super.setContext(gbDevice, btAdapter, context);
        protocolBufferHandler.setContext(gbDevice, btAdapter, context);
    }

    public ProtocolBufferHandler getProtocolBufferHandler() {
        return protocolBufferHandler;
    }

    // ── What Tracks subscribes to ────────────────────────────────────────────

    /** Notified as bytes arrive, so Tracks can show real progress rather than a spinner. */
    public interface DownloadProgressListener {
        void onProgress(int percent);
    }

    /**
     * Told when a sync run begins and ends.
     *
     * <p>Separate from the device-event fan-out because "the queue is empty" is
     * not something the watch says — it is something this class concludes — and
     * Tracks needs it to know when it may stop holding a foreground service.
     */
    public interface SyncListener {
        /** The watch is now initialised and will accept commands. */
        void onWatchReady();

        /** Every queued file has been pulled (or skipped). */
        void onSyncFinished();
    }

    /**
     * The watch has opened something that controls phone music.
     *
     * <p>It announces this by asking what commands the phone supports, and then
     * expects to be told what is playing — it does not ask a second time. Tracks
     * only ever pushed music on connect and when the track changed, so a watch
     * whose music screen was opened later than that showed nothing at all, and
     * the buttons had nothing to act on.
     */
    public interface MusicRefreshListener {
        void onMusicRefreshWanted();
    }

    private volatile DownloadProgressListener progressListener;
    private volatile SyncListener syncListener;
    private volatile MusicRefreshListener musicRefreshListener;

    public void setDownloadProgressListener(final DownloadProgressListener listener) {
        this.progressListener = listener;
    }

    public void setSyncListener(final SyncListener listener) {
        this.syncListener = listener;
    }

    public void setMusicRefreshListener(final MusicRefreshListener listener) {
        this.musicRefreshListener = listener;
    }

    // ── Connection lifecycle ─────────────────────────────────────────────────

    @Override
    public boolean useAutoConnect() {
        // Let the controller reconnect when the watch comes back into range.
        // The alternative is waking the CPU to scan, which is the single
        // largest avoidable battery cost in this whole layer.
        return true;
    }

    /**
     * Negotiate the transport, once services have been discovered.
     *
     * <p>Garmin speaks two incompatible GFDI transports and which one a watch
     * offers depends on its firmware generation, so this is try-V2-then-V1 in
     * upstream's order — V2 is the multi-link protocol on current hardware.
     * Neither matching is not an error worth crashing on: it means the thing we
     * connected to is not a Garmin watch.
     */
    @NonNull
    @Override
    protected TransactionBuilder initializeDevice(@NonNull final TransactionBuilder builder) {
        builder.setDeviceState(GBDevice.State.INITIALIZING);

        // A larger MTU is the difference between a FIT file transferring in
        // minutes and in tens of minutes, and fewer, larger packets is also
        // less radio time — it is a battery win as well as a speed one.
        builder.requestMtu(515);

        // Ask for the 2M PHY, which Garmin Connect uses: at twice the symbol
        // rate more packets fit in each connection event, and this watch pins
        // the event interval at 45 ms, so packets-per-event is the throughput.
        // The watch may decline, in which case the link stays on 1M — harmless.
        builder.setPreferredPhy(
                BluetoothDevice.PHY_LE_2M_MASK,
                BluetoothDevice.PHY_LE_2M_MASK,
                BluetoothDevice.PHY_OPTION_NO_PREFERRED);

        final CommunicatorV2 communicatorV2 = new CommunicatorV2(this);
        if (communicatorV2.initializeDevice(builder)) {
            communicator = communicatorV2;
            decideProtocols(true);
            return builder;
        }

        final CommunicatorV1 communicatorV1 = new CommunicatorV1(this);
        if (communicatorV1.initializeDevice(builder)) {
            communicator = communicatorV1;
            decideProtocols(false);
            return builder;
        }

        LOG.warn("Found neither a V1 nor a V2 Garmin GFDI service; this is not a Garmin watch");
        builder.setDeviceState(GBDevice.State.NOT_CONNECTED);
        return builder;
    }

    @Override
    public void onMtuChanged(final BluetoothGatt gatt, final int mtu, final int status) {
        super.onMtuChanged(gatt, mtu, status);
        if (status != BluetoothGatt.GATT_SUCCESS) {
            return;
        }
        // 23 is the BLE minimum; anything below it is a controller reporting
        // nonsense, and passing it on would size our packets to zero payload.
        if (mtu < 23) {
            LOG.warn("Ignoring MTU of {}, below the BLE minimum", mtu);
            return;
        }
        final ICommunicator comm = communicator;
        if (comm != null) {
            comm.onMtuChanged(mtu);
        }
        // Keep the upload chunk within one MTU's time budget — an app (.prg)
        // install over the legacy path stalls out otherwise at a small MTU.
        fileTransferHandler.setLinkMtu(mtu);
    }

    @Override
    public boolean onCharacteristicChanged(final BluetoothGatt gatt,
                                           final BluetoothGattCharacteristic characteristic,
                                           final byte[] value) {
        if (super.onCharacteristicChanged(gatt, characteristic, value)) {
            return true;
        }
        final ICommunicator comm = communicator;
        return comm != null && comm.onCharacteristicChanged(gatt, characteristic, value);
    }

    @Override
    public void onConnectionStateChange(final BluetoothGatt gatt, final int status, final int newState) {
        super.onConnectionStateChange(gatt, status, newState);
        final ICommunicator comm = communicator;
        if (comm != null) {
            comm.onConnectionStateChange(gatt, status, newState);
        }
    }

    @Override
    public void dispose() {
        synchronized (ConnectionMonitor) {
            final ICommunicator comm = communicator;
            if (comm != null) {
                comm.dispose();
            }
            communicator = null;
            // The subscription belongs to the connection, not to the watch: the
            // next one re-requests it during its handshake. Leaving this set
            // would have the relay believe an unsubscribed watch was listening.
            notificationsSubscribed = false;
            notificationsHandler.setEnabled(false);
            // A disconnect mid-transfer must not leave the next connection
            // believing a download is still in flight, or the queue never moves.
            synchronized (downloadLock) {
                cancelDownloadWatchdog();
                cancelFileListWatchdog();
                currentlyDownloading = null;
                busyFetching = false;
                syncRequested = false;
                watchReadySignalled = false;
                awaitingFileList = false;
                filesToDownload.clear();
                queuedThisRun.clear();
                typeNamesByCode.clear();
            }
            watchdogExecutor.shutdownNow();
            super.dispose();
        }
    }

    public void setCommunicator(final ICommunicator communicator) {
        this.communicator = communicator;
    }

    public ICommunicator getCommunicator() {
        return communicator;
    }

    // ── Message dispatch ─────────────────────────────────────────────────────

    /**
     * A GFDI message arrived from the watch.
     *
     * <p>Handlers are tried in registration order and the first to produce a
     * follow-up wins, which is upstream's ordering and matters: a handler both
     * validates the incoming payload and decides the reply, so running them all
     * would risk two replies to one message.
     *
     * <p>A handler that throws is logged and skipped rather than allowed to
     * take the connection down. Firmware we have not seen before should degrade
     * to "some messages unhandled", not "sync impossible in the field".
     */
    @Override
    public void onMessage(final byte[] message) {
        if (message == null) {
            return;
        }

        final GFDIMessage parsed = GFDIMessage.parseIncoming(message);
        if (parsed == null) {
            LOG.error("Could not parse an incoming GFDI message of {} bytes", message.length);
            return;
        }

        // Kept because upstream has it and the first real-hardware session
        // proved why: without a record of what the watch actually said, a
        // misbehaving exchange is indistinguishable from a firmware quirk.
        LOG.debug("INCOMING {}: {}", parsed.getGarminMessage(), GB.hexdump(message));

        // Any message at all is evidence the watch is still answering, which is
        // all the file-list watchdog needs to hear. Stamping here rather than
        // counting listed files, because a page of the listing can legitimately
        // be empty and counting files would read that as silence.
        if (awaitingFileList) {
            lastFileListActivityAt = SystemClock.elapsedRealtime();
        }

        if (isRepeatStorm(parsed)) {
            return;
        }

        if (parsed instanceof DeviceInformationMessage) {
            productNumber = productNumberOf(message);
            LOG.info("Watch is Garmin product {}", productNumber);
        }

        // The watch asking what music commands the phone supports is the only
        // notice it gives that its music screen is open. Upstream answers the
        // question and stops there, which is correct as far as the protocol
        // goes and useless in practice: the answer says what the phone *can*
        // do, never what is playing. Push the current track behind the ACK.
        if (parsed instanceof MusicControlCapabilitiesMessage) {
            final MusicRefreshListener listener = musicRefreshListener;
            if (listener != null) {
                LOG.debug("Watch asked for music capabilities; refreshing what is playing");
                listener.onMusicRefreshWanted();
            }
        }

        GFDIMessage followup = null;
        for (final MessageHandler handler : messageHandlers) {
            try {
                followup = handler.handle(parsed);
            } catch (final Exception e) {
                LOG.error("{} failed handling {}", handler.getClass().getSimpleName(), parsed, e);
                continue;
            }
            if (followup != null) {
                break;
            }
        }

        // Order matters and is upstream's: acknowledge first, because a handler
        // may have downgraded the status after checking the payload's
        // integrity, then the reply the parsed message itself carries, then any
        // follow-up the handler produced.
        sendAck("ack", parsed);
        sendOutgoingMessage("reply", parsed);
        sendOutgoingMessage("followup", followup);

        // A TransferComplete's answer is only now on the wire. Anything holding
        // its next upload request back until that happened may go -- see
        // FileSyncServiceHandler#flushAnsweredTransfers for why the release has
        // to happen here rather than where the answer was built.
        protocolBufferHandler.getFileSyncServiceHandler().flushAnsweredTransfers();

        for (final GBDeviceEvent event : parsed.getGBDeviceEvent()) {
            evaluateGBDeviceEvent(event);
        }

        processDownloadQueue();
        updateConnectionPriority();
    }

    /**
     * Ask for a faster radio while bytes are moving, and only while.
     *
     * <p>This is the difference between a sync that finishes and one the user
     * gives up on. Measured on a fenix 6X at the connection's default interval:
     * one ~380-byte packet every ~200ms, which is <b>1.9 KB/s</b>. The watch's
     * own directory listed 238 files — a single 1.1 MB activity took ten
     * minutes at that rate, and the full backlog would have taken hours. It
     * looked exactly like a hung transfer, and it was not: it was working
     * perfectly, at a speed no one would wait for.
     *
     * <p>Counter-intuitively this is also the battery-conscious choice, which
     * matters for a tool meant to be carried into the field. A high-priority
     * connection costs more per second, but the radio is up for a small
     * fraction of the time, and the phone and watch both return to idle sooner.
     * The expensive thing is not a fast transfer; it is a slow one.
     *
     * <p>Derived from the transfer state rather than set at each call site, and
     * re-evaluated after every message, so it cannot drift out of step with
     * what is actually happening. The {@code fastConnection} check keeps it to
     * one request per transition — some Android stacks handle a stream of
     * priority requests badly, and upstream has bug reports of connections
     * dropping outright because of them.
     */
    private void updateConnectionPriority() {
        // The whole run, not the individual file. An earlier version tracked
        // only what was in flight and flapped between high and balanced in the
        // gap between one file finishing and the next being requested — on the
        // newer protocol that gap is about fifteen milliseconds, so a 357-file
        // sync would have issued seven hundred priority changes. Some Android
        // stacks drop connections over far less.
        final boolean transferring = syncRequested
                || awaitingFileList
                || currentlyDownloading != null
                || fileTransferHandler.isDownloading()
                || fileTransferHandler.isUploading();
        if (transferring == fastConnection) {
            return;
        }
        if (getDevice() == null || !getDevice().isConnected()) {
            return;
        }
        fastConnection = transferring;
        final int priority = transferring
                ? BluetoothGatt.CONNECTION_PRIORITY_HIGH
                : BluetoothGatt.CONNECTION_PRIORITY_BALANCED;
        LOG.debug("Requesting {} connection priority", transferring ? "high" : "balanced");
        try {
            createTransactionBuilder("connection priority")
                    .requestConnectionPriority(priority)
                    .queue();
        } catch (final Exception e) {
            // Never fatal: a refused priority request means a slow sync, not a
            // broken one, and the transfer is already under way.
            LOG.warn("Could not change the connection priority", e);
        }
    }

    /**
     * Stop answering a message the watch will not stop asking.
     *
     * <p>Not a protocol guard — a configuration guard. If a second companion app
     * is still connected to the same watch, both receive every GFDI message and
     * both reply. The watch gets two answers to one question, does not accept
     * either, and asks again; measured on a fenix 6X with Gadgetbridge still
     * installed, that settles into roughly eight round trips per second and
     * stays there. On a watch meant to last two weeks that is a battery
     * emergency, and neither app will ever notice on its own.
     *
     * <p>So the rule is: if the same message type arrives {@link #STORM_LIMIT}
     * times inside {@link #STORM_WINDOW_MS} with nothing else in between, stop
     * replying to it and say clearly why. Backing off is safe — the watch simply
     * does not get that one feature — where continuing is not.
     *
     * <p>The counter resets on any other message type, so an ordinary burst
     * (fragments of one file, a run of status messages) never trips it.
     */
    private boolean isRepeatStorm(final GFDIMessage parsed) {
        final GFDIMessage.GarminMessage type = parsed.getGarminMessage();

        // Streaming types are exempt, and this exemption is the difference
        // between a guard and a bug: a multi-megabyte activity file arrives as
        // hundreds of consecutive FILE_TRANSFER_DATA messages, far faster than
        // the threshold below, and tripping on those would break the one thing
        // this app exists to do.
        if (type == GFDIMessage.GarminMessage.FILE_TRANSFER_DATA
                || type == GFDIMessage.GarminMessage.FIT_DATA
                || type == GFDIMessage.GarminMessage.FIT_DEFINITION
                || type == GFDIMessage.GarminMessage.PROTOBUF_REQUEST
                || type == GFDIMessage.GarminMessage.PROTOBUF_RESPONSE) {
            return false;
        }

        final long now = System.currentTimeMillis();

        if (type != stormType || now - stormWindowStart > STORM_WINDOW_MS) {
            stormType = type;
            stormWindowStart = now;
            stormCount = 1;
            return false;
        }

        stormCount++;
        if (stormCount < STORM_LIMIT) {
            return false;
        }

        if (stormCount == STORM_LIMIT) {
            LOG.error(
                    "Received {} {} messages in {}ms and the watch is not accepting our replies. "
                            + "This almost always means another companion app (Gadgetbridge, Garmin "
                            + "Connect) is still connected to this watch — two apps answering one "
                            + "watch will flatten its battery. Not replying to {} again on this "
                            + "connection.",
                    stormCount, type, now - stormWindowStart, type);
        }
        return true;
    }

    /**
     * Handle the events that are this layer's own business, and let everything
     * else out to Tracks.
     *
     * <p>The split is the boundary in miniature. An event that changes protocol
     * state — the packet size, which file types exist, whether notifications
     * are subscribed — is answered here, because the watch is waiting on a
     * reply and Tracks has no opinion. An event that carries *information* —
     * battery level, firmware version, a downloaded file — falls through to
     * {@code super}, which fans it out to whoever is listening on the Tracks
     * side.
     */
    @Override
    public void evaluateGBDeviceEvent(final GBDeviceEvent deviceEvent) {
        if (deviceEvent instanceof CapabilitiesDeviceEvent) {
            watchCapabilities = ((CapabilitiesDeviceEvent) deviceEvent).capabilities;
            completeInitialization();
            return;
        }

        if (deviceEvent instanceof ProtobufResponseEvent) {
            final ProtobufResponseEvent event = (ProtobufResponseEvent) deviceEvent;
            sendOutgoingMessage(
                    "protobuf response " + event.messageId,
                    protocolBufferHandler.prepareProtobufResponse(event.payload, event.messageId));
            return;
        }

        if (deviceEvent instanceof NotificationSubscriptionDeviceEvent) {
            final boolean enable = ((NotificationSubscriptionDeviceEvent) deviceEvent).enable;
            notificationsHandler.setEnabled(enable);
            // Mirrored here so the relay can say *why* it dropped something.
            // The handler owns the flag but exposes no getter, and it is
            // vendored code we do not edit.
            notificationsSubscribed = enable;
            LOG.info("Watch {} notification delivery", enable ? "subscribed to" : "unsubscribed from");
            // Always ENABLED, which is not the same as the watch's own flag and
            // is the bug this replaces. The status field answers "can this phone
            // relay notifications at all?"; `enable` answers "am I subscribed
            // right now?". Echoing the watch's answer back at it conflated the
            // two, and told a watch that happened to connect with notifications
            // off that the *phone* could not do them — after which it had no
            // reason to ever ask again, and the relay stayed dead until a
            // re-pair. Upstream reports its own preference here, defaulting on;
            // this is that, with the user's notification-listener grant as the
            // thing that actually decides whether anything gets through.
            sendOutgoingMessage("notification subscription", new NotificationSubscriptionStatusMessage(
                    GFDIMessage.Status.ACK,
                    NotificationSubscriptionStatusMessage.NotificationStatus.ENABLED,
                    enable,
                    0));
            return;
        }

        if (deviceEvent instanceof MaxPacketSizeDeviceEvent) {
            fileTransferHandler.setMaxPacketSize(((MaxPacketSizeDeviceEvent) deviceEvent).getMaxPacketSize());
            return;
        }

        if (deviceEvent instanceof SupportedFileTypesDeviceEvent) {
            supportedFileTypes.clear();
            supportedFileTypes.addAll(((SupportedFileTypesDeviceEvent) deviceEvent).getSupportedFileTypes());
            // The watch's own answer to "what file types do you take?", logged in
            // full. Each entry pairs the numeric type/subtype a BLE push has to
            // address with the name GarminDevice.xml uses for the same thing, so
            // the two tables can be compared without guessing. Types the parser
            // does not recognise are already logged separately, by
            // SupportedFileTypesStatusMessage, at the point it drops them.
            for (final FileType fileType : supportedFileTypes) {
                final FileType.FILETYPE t = fileType.getFileType();
                LOG.info("watch accepts file type {}/{} ({}) = {}",
                        t.getType(), t.getSubType(), fileType.getGarminDeviceFileType(), t);
            }
            // *This* is when the watch is actually ready to be synced, and it is
            // the last piece of the handshake to arrive. Measured on a fenix 6X:
            // the reply lands 250ms after the request, and an earlier version
            // announced readiness as soon as the request was *sent* — so a sync
            // started, found no known file types, and gave up six milliseconds
            // before the answer arrived.
            signalWatchReady();
            return;
        }

        if (deviceEvent instanceof IncomingFitDefinitionDeviceEvent) {
            // The watch is about to stream FIT records and has just told us
            // their shape. The handler unregisters itself once the stream ends.
            messageHandlers.add(new FitLocalMessageHandler(
                    this, ((IncomingFitDefinitionDeviceEvent) deviceEvent).getRecordDefinitions()));
            return;
        }

        if (deviceEvent instanceof FileDownloadedDeviceEvent) {
            // Clear the in-flight slot before the fan-out: a listener that
            // throws must not wedge the queue behind a file that already
            // finished transferring.
            currentlyDownloading = null;
            final FileDownloadedDeviceEvent event = (FileDownloadedDeviceEvent) deviceEvent;
            if (!event.success) {
                LOG.warn("Download failed for {}", event.directoryEntry);
                return;
            }
            // Deliberately *not* archived on the watch here, which is where
            // upstream archives it. See archiveOnWatch: until Tracks has the
            // bytes somewhere durable, the watch holds the only copy that
            // survives losing the phone.
        }

        if (deviceEvent instanceof WeatherRequestDeviceEvent) {
            // The watch is asking; Tracks answers by calling sendWeather with
            // whatever the weather feed last supplied. Falls through so the
            // Tracks side sees the request.
            LOG.debug("Watch requested weather");
        }

        super.evaluateGBDeviceEvent(deviceEvent);
    }

    /**
     * Turn on the Explore sync service for this watch.
     *
     * <p>The vendored {@code ProtocolBufferHandler} gates Explore sync behind a
     * per-device preference that defaults off, which upstream exposes as a
     * settings-screen switch. Tracks has no such screen — see
     * {@code DevicePrefs} on why — so the decision is made here instead of
     * being left to a toggle nobody can reach.
     *
     * <p>On, because Explore sync is how saved courses and waypoints move in
     * both directions; declining it was the reason a course pushed from the
     * phone could never be taken back off. Written rather than read with a
     * different default so the gate itself stays exactly as upstream wrote it,
     * and a future re-vendor of that file needs no patch.
     */
    private void enableExploreSync() {
        getDevicePrefs().getPreferences().edit()
                .putBoolean("garmin_exploresync", true)
                .apply();
    }

    /**
     * Bring the watch to a usable state, once it has told us what it can do.
     *
     * <p>This is upstream's sequence and the ordering is the watch's
     * requirement, not a preference: file types must be requested before a
     * directory listing means anything, and the sync-ready system event is what
     * some models wait for before they will talk at all.
     */
    private void completeInitialization() {
        enableExploreSync();
        if (getDevice() != null && getDevice().isInitialized()) {
            LOG.warn("Already initialised; ignoring a second capabilities message");
            return;
        }

        sendOutgoingMessage("request supported file types", new SupportedFileTypesMessage());
        sendDeviceSettings();
        sendOutgoingMessage("set time", new SystemEventMessage(SystemEventMessage.GarminSystemEventType.TIME_UPDATED, 0));
        sendOutgoingMessage("set sync ready", new SystemEventMessage(SystemEventMessage.GarminSystemEventType.SYNC_READY, 0));
        enableBatteryLevelUpdate();

        if (watchCapabilities.contains(GarminCapability.REALTIME_SETTINGS)) {
            sendOutgoingRealtimeSettingsInit();
        }

        if (getDevice() != null) {
            getDevice().setUpdateState(GBDevice.State.INITIALIZED, getContext());
        }
        // Deliberately not signalling readiness here — see the
        // SupportedFileTypesDeviceEvent branch. The link is up, but the watch
        // has not yet said what it can offer, and a sync started now finds
        // nothing.
    }

    /**
     * Announce that the watch will accept a sync. Fires at most once per
     * connection; a watch that re-sends its file types must not restart a run.
     */
    private void signalWatchReady() {
        if (watchReadySignalled) {
            return;
        }
        watchReadySignalled = true;
        startExploreSync();
        final SyncListener listener = syncListener;
        if (listener != null) {
            try {
                listener.onWatchReady();
            } catch (final Exception e) {
                LOG.error("Sync listener failed on watch-ready", e);
            }
        }
    }

    /**
     * Tell the watch how to behave towards its companion.
     *
     * <p>Auto-upload on, so activities are offered as soon as they finish
     * rather than waiting for us to ask. Weather conditions on, because Tracks
     * pushes a forecast from the phone. Weather *alerts* off: they arrive from
     * Garmin's own service, which is exactly the dependency a self-hosted app
     * exists to remove.
     */
    private void sendDeviceSettings() {
        final Map<SetDeviceSettingsMessage.GarminDeviceSetting, Object> settings = new LinkedHashMap<>(3);
        settings.put(SetDeviceSettingsMessage.GarminDeviceSetting.AUTO_UPLOAD_ENABLED, true);
        settings.put(SetDeviceSettingsMessage.GarminDeviceSetting.WEATHER_CONDITIONS_ENABLED, true);
        settings.put(SetDeviceSettingsMessage.GarminDeviceSetting.WEATHER_ALERTS_ENABLED, false);
        sendOutgoingMessage("send device settings", new SetDeviceSettingsMessage(settings));
    }

    private void enableBatteryLevelUpdate() {
        sendProtobufRequest("enable battery updates", GdiSmartProto.Smart.newBuilder()
                .setDeviceStatusService(
                        GdiDeviceStatus.DeviceStatusService.newBuilder()
                                .setRemoteDeviceBatteryStatusRequest(
                                        GdiDeviceStatus.DeviceStatusService.RemoteDeviceBatteryStatusRequest.newBuilder()))
                .build());
    }

    /**
     * Watches with the realtime-settings service expect a locale before they
     * will render their settings screens. The region is not derivable from the
     * phone locale — it is Garmin's own partition of the world — so "us" is a
     * placeholder that no Tracks feature depends on.
     */
    private void sendOutgoingRealtimeSettingsInit() {
        final String language = Locale.getDefault().getLanguage();
        final String country = Locale.getDefault().getCountry();
        final String localeString = language + "_" + country.toUpperCase(Locale.ROOT);
        sendProtobufRequest("init realtime settings", GdiSmartProto.Smart.newBuilder()
                .setSettingsService(
                        GdiSettingsService.SettingsService.newBuilder()
                                .setInitRequest(
                                        GdiSettingsService.InitRequest.newBuilder()
                                                .setLanguage(localeString.length() == 5 ? localeString : "en_US")
                                                .setRegion("us")))
                .build());
    }

    // ── Pulling files off the watch ──────────────────────────────────────────

    /**
     * Ask the watch for its directory listing, which is what populates the
     * download queue.
     *
     * <p>Even when the newer protobuf sync is in use this still goes out,
     * because requesting a download is also what makes the watch flush its
     * in-progress monitoring file — skip it and the last few hours of data
     * arrive truncated.
     */
    public void fetchRecordedData() {
        if (supportedFileTypes.isEmpty() && !newSyncProtocol()) {
            // Nothing to ask for. Finish rather than return: the caller is
            // suspended waiting for this run to end, and returning silently
            // leaves it there until its timeout — which on device presented as
            // a spinner that never stopped.
            LOG.warn("Watch has reported no supported file types; nothing to sync");
            finishFileSync();
            return;
        }
        syncRequested = true;
        awaitingDirectory = true;
        fileListRequestedThisRun = false;
        sendOutgoingMessage("fetch recorded data", fileTransferHandler.initiateDownload());
        directoryWatchdog = schedule(this::onDirectoryWatchdogFired, DIRECTORY_TIMEOUT_MS);
        updateConnectionPriority();
    }

    /**
     * The directory never came. On the newer protocol it was only ever a
     * go-ahead (see {@link #addFileToDownloadList(FileTransferHandler.DirectoryEntry)}),
     * so the listing is asked for directly; the cost is that the in-progress
     * monitoring file may not have been flushed, and the next sync picks that
     * up. On the older protocol the directory is the listing, so the run ends
     * now rather than at the app's timeout.
     */
    private void onDirectoryWatchdogFired() {
        directoryWatchdog = null;
        if (!awaitingDirectory) {
            return;
        }
        awaitingDirectory = false;
        if (newSyncProtocol()) {
            LOG.warn("No directory after {}s; asking for the file list directly", DIRECTORY_TIMEOUT_MS / 1000);
            android.util.Log.w("TracksFileSync", "watch did not answer the directory request; listing directly");
            requestFileListOnce();
        } else {
            LOG.warn("No directory after {}s; ending the run", DIRECTORY_TIMEOUT_MS / 1000);
            finishFileSync();
        }
    }

    private void requestFileListOnce() {
        if (fileListRequestedThisRun) {
            return;
        }
        fileListRequestedThisRun = true;
        awaitingFileList = true;
        armFileListWatchdog();
        sendProtobufRequest("request file list", GdiSmartProto.Smart.newBuilder()
                .setFileSyncService(
                        protocolBufferHandler.getFileSyncServiceHandler().requestFileList())
                .build());
    }

    /**
     * Drive one file at a time off the queue.
     *
     * <p>Serial by construction: the transfer protocol has a single in-flight
     * file, so {@link #currentlyDownloading} is both the guard and the state.
     * Called after every incoming message because that is when the queue can
     * have changed — either a directory listing just added entries, or the file
     * that was in flight just finished.
     */
    private void processDownloadQueue() {
        synchronized (downloadLock) {
            processDownloadQueueLocked();
        }
    }

    private void processDownloadQueueLocked() {
        if (currentlyDownloading != null) {
            return;
        }

        while (!filesToDownload.isEmpty()) {
            if (!busyFetching) {
                busyFetching = true;
                LOG.debug("Starting a sync run of {} file(s)", filesToDownload.size());
            }

            currentlyDownloading = filesToDownload.remove();

            final FileTransferHandler.DirectoryEntry entry = currentlyDownloading.getDirectoryEntry();
            if (entry != null) {
                final Optional<File> held =
                        alreadyDownloaded(entry.getOutputPath(), entry.getFileSize());
                if (held.isPresent()) {
                    LOG.debug("{} is already on the phone; announcing it rather than pulling it again",
                            entry.getFileName());
                    final FileDownloadedDeviceEvent event = new FileDownloadedDeviceEvent();
                    event.directoryEntry = entry;
                    event.localPath = held.get().getAbsolutePath();
                    evaluateGBDeviceEvent(event);
                    continue;
                }
                final DownloadRequestMessage request = fileTransferHandler.downloadDirectoryEntry(entry);
                armDownloadWatchdog("file " + entry.getFileIndex());
                sendOutgoingMessage("download file " + entry.getFileIndex(), request);
                return;
            }

            final GdiFileSyncService.File syncFile = currentlyDownloading.getSyncFile();
            if (syncFile != null) {
                final String label = syncFile.getId().getId1() + "/" + syncFile.getId().getId2();

                // The training calendar is always fetched again, never served
                // from the copy on disk.
                //
                // Every other type here is immutable once written, so a local
                // copy of the same name and size IS the file and re-pulling it
                // is waste. Schedule.fit is the opposite: the watch rewrites it
                // in place whenever it ingests a schedule, and it is read for
                // exactly that reason. Announcing the held copy reported a file
                // from an earlier run, with its own timestamp, as though the
                // watch had just handed it over -- which is the one thing this
                // read must never do, since a stale calendar and a fresh one
                // look identical.
                final boolean alwaysRefetch = syncFile.hasType()
                        && FileType.FILETYPE.SCHEDULES
                        == FileType.FILETYPE.findByTypeName(syncFile.getType().getName());

                final Optional<File> held = alwaysRefetch
                        ? Optional.empty()
                        : alreadyDownloaded(syncFileOutputPath(syncFile), syncFile.getSize());
                if (held.isPresent()) {
                    LOG.debug("{} is already on the phone; announcing it rather than pulling it again",
                            label);
                    final SyncFileDownloadedDeviceEvent event = new SyncFileDownloadedDeviceEvent();
                    event.syncFile = syncFile;
                    event.localPath = held.get().getAbsolutePath();
                    rememberIfActivity(syncFile);
                    evaluateGBDeviceEvent(event);
                    continue;
                }
                armDownloadWatchdog("file " + label);
                sendProtobufRequest("request file", GdiSmartProto.Smart.newBuilder()
                        .setFileSyncService(
                                protocolBufferHandler.getFileSyncServiceHandler().requestFile(syncFile))
                        .build());
                return;
            }

            LOG.error("Queued a FileToDownload with neither a directory entry nor a sync file");
            currentlyDownloading = null;
        }

        // The queue is empty — but on the newer protocol that may only mean the
        // list has not come back yet, and declaring the run over here would stop
        // it moments before the watch says what it holds.
        if (awaitingFileList) {
            return;
        }

        // Nothing left to pull. Two ways to arrive here and both must finish the
        // run, which is what an earlier version got wrong: it only finished if
        // at least one file had been transferred, so a watch with nothing new to
        // offer left the sync hanging until its timeout. That is the *ordinary*
        // case for a watch that was synced yesterday, and on device it showed up
        // as a spinner that never stopped.
        //
        // isDownloading() is the guard that makes this safe to check eagerly:
        // the directory listing is itself a download, so it stays true from the
        // moment the listing is requested until the entries have been queued.
        if (syncRequested && !fileTransferHandler.isDownloading()) {
            syncRequested = false;
            busyFetching = false;
            cancelDownloadWatchdog();
            // Scoped to the run, not the connection: a file legitimately offered
            // again on the *next* sync — because the last one could not upload
            // it — must be taken again.
            queuedThisRun.clear();
            finishFileSync();
        }
    }

    /**
     * Give up on a file the watch never answers for.
     *
     * <p>Every one of the three ways a download ends — the bytes arrive, the
     * watch refuses, the transfer channel closes — arrives as something that
     * pumps the queue. There is no fourth for *silence*. Asked for a file it
     * will not discuss, the watch simply says nothing, and the run then waits
     * forever on a file that is never coming: measured on a fenix 6X as a sync
     * that requested one file, received no reply of any kind, and left the app
     * showing a spinner until it was force-stopped.
     *
     * <p>So silence gets a deadline. It is deliberately long — a large activity
     * takes minutes and progress resets the timer on every chunk — because the
     * cost of firing early is abandoning a file that was on its way, while the
     * cost of firing late is only a slower failure.
     */
    private void armDownloadWatchdog(final String what) {
        cancelDownloadWatchdog();
        lastDownloadActivityAt = SystemClock.elapsedRealtime();
        scheduleWatchdog(what, DOWNLOAD_SILENCE_TIMEOUT_MS);
    }

    private void scheduleWatchdog(final String what, final long delayMs) {
        downloadWatchdog = schedule(() -> onDownloadWatchdogFired(what), delayMs);
    }

    /**
     * Scheduling after {@link #dispose} is not an error worth propagating.
     *
     * <p>The executor is shut down when the connection goes away, and a message
     * that was already in the BLE stack can still arrive after that and drive
     * the queue one more time. Left unguarded that throws
     * {@code RejectedExecutionException} on the BLE callback thread — turning a
     * tidy disconnect into a crash, for a timer whose only job is to notice a
     * transfer that can no longer be happening.
     */
    private ScheduledFuture<?> schedule(final Runnable task, final long delayMs) {
        if (watchdogExecutor.isShutdown()) {
            return null;
        }
        try {
            return watchdogExecutor.schedule(task, delayMs, TimeUnit.MILLISECONDS);
        } catch (final RejectedExecutionException e) {
            // Lost the race with dispose(); there is nothing left to watch.
            return null;
        }
    }

    /**
     * Re-checks rather than acts, because "no bytes for a while" and "no bytes
     * at all" are different things: a multi-megabyte activity legitimately
     * takes minutes, and {@link #onFileDownloadProgress} keeps the clock fresh.
     * Only a transfer that has gone quiet for the whole window is abandoned.
     */
    private void onDownloadWatchdogFired(final String what) {
        synchronized (downloadLock) {
            if (currentlyDownloading == null) {
                return;
            }
            final long idle = SystemClock.elapsedRealtime() - lastDownloadActivityAt;
            if (idle < DOWNLOAD_SILENCE_TIMEOUT_MS) {
                scheduleWatchdog(what, DOWNLOAD_SILENCE_TIMEOUT_MS - idle);
                return;
            }
            LOG.error("The watch has said nothing about {} for {}s; abandoning it and "
                    + "continuing with the rest of the run", what, idle / 1000);
            downloadWatchdog = null;
            failCurrentDownload();
        }
    }

    private void cancelDownloadWatchdog() {
        final ScheduledFuture<?> watchdog = downloadWatchdog;
        if (watchdog != null) {
            watchdog.cancel(false);
            downloadWatchdog = null;
        }
    }

    private void armFileListWatchdog() {
        synchronized (downloadLock) {
            cancelFileListWatchdog();
            lastFileListActivityAt = SystemClock.elapsedRealtime();
            fileListWatchdog = schedule(this::onFileListWatchdogFired, FILE_LIST_TIMEOUT_MS);
        }
    }

    private void cancelFileListWatchdog() {
        synchronized (downloadLock) {
            final ScheduledFuture<?> watchdog = fileListWatchdog;
            if (watchdog != null) {
                watchdog.cancel(false);
                fileListWatchdog = null;
            }
        }
    }

    /**
     * The watch was asked what it holds and said nothing.
     *
     * <p>This is the specific failure that defaulting the newer protocol on
     * makes possible, and it is the worst-shaped one: a watch whose firmware
     * predates the protobuf file sync does not refuse the request, it ignores
     * it. Nothing arrives, so nothing drives the queue, and
     * {@link #processDownloadQueueLocked} correctly refuses to end a run whose
     * listing has not come back — leaving the sync waiting forever on an answer
     * that was never coming. Without this the app's own sync timeout would be
     * the only thing to end it, tens of minutes later, having done nothing.
     *
     * <p>So the run is not abandoned, it is restarted on the older protocol.
     * The directory is asked for again — a few kilobytes — and this time
     * {@code parseDirectoryEntries} reads the entries out of it rather than
     * deferring to a listing that will not arrive. A watch that cannot speak
     * the new protocol therefore still syncs on the first attempt, roughly
     * {@link #FILE_LIST_TIMEOUT_MS} later than it otherwise would, and never
     * pays that cost again.
     */
    private void onFileListWatchdogFired() {
        synchronized (downloadLock) {
            fileListWatchdog = null;
            if (!awaitingFileList) {
                return;
            }
            final long idle = SystemClock.elapsedRealtime() - lastFileListActivityAt;
            if (idle < FILE_LIST_TIMEOUT_MS) {
                fileListWatchdog =
                        schedule(this::onFileListWatchdogFired, FILE_LIST_TIMEOUT_MS - idle);
                return;
            }
            LOG.warn("The watch has not answered a file-list request in {}s; its firmware "
                    + "does not appear to know the newer sync protocol. Falling back to the "
                    + "directory listing, for this run and from now on.", idle / 1000);
            demoteToOldSyncProtocol();
            awaitingFileList = false;
        }

        // Outside the lock: this reaches the BLE write queue, and holding the
        // download lock across it would invert the order every other path takes.
        sendOutgoingMessage("re-fetch directory listing",
                fileTransferHandler.initiateDownload());
    }

    /**
     * The queue is drained.
     *
     * <p>Upstream parses the pulled FIT files here. Tracks does not parse FIT on
     * the phone at all — the bytes go to the server, which already has a FIT
     * importer — so this only tells the watch the conversation is over and lets
     * Tracks know it can stop holding a foreground service.
     */
    private void finishFileSync() {
        sendOutgoingMessage("set sync complete",
                new SystemEventMessage(SystemEventMessage.GarminSystemEventType.SYNC_COMPLETE, 0));
        updateConnectionPriority();

        final SyncListener listener = syncListener;
        if (listener != null) {
            try {
                listener.onSyncFinished();
            } catch (final Exception e) {
                LOG.error("Sync listener failed on sync-finished", e);
            }
        }
    }

    /**
     * The copy of this file already in our export directory, if there is one.
     *
     * <p>Returns the file rather than a yes/no, because the caller does not
     * want to skip it — it wants to announce it. A pull and an upload are two
     * steps, and a run interrupted between them leaves the bytes on the phone
     * with the server still not holding them. Skipping such a file on the next
     * run, which is what an earlier version did, meant it was never pulled again
     * *and* never uploaded: permanently stranded on the phone while the watch
     * kept offering it. Announcing it puts it back on the upload path, where the
     * server's own duplicate check makes a redundant announcement free.
     *
     * <p>Only the current naming scheme is checked. Upstream also probes two
     * legacy filename shapes because its users have years of files written by
     * older releases; Tracks has never written either, so looking for them
     * would be checking for something that cannot exist.
     *
     * <p>A zero-length file counts as absent: that is what a transfer
     * interrupted at the wrong moment leaves behind, and treating it as done
     * would silently lose the activity.
     */
    /**
     * A local copy good enough to skip the download, or empty to pull again.
     *
     * <p>The size check is the whole point and it was missing. A watch keeps
     * <em>one</em> monitoring file per day and appends to it all day — steps,
     * stress, Body Battery, respiration are written into it as the hours pass —
     * so a sync at nine in the morning leaves the phone holding a file that is
     * correct and two hours long. Existence alone said "already have it", and
     * every sync for the rest of that day re-announced the nine-o'clock copy
     * while the watch sat on a fuller one. Today's numbers could not arrive by
     * any route; the file they were in was never asked for a second time.
     *
     * <p>The watch reports each file's current size in the same listing that
     * offers it, so the comparison costs nothing: a local copy shorter than
     * what the watch holds is stale by definition, and everything else — a
     * finished activity, a closed night's sleep — matches and is still skipped.
     *
     * <p>{@code expectedSize} of zero or less means the listing did not say,
     * in which case existence is all there is to go on.
     */
    private Optional<File> alreadyDownloaded(final String outputPath, final long expectedSize) {
        final Optional<File> existing = findLocalFile(outputPath);
        if (!existing.isPresent()) {
            return Optional.empty();
        }
        final long held = existing.get().length();
        if (held == 0) {
            LOG.warn("{} exists but is empty; pulling it again", outputPath);
            return Optional.empty();
        }
        if (expectedSize > 0 && held < expectedSize) {
            LOG.debug("{} has grown on the watch ({} bytes held, {} offered); pulling it again",
                    outputPath, held, expectedSize);
            return Optional.empty();
        }
        return existing;
    }

    private Optional<File> findLocalFile(final String fileName) {
        try {
            final File candidate = new File(getWritableExportDirectory(), fileName);
            return candidate.exists() ? Optional.of(candidate) : Optional.empty();
        } catch (final IOException e) {
            LOG.error("Could not reach the export directory", e);
            return Optional.empty();
        }
    }

    /**
     * Tell the watch it may reclaim a file's space.
     *
     * <p>Two call sites, two different reasons the flag is the right one.
     * Reclaiming a pulled recording's space is deliberately *not* automatic
     * after a download, which is where upstream calls it — Tracks calls it
     * only once the bytes are durably held elsewhere, because until then the
     * watch holds the only copy that survives losing the phone, and an
     * expedition tool should not trade that away to save flash on a device
     * with gigabytes of it. Removing a course the phone pushed is the other
     * caller: {@link #deleteOnWatch}'s DELETE flag errored on every real
     * device tested so far, and ARCHIVE is the one flag upstream has actually
     * seen a watch accept — see {@code GarminIntegration.deleteFile} for
     * whether the watch actually drops an archived course from its own
     * Courses list, which is a separate question from whether it accepts
     * the flag at all.
     */
    public void archiveOnWatch(final int fileIndex) {
        sendOutgoingMessage("archive file " + fileIndex,
                new SetFileFlagsMessage(fileIndex, SetFileFlagsMessage.FileFlags.ARCHIVE));
    }

    // ── Pushing files to the watch ───────────────────────────────────────────

    /**
     * Write a file the Tracks server produced — a workout, a course, ephemeris.
     *
     * <p>The bytes are opaque here: the server authored the FIT and told us its
     * type, so this layer never parses one. Upstream reaches the same
     * {@code initiateUpload} through an install-handler that sniffs the file's
     * {@code file_id} message; Tracks does not need to sniff what it was told.
     */
    /**
     * Push a file the way Garmin's own apps do.
     *
     * <p>Two steps. A {@code FileSyncService} upload request names the file by
     * the watch's own type name ("FIT_TYPE_7") and its uncompressed size, and
     * the watch answers with a transfer handle. The bytes then go over a
     * separate multi-link channel, opened with a six-byte header that is the
     * download header with one byte changed — {@code 00 01 <handle LE> 00 00},
     * where the download form uses {@code 00} in the second position. The file
     * itself is deflated, same as it arrives in the other direction.
     *
     * <p>This exists because {@link #uploadFile}'s legacy path does not work for
     * every type. A schedule sent that way is accepted and acknowledged in full
     * and never reaches the training calendar; the same bytes sent this way are
     * what Garmin Connect does. See AGENTS.md.
     *
     * <p>{@code onResult} gets true only once the channel has closed cleanly.
     */
    /**
     * The transfer handle whose TransferComplete should be answered with an
     * empty response, closing the burst. -1 when none is outstanding.
     *
     * A handle rather than a boolean for the same reason
     * {@code transferCompleteWaiters} is keyed by one: a download's
     * TransferComplete can land between an upload's channel closing and its own,
     * and closing the session on the wrong one would end the burst early.
     */
    private volatile int burstEndHandle = -1;

    /**
     * Whether this handle is the last of an upload burst, consuming the answer.
     *
     * Consuming rather than peeking: the burst ends once, and a retransmitted
     * TransferComplete for the same handle must not close a second session.
     */
    public boolean endsUploadBurst(final int handle) {
        if (handle != burstEndHandle) {
            return false;
        }
        burstEndHandle = -1;
        return true;
    }

    public void uploadFileViaFileSync(final String taskName,
                                      final byte[] contents,
                                      final String typeName,
                                      final boolean lastInBatch,
                                      final Consumer<Boolean> onResult) {
        final ICommunicator comm = communicator;
        if (!(comm instanceof CommunicatorV2)) {
            LOG.warn("{}: file-sync upload needs the V2 transport", taskName);
            onResult.accept(false);
            return;
        }

        sendProtobufRequest(taskName, GdiSmartProto.Smart.newBuilder()
                .setFileSyncService(protocolBufferHandler.getFileSyncServiceHandler()
                        .uploadRequest(typeName, contents.length, transferHandle -> {
                            if (transferHandle < 0) {
                                onResult.accept(false);
                                return;
                            }
                            if (lastInBatch) {
                                burstEndHandle = transferHandle;
                            }
                            startFileSyncUpload(taskName, contents, transferHandle,
                                    (CommunicatorV2) comm, onResult);
                        }))
                .build());
    }

    private void startFileSyncUpload(final String taskName,
                                     final byte[] contents,
                                     final int transferHandle,
                                     final CommunicatorV2 comm,
                                     final Consumer<Boolean> onResult) {
        final byte[] payload = CompressionUtils.INSTANCE.deflate(contents);
        LOG.debug("{}: deflated {} bytes to {} for transfer handle {}",
                taskName, contents.length, payload.length, transferHandle);

        /*
         * A file is not finished when its channel closes.
         *
         * The watch sends a TransferComplete about a tenth of a second later and
         * waits to be told it was heard, and Garmin Connect never starts the next
         * upload until it has sent that answer. Reporting success at the close
         * instead let the next upload request overtake the answer on every single
         * file; the watch put up with four of them and then stopped replying
         * altogether for five minutes. So the report waits for both halves.
         */
        final AtomicBoolean channelClosed = new AtomicBoolean(false);
        final AtomicBoolean completeAnswered = new AtomicBoolean(false);
        final AtomicBoolean reported = new AtomicBoolean(false);
        final ScheduledFuture<?>[] guard = new ScheduledFuture<?>[1];

        final Consumer<Boolean> report = outcome -> {
            if (!reported.compareAndSet(false, true)) {
                return;
            }
            if (guard[0] != null) {
                guard[0].cancel(false);
            }
            protocolBufferHandler.getFileSyncServiceHandler()
                    .cancelTransferCompleteWaiter(transferHandle);
            onResult.accept(outcome);
        };

        final Runnable finishIfReady = () -> {
            if (channelClosed.get() && completeAnswered.get()) {
                LOG.info("{}: file-sync upload finished on handle {}", taskName, transferHandle);
                report.accept(true);
            }
        };

        protocolBufferHandler.getFileSyncServiceHandler()
                .onTransferCompleteAnswered(transferHandle, () -> {
                    completeAnswered.set(true);
                    finishIfReady.run();
                });

        comm.startTransfer(new CommunicatorV2.ServiceCallback() {
            private boolean settled;

            /**
             * Whether the channel ever opened.
             *
             * <p>Load-bearing: {@link CommunicatorV2#startTransfer} answers a
             * request it cannot satisfy — all six file-transfer slots already in
             * use, which happens whenever a sync is pulling files — by calling
             * {@code onClose} without ever calling {@code onConnect}. Treating
             * any close as success reported a completed upload for a file that
             * was never written, which is exactly what happened on the first
             * live test.
             */
            private boolean opened;

            /** The writer, held between opening the channel and the go-ahead. */
            private CommunicatorV2.ServiceWriter writer;

            /** Whether the body has been written, so a later reply is ignored. */
            private boolean sent;

            @Override
            public void onConnect(final CommunicatorV2.ServiceWriter serviceWriter) {
                opened = true;
                writer = serviceWriter;
                final ByteBuffer header = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN);
                header.put((byte) 0x00);
                header.put((byte) 0x01); // 0x00 would be a download
                header.putShort((short) transferHandle);
                header.put((byte) 0x00);
                header.put((byte) 0x00);
                LOG.debug("{}: transfer channel open for handle {}", taskName, transferHandle);
                // Header only. The body waits for the watch to answer -- see
                // onMessage.
                writer.write(taskName + " header", header.array());
            }

            /**
             * The watch's go-ahead: three zero bytes, once, after the header.
             *
             * <p>Waiting for it is not politeness, it is the difference between
             * a file arriving and not. Tracks used to write the header and the
             * whole body back to back; the capture shows the watch replying
             * about 90ms later, by which point the body had already filled the
             * reliable-transport window, and the transfer died with 160 of 429
             * bytes delivered. Garmin Connect sends the header, waits for these
             * three bytes, and only then streams -- and its transfers finish.
             */
            @Override
            public void onMessage(final byte[] value) {
                if (sent || writer == null) {
                    return;
                }
                sent = true;
                LOG.debug("{}: watch is ready on handle {}, sending {} bytes",
                        taskName, transferHandle, payload.length);

                // Two bytes, not one: the reliable-transport writer spends two
                // on its own header, so asking it to carry maxWriteSize - 1
                // split every chunk across two packets -- eighteen bytes and
                // then a lonely one -- and burned through the send window twice
                // as fast as it had to. Connect writes exactly this size.
                final int chunk = Math.max(1, comm.maxWriteSize - 2);
                for (int offset = 0; offset < payload.length; offset += chunk) {
                    final int end = Math.min(payload.length, offset + chunk);
                    writer.write(taskName + " data", Arrays.copyOfRange(payload, offset, end));
                }

                // Hand the channel back as soon as the last fragment is
                // acknowledged. Without this the watch waits exactly 30 seconds
                // for a close that never comes and then reclaims the channel
                // itself, which is where most of a sync's wall-clock time went:
                // the file was fully delivered and acked in about 275ms, and the
                // remaining 30s was pure timeout.
                writer.closeWhenDrained();
            }

            @Override
            public void onClose() {
                if (settled) {
                    return;
                }
                settled = true;
                if (!opened) {
                    LOG.warn("{}: no file transfer channel was available for handle {}",
                            taskName, transferHandle);
                    report.accept(false);
                    return;
                }
                if (!sent) {
                    LOG.warn("{}: transfer channel for handle {} closed before the watch was ready",
                            taskName, transferHandle);
                    report.accept(false);
                    return;
                }

                // Delivered, but not yet acknowledged in the way the watch is
                // waiting for. The guard exists because a watch that never sends
                // the TransferComplete must not strand the whole push: the bytes
                // are on the device either way, so a late answer costs a warning
                // rather than the file.
                channelClosed.set(true);
                guard[0] = schedule(() -> {
                    if (!reported.get()) {
                        LOG.warn("{}: handle {} closed but the watch never sent a "
                                        + "TransferComplete; continuing without it",
                                taskName, transferHandle);
                        report.accept(true);
                    }
                }, TRANSFER_COMPLETE_TIMEOUT_MS);
                finishIfReady.run();
            }
        });
    }

    public void uploadFile(final String taskName, final byte[] bytes, final FileType.FILETYPE fileType) {
        if (bytes == null || bytes.length == 0) {
            LOG.warn("Refusing to upload an empty file for {}", taskName);
            return;
        }
        sendOutgoingMessage(taskName, fileTransferHandler.initiateUpload(bytes, fileType));
        updateConnectionPriority();
    }

    /** Whether a legacy upload is still between its create-file and its last block. */
    public boolean isUploadInFlight() {
        return fileTransferHandler.isUploading();
    }

    /**
     * Why the watch last refused an upload, or null.
     *
     * Cleared by the caller before each push, so a stale answer from an earlier
     * failure cannot be reported against a later one.
     */
    public String getLastUploadRefusal() {
        return fileTransferHandler.getLastRefusal();
    }

    public void clearLastUploadRefusal() {
        fileTransferHandler.clearLastRefusal();
    }

    public boolean isUploading() {
        return fileTransferHandler.isUploading();
    }

    public boolean isDownloading() {
        return fileTransferHandler.isDownloading();
    }

    public List<FileType> getSupportedFileTypes() {
        return Collections.unmodifiableList(supportedFileTypes);
    }

    public Set<GarminCapability> getWatchCapabilities() {
        return Collections.unmodifiableSet(watchCapabilities);
    }

    // ── Phone feeds: notifications, music, weather ───────────────────────────

    public void sendNotification(final NotificationSpec spec) {
        if (!notificationsSubscribed) {
            // Loud, because the alternative is silent. The handler returns null
            // when the watch has not subscribed and sendOutgoingMessage drops
            // nulls, so an unsubscribed watch discarded every notification
            // without a single line anywhere — indistinguishable from the relay
            // being broken, the permission being off, or the phone filtering
            // the notification out. Those need telling apart from a logcat.
            LOG.warn("Not relaying notification {}: watch has not subscribed to notifications. "
                    + "The watch requests this during its handshake; if it never does, check that "
                    + "no other phone app holds the Garmin pairing.", spec.getId());
            return;
        }
        sendOutgoingMessage("notify " + spec.getId(), notificationsHandler.onNotification(spec));
    }

    public void deleteNotification(final int id) {
        if (!notificationsSubscribed) {
            return;
        }
        sendOutgoingMessage("dismiss notification " + id, notificationsHandler.onDeleteNotification(id));
    }

    /**
     * The canned answers the watch offers when replying to a notification.
     *
     * <p>Written to the device prefs first, under the keys the vendored
     * protocol handler already reads ({@code canned_reply_1}…{@code _16}),
     * because the watch asks for the list whenever it likes — often during
     * the handshake, before the phone has said anything — and the handler
     * answers that from the prefs. Then the watch is told the list changed,
     * which makes a connected watch ask again now rather than at its next
     * connect.
     *
     * <p>Only the message-reply list. The other one the protocol has is for
     * declining a call with a text, which needs SMS permission Tracks does not
     * ask for.
     */
    public void setQuickReplies(final String[] replies) {
        final android.content.SharedPreferences.Editor edit = getDevicePrefs().getPreferences().edit();
        for (int i = 1; i <= MAX_QUICK_REPLIES; i++) {
            if (i <= replies.length) {
                edit.putString(PREF_QUICK_REPLY + i, replies[i - 1]);
            } else {
                edit.remove(PREF_QUICK_REPLY + i);
            }
        }
        edit.apply();

        final CannedMessagesSpec spec = new CannedMessagesSpec();
        spec.type = CannedMessagesSpec.TYPE_NEWSMS;
        spec.cannedMessages = java.util.Arrays.copyOf(replies, Math.min(replies.length, MAX_QUICK_REPLIES));
        sendOutgoingMessage("quick replies", protocolBufferHandler.setCannedMessages(spec));
    }

    /** The vendored handler's own key and limit; see ProtocolBufferHandler#populateCannedListTypeMap. */
    private static final String PREF_QUICK_REPLY = "canned_reply_";
    private static final int MAX_QUICK_REPLIES = 16;

    /** The watch's product number, or -1 before its handshake. See the field. */
    public int getProductNumber() {
        return productNumber;
    }

    /**
     * Read the product number out of a raw DEVICE_INFORMATION frame.
     *
     * <p>Read here rather than from the parsed message because the vendored
     * {@link DeviceInformationMessage} parses it into a private field with no
     * getter, and adding one means a vendored patch for a two-byte read. The
     * layout is the one its parser walks: length (u16), message type (u16),
     * protocol version (u16), then the product number (u16), little-endian
     * throughout. {@code GarminSupportProductNumberTest} holds the two readings
     * together by running a frame through the vendored parser as well.
     */
    static int productNumberOf(final byte[] frame) {
        if (frame == null || frame.length < 8) {
            return -1;
        }
        return (frame[6] & 0xFF) | ((frame[7] & 0xFF) << 8);
    }

    /** Whether the watch has asked for notifications. See the subscription event. */
    public boolean isSubscribedToNotifications() {
        return notificationsSubscribed;
    }

    public void sendCallState(final CallSpec callSpec) {
        sendOutgoingMessage("call state", notificationsHandler.onSetCallState(callSpec));
    }

    /**
     * Send a forecast the caller has already encoded as FIT local messages.
     *
     * <p>The encoding lives on the Tracks side rather than here, because it
     * translates Tracks' own weather model and this class may not know about
     * that model. What stays here is the protocol dance: the definitions go out
     * first, and a handler owns the exchange until the watch has taken the data.
     */
    public void sendWeather(final FitLocalMessageBuilder weatherMessages) {
        if (weatherMessages == null || weatherMessages.getDefinitions().isEmpty()) {
            return;
        }
        if (!watchCapabilities.isEmpty() && !watchCapabilities.contains(GarminCapability.WEATHER_CONDITIONS)) {
            LOG.debug("Watch does not accept pushed weather conditions; skipping");
            return;
        }
        // Any handler left from the previous forecast goes first. The watch
        // asks for weather about once a minute, and each ask used to register
        // another handler that was never removed — so after an hour connected,
        // sixty of them were queued to answer the next FitDefinitionStatus, the
        // earliest (and stalest) winning. The definitions are re-sent with every
        // forecast anyway, so the old handler has nothing left to do.
        for (final MessageHandler existing : new ArrayList<>(messageHandlers)) {
            if (existing instanceof FitLocalMessageHandler) {
                messageHandlers.remove(existing);
            }
        }

        final FitLocalMessageHandler handler = new FitLocalMessageHandler(this, weatherMessages);
        messageHandlers.add(handler);
        sendOutgoingMessage("send weather", handler.init());
    }

    /** Track metadata for the watch's music screen. Nulls are sent as empty. */
    public void sendMusicTrack(final String artist, final String album, final String title, final int durationSeconds) {
        final Map<MusicControlEntityUpdateMessage.MusicEntity, String> attributes = new HashMap<>(4);
        attributes.put(MusicControlEntityUpdateMessage.TRACK.ARTIST, artist == null ? "" : artist);
        attributes.put(MusicControlEntityUpdateMessage.TRACK.ALBUM, album == null ? "" : album);
        attributes.put(MusicControlEntityUpdateMessage.TRACK.TITLE, title == null ? "" : title);
        attributes.put(MusicControlEntityUpdateMessage.TRACK.DURATION, String.valueOf(durationSeconds));
        sendOutgoingMessage("set music info", new MusicControlEntityUpdateMessage(attributes));
    }

    /**
     * Playback position and rate.
     *
     * <p>The wire format is a bare comma-separated triple, and the locale is
     * pinned to ROOT for a reason that is easy to miss: in a locale that formats
     * decimals with a comma, {@code %.1f} would emit "1,0" and turn a
     * three-field value into four. Tested phones in Europe would break.
     */
    public void sendMusicState(final boolean playing, final int positionSeconds) {
        final Map<MusicControlEntityUpdateMessage.MusicEntity, String> attributes = new HashMap<>(1);
        attributes.put(MusicControlEntityUpdateMessage.PLAYER.PLAYBACK_INFO,
                String.format(Locale.ROOT, "%d,%.1f,%.3f", playing ? 1 : 0, playing ? 1.0f : 0.0f, (float) positionSeconds));
        sendOutgoingMessage("set music state", new MusicControlEntityUpdateMessage(attributes));
    }

    public void sendPhoneVolume(final float volumePercent) {
        final Map<MusicControlEntityUpdateMessage.MusicEntity, String> attributes = new HashMap<>(1);
        attributes.put(MusicControlEntityUpdateMessage.PLAYER.VOLUME,
                String.format(Locale.ROOT, "%.2f", volumePercent / 100f));
        sendOutgoingMessage("set phone volume", new MusicControlEntityUpdateMessage(attributes));
    }

    // ── What the vendored protocol code calls back into ──────────────────────

    /**
     * Bytes of the file currently transferring that have arrived so far.
     *
     * <p>Bytes, not a percentage: neither protocol tells us the total up front
     * in a form this method sees, and both call it with a running count.
     *
     * <p>Upstream drives a notification here. Tracks forwards it so the caller
     * can decide — a foreground-service notification during an active transfer
     * is required by Android anyway, but a background sync should stay silent.
     */
    public void onFileDownloadProgress(final int progress) {
        // Also what keeps the watchdog off a transfer that is simply large.
        lastDownloadActivityAt = SystemClock.elapsedRealtime();
        final DownloadProgressListener listener = progressListener;
        if (listener != null) {
            listener.onProgress(progress);
        }
    }

    public void addFileToDownloadList(final FileTransferHandler.DirectoryEntry directoryEntry) {
        if (directoryEntry == null) {
            return;
        }
        awaitingDirectory = false;
        final ScheduledFuture<?> watchdog = directoryWatchdog;
        if (watchdog != null) {
            watchdog.cancel(false);
            directoryWatchdog = null;
        }

        // On the newer protocol the directory listing is not the source of
        // truth — it is only the signal that the watch is willing to talk about
        // files at all. The real list comes back over protobuf, and asking for
        // it is what this branch exists to do. The entries in the directory are
        // deliberately dropped rather than merged: the two listings overlap,
        // and pulling the same activity down both paths would upload it twice.
        if (newSyncProtocol() && directoryEntry.getFiletype() != FileType.FILETYPE.DEVICE_XML) {
            if (directoryEntry.getFiletype() == FileType.FILETYPE.DIRECTORY) {
                requestFileListOnce();
                return;
            }
            LOG.debug("Ignoring directory entry {} — the file list will carry it",
                    directoryEntry.getFileName());
            return;
        }

        if (queueForThisRun(directoryEntry.getOutputPath())) {
            filesToDownload.add(new FileToDownload(directoryEntry));
        }
    }

    public void addFileToDownloadList(final GdiFileSyncService.File file) {
        if (file == null) {
            return;
        }
        // A listing that arrives after we gave up waiting for it. The run has
        // already restarted on the older protocol by then, and taking these too
        // would queue every file twice — once by id, once by directory index,
        // with nothing downstream able to tell they are the same activity.
        if (!newSyncProtocol) {
            LOG.debug("Ignoring a file offered over the newer protocol after falling back");
            return;
        }
        final GdiFileSyncService.File named = nameType(file);
        final String key = syncFileKey(
                named.getId().getId1(), named.getId().getId2(),
                named.hasType() ? named.getType().getName() : null);
        if (isDeliveredUnmarkable(key)) {
            LOG.debug("{} was already delivered and the watch will never let it be marked "
                    + "synced; not pulling it again", key);
            return;
        }
        if (queueForThisRun(syncFileOutputPath(named))) {
            filesToDownload.add(new FileToDownload(named));
        }
    }

    /**
     * Take a file only if this run has not already taken it.
     *
     * <p>The watch does offer the same file twice — measured on a fenix 6X, two
     * out of 364 appeared in both a paged file listing and, separately, as a new
     * file notification. Both copies get pulled, both get announced, and the
     * second upload then reads a path the first upload has already deleted. The
     * result is a sync that reports files it failed to send when in fact it sent
     * every one of them, which is worse than it sounds: the message it produces
     * tells the user their data is still stuck on the watch.
     *
     * <p>Keyed on the output path rather than the id, because the path is the
     * identity that actually matters downstream — it is what gets read, uploaded
     * and deleted — and it is the one key both protocols can produce.
     */
    private boolean queueForThisRun(final String outputPath) {
        if (queuedThisRun.add(outputPath)) {
            return true;
        }
        LOG.debug("The watch offered {} twice in one run; taking it once", outputPath);
        return false;
    }

    /**
     * Put the type name back on a file that arrived without one.
     *
     * <p>The watch names a type once and then omits it. In a listing of 357
     * files, the first {@code SPORTS} entry carries {@code type.name = "SPORTS"}
     * alongside {@code type.code}, and every {@code SPORTS} entry after it
     * carries the code alone — the name is stated once and referred to by number
     * thereafter. Measured on a fenix 6X: 39 of the first 43 files pulled had no
     * type name, and landed in a directory called UNKNOWN.
     *
     * <p>The vendored listing handler does build this mapping, and then drops it
     * — it only needs the name to decide whether to want the file. Rebuilding it
     * here is a few lines and costs nothing, where teaching the vendored handler
     * to carry the name forward would be a second patch on a file we already
     * patch once.
     *
     * <p>Only read from the BLE callback thread, in listing order, so a plain
     * map is safe. It is not, however, always correct within one connection:
     * the naming entry does not reliably precede every entry that refers to
     * it — measured on a fenix 6X, one file resolved on one connection and
     * not on the next, meaning the watch's "name it once" applies across
     * connections, not within one listing. {@link #typeNamesByCode} is
     * therefore persisted rather than rebuilt from nothing every time; see its
     * own doc comment.
     */
    private GdiFileSyncService.File nameType(final GdiFileSyncService.File file) {
        if (!file.hasType() || !file.getType().hasCode()) {
            return file;
        }
        final int code = file.getType().getCode();
        if (file.getType().hasName() && !file.getType().getName().isEmpty()) {
            final String name = file.getType().getName();
            if (!name.equals(typeNamesByCode.put(code, name))) {
                persistTypeName(code, name);
            }
            return file;
        }
        final String known = typeNamesByCode.get(code);
        if (known == null) {
            return file;
        }
        return file.toBuilder()
                .setType(file.getType().toBuilder().setName(known))
                .build();
    }

    /**
     * The type name for a listed file, learning it when the watch states it.
     *
     * <p>For the vendored listing handler, which decides whether a file is
     * wanted before {@link #nameType} ever sees it. It used to resolve names
     * from a map rebuilt per listing page, and the watch names a type once
     * <em>ever</em>, not once per page: after the first listing that named
     * activity files, every later activity arrived by code alone, resolved to
     * nothing, and was dropped as "no type name found". Measured on a fenix 6X:
     * no activity left the watch for a month while monitoring files, named
     * afresh in each listing, kept flowing. Answering from the persisted map
     * closes that.
     *
     * <p>Caveat, measured 2026-09-30 by decoding a two-page listing: the code
     * is a per-page index, not the FIT type — page 1 had FIT_TYPE_6 as code 13,
     * page 2 restarted at code 0 = FIT_TYPE_6, each named on first use in its
     * page. Within a listing that is harmless here, since the naming entry
     * comes first and overwrites the map. A code-only entry reaching this
     * without a naming entry before it (a new-file notice, say) resolves to
     * whatever type last held that index, which may be wrong.
     */
    public String resolveTypeName(final GdiFileSyncService.FileType type) {
        if (type.hasName() && !type.getName().isEmpty()) {
            final String name = type.getName();
            if (type.hasCode() && !name.equals(typeNamesByCode.put(type.getCode(), name))) {
                persistTypeName(type.getCode(), name);
            }
            return name;
        }
        return type.hasCode() ? typeNamesByCode.get(type.getCode()) : null;
    }

    /**
     * Refill {@link #typeNamesByCode} from what earlier connections learned.
     *
     * <p>Called once per connection, from {@link #decideProtocols}, which runs
     * right after a communicator is chosen — before anything can ask this watch
     * about a file, so there is no window where a lookup could miss a mapping
     * this same connection already has on disk.
     */
    private void loadPersistedTypeNames() {
        for (final String entry : getDevicePrefs()
                .getStringSet(PREF_TYPE_NAMES_BY_CODE, Collections.emptySet())) {
            final int separator = entry.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            try {
                typeNamesByCode.put(
                        Integer.parseInt(entry.substring(0, separator)),
                        entry.substring(separator + 1));
            } catch (final NumberFormatException ignored) {
                // A malformed entry cannot have come from persistTypeName; skip it.
            }
        }
    }

    private void persistTypeName(final int code, final String name) {
        final SharedPreferences prefs = getDevicePrefs().getPreferences();
        final Set<String> updated = new HashSet<>(
                prefs.getStringSet(PREF_TYPE_NAMES_BY_CODE, Collections.emptySet()));
        updated.removeIf(entry -> entry.startsWith(code + "="));
        updated.add(code + "=" + name);
        prefs.edit().putStringSet(PREF_TYPE_NAMES_BY_CODE, updated).apply();
    }

    /**
     * The watch has finished listing what it holds.
     *
     * <p>Called from the vendored {@link FileSyncServiceHandler} — the one
     * patch in this protocol's path, and see {@code SHIMS.md} for why it has to
     * be a patch. Until this arrives, an empty queue means "nothing yet", not
     * "nothing at all", and {@link #processDownloadQueue} must not conclude the
     * run is over.
     */
    public void onFileListComplete() {
        LOG.debug("File listing complete; {} file(s) queued", filesToDownload.size());
        cancelFileListWatchdog();
        clearSyncProtocolDemotion();
        awaitingFileList = false;
        processDownloadQueue();
    }

    public Queue<FileToDownload> getFilesToDownload() {
        return filesToDownload;
    }

    @Override
    public GarminPrefs getDevicePrefs() {
        return new GarminPrefs(
                nodomain.freeyourgadget.gadgetbridge.GBApplication
                        .getDeviceSpecificSharedPrefs(getDevice().getAddress()),
                getDevice());
    }

    /**
     * Whether this connection speaks the newer protobuf-based file sync rather
     * than the older directory-listing one.
     *
     * <p>The value is decided once per connection by {@link #decideSyncProtocol}
     * and only ever moves in one direction after that, when
     * {@link #onFileListWatchdogFired} finds the watch cannot answer.
     */
    public boolean newSyncProtocol() {
        return newSyncProtocol;
    }

    /**
     * Multi-link reliable transport for the service channels.
     *
     * <p>Follows the protocol decision by default, because upstream ships them
     * together and warns that the newer sync without MLR "fails to sync large
     * workouts" — but keeps its own override key, because MLR also changes how
     * the *main* GFDI channel is registered. When the newer protocol misbehaves
     * on a watch nobody here owns, being able to move one variable at a time is
     * worth an extra preference.
     */
    public boolean mlrEnabled() {
        return mlrEnabled;
    }

    /**
     * Pick a protocol for the connection now being set up.
     *
     * <p>On by default, with three ways to end up on the older path:
     *
     * <ol>
     *   <li><b>The watch has no second channel.</b> The newer protocol streams
     *       each file down a service channel of its own, which only the
     *       multi-link communicator can open — so on a V1 watch this is not a
     *       preference at all, it is arithmetic. Checked first, and no
     *       preference overrides it.
     *   <li><b>The watch has already failed to answer.</b> Sticky, so a watch
     *       whose firmware does not know the protocol pays the timeout once
     *       rather than at the start of every sync.
     *   <li><b>Somebody said so.</b> An explicit preference wins over the
     *       automatic decision in both directions, including re-enabling a
     *       watch that was demoted.
     * </ol>
     *
     * <p>Defaulting on is the deliberate part. The older path works on this
     * hardware, so this is not a fix — it is a different order of magnitude:
     * files arrive deflated, at roughly a seventh of their size, with no
     * per-message acknowledgement between them. Measured against a fenix 6X,
     * whose 23-byte ATT MTU makes the old path 1.9 KB/s no matter what else is
     * tuned, one 2 MB activity took 82 seconds instead of a projected 17
     * minutes. A backlog that would have needed a day of sitting next to the
     * phone finished in ten minutes. For an expedition tool that is the
     * difference between syncing at camp and not syncing.
     */
    static boolean decideSyncProtocol(
            final boolean multiLink, final Boolean chosen, final boolean demoted) {
        if (!multiLink) {
            return false;
        }
        if (chosen != null) {
            return chosen;
        }
        return !demoted;
    }

    /** Reads the three inputs {@link #decideSyncProtocol} weighs, and says why. */
    private boolean decideSyncProtocol(final boolean multiLink) {
        final GarminPrefs prefs = getDevicePrefs();
        final Boolean chosen = prefs.contains(PREF_NEW_SYNC_PROTOCOL)
                ? prefs.getBoolean(PREF_NEW_SYNC_PROTOCOL, true)
                : null;
        final boolean demoted = prefs.getBoolean(PREF_NEW_SYNC_UNSUPPORTED, false);

        if (!multiLink && Boolean.TRUE.equals(chosen)) {
            LOG.warn("The newer sync protocol is switched on for this watch, but it speaks the "
                    + "single-channel protocol and cannot carry it; using the directory listing");
        } else if (multiLink && chosen == null && demoted) {
            LOG.debug("This watch did not answer a file-list request before; "
                    + "using the directory listing");
        }

        return decideSyncProtocol(multiLink, chosen, demoted);
    }

    /**
     * Settle both protocol questions for the connection being set up.
     *
     * <p>Called from {@link #initializeDevice} the moment a communicator is
     * chosen, which is before anything can ask — the alternative, deciding
     * lazily on first use, risks answering while {@link #communicator} is still
     * null and quietly choosing the old protocol on a watch that supports the
     * new one.
     */
    private void decideProtocols(final boolean multiLink) {
        newSyncProtocol = decideSyncProtocol(multiLink);
        mlrEnabled = getDevicePrefs().getBoolean(PREF_MLR, newSyncProtocol);
        loadPersistedTypeNames();
        LOG.info("Sync protocol for this connection: {}{}",
                newSyncProtocol ? "protobuf file sync" : "directory listing",
                mlrEnabled ? ", service channels reliable" : "");
    }

    /**
     * Stop asking this watch for something it does not understand.
     *
     * <p>Persisted rather than kept in memory: firmware does not gain a
     * protocol between one connection and the next, so a watch that failed
     * should not spend {@link #FILE_LIST_TIMEOUT_MS} failing again at the start
     * of every sync for the rest of its life. An explicit preference still wins
     * over this, which is what makes it recoverable after a firmware update.
     */
    private void demoteToOldSyncProtocol() {
        newSyncProtocol = false;
        getDevicePrefs().getPreferences().edit()
                .putBoolean(PREF_NEW_SYNC_UNSUPPORTED, true)
                .apply();
    }

    /**
     * A watch that answers has disproved any earlier demotion.
     *
     * <p>Firmware updates are the reason this exists: without it, a watch
     * demoted once on old firmware would stay on the slow path forever, and the
     * only cure would be a preference the user has no reason to suspect.
     */
    private void clearSyncProtocolDemotion() {
        final GarminPrefs prefs = getDevicePrefs();
        if (prefs.getBoolean(PREF_NEW_SYNC_UNSUPPORTED, false)) {
            LOG.info("This watch answers the newer sync protocol after all; "
                    + "clearing the earlier fallback");
            prefs.getPreferences().edit().remove(PREF_NEW_SYNC_UNSUPPORTED).apply();
        }
    }

    public static final String PREF_NEW_SYNC_PROTOCOL = "new_sync_protocol";
    public static final String PREF_MLR = "garmin_mlr";

    /**
     * Set once a watch has proved it cannot answer a file-list request.
     * Separate from {@link #PREF_NEW_SYNC_PROTOCOL} so that what we worked out
     * and what the user chose stay distinguishable — collapsing them would make
     * a demotion look like a setting and hide it from the automatic decision.
     */
    public static final String PREF_NEW_SYNC_UNSUPPORTED = "new_sync_protocol_unsupported";

    /**
     * Where to write files pulled off the watch.
     *
     * <p>App-private storage, deliberately — a FIT file is a complete GPS trace
     * of where its owner has been, and external storage is readable by anything
     * holding the right permission. Tracks encrypts GPS at rest on the server
     * for the same reason; leaving the source data world-readable on the phone
     * would make that pointless.
     */
    public File getWritableExportDirectory() throws IOException {
        final File dir = new File(getContext().getFilesDir(), "garmin");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Could not create " + dir);
        }
        return dir;
    }

    /**
     * Pull a file the watch has agreed to hand over on the newer protocol.
     *
     * <p>Nothing like the older path. There, the file arrives as GFDI
     * {@code FILE_TRANSFER_DATA} messages on the one channel everything else
     * uses. Here the watch opens a *second* BLE service channel, streams the
     * file down it as raw fragments with no GFDI framing, and closes it — so
     * the transfer never passes through {@link #onMessage} and none of the
     * bookkeeping that hangs off it happens by itself.
     *
     * <p>Three consequences, each of which is a bug if missed:
     *
     * <ul>
     *   <li>The payload is zlib-deflated. The old path's bytes are not.</li>
     *   <li>{@link #processDownloadQueue} is driven by incoming GFDI messages,
     *       and no GFDI message marks the end of this transfer. Upstream gets
     *       away with not calling it because it always follows a download with
     *       a "mark as synced" request, whose reply restarts the pump — but
     *       Tracks deliberately does not mark files synced until the server has
     *       them, so for us the queue would simply stop. It is called
     *       explicitly below, on every exit path.</li>
     *   <li>An empty or corrupt payload still has to release
     *       {@link #currentlyDownloading}, or one bad file wedges the run.</li>
     * </ul>
     */
    public void downloadFileFromServiceV2(final int fileHandle) {
        final ICommunicator comm = communicator;
        if (!(comm instanceof CommunicatorV2)) {
            LOG.error("Watch offered a V2 file transfer but we are not speaking V2");
            failCurrentDownload();
            return;
        }

        // Captured now: the callbacks below run after the queue has moved on in
        // the failure cases, and the file's identity has to survive that.
        final GdiFileSyncService.File syncFile =
                currentlyDownloading != null ? currentlyDownloading.getSyncFile() : null;
        if (syncFile == null) {
            LOG.error("Got a V2 file handle {} with no file in flight", fileHandle);
            failCurrentDownload();
            return;
        }

        LOG.debug("Starting V2 transfer of {}/{} on handle {}",
                syncFile.getId().getId1(), syncFile.getId().getId2(), fileHandle);

        ((CommunicatorV2) comm).startTransfer(new CommunicatorV2.ServiceCallback() {
            private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            private boolean started;

            /**
             * Set once this transfer has given a verdict, so it cannot give a
             * second one.
             *
             * <p>The channel stays open after a bad frame and keeps delivering,
             * and {@code onClose} still arrives afterwards. Without this, one
             * malformed transfer calls {@link #failCurrentDownload} repeatedly —
             * and every call after the first abandons whichever *other* file the
             * queue has moved on to. One bad file would take several good ones
             * with it.
             */
            private boolean finished;

            @Override
            public void onConnect(final CommunicatorV2.ServiceWriter writer) {
                // Six bytes, of which only the handle is ours to fill in. The
                // shape is upstream's, established by observation of Garmin's
                // own app; the surrounding zeros have no known meaning and
                // guessing at them is how this stops working.
                final ByteBuffer request = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN);
                request.put((byte) 0x00);
                request.put((byte) 0x00);
                request.putShort((short) fileHandle);
                request.put((byte) 0x00);
                request.put((byte) 0x00);
                writer.write("request file " + fileHandle, request.array());
            }

            @Override
            public void onMessage(final byte[] value) {
                if (finished) {
                    return;
                }
                if (!started) {
                    // The watch acknowledges with three zero bytes before any
                    // file content. Anything else means it is not going to send
                    // the file, and buffering it would produce a plausible
                    // looking file full of protocol noise.
                    if (!ArrayUtils.equals(new byte[]{0, 0, 0}, value, 0)) {
                        LOG.error("V2 transfer for handle {} opened with {} instead of an ack",
                                fileHandle, GB.hexdump(value));
                        finished = true;
                        failCurrentDownload();
                        return;
                    }
                    started = true;
                    return;
                }
                buffer.write(value, 0, value.length);
                onFileDownloadProgress(buffer.size());
            }

            @Override
            public void onClose() {
                if (finished) {
                    return;
                }
                finished = true;
                if (buffer.size() == 0) {
                    LOG.warn("V2 transfer for handle {} closed with nothing in the buffer", fileHandle);
                    failCurrentDownload();
                    return;
                }

                final byte[] inflated = CompressionUtils.INSTANCE.inflate(buffer.toByteArray());
                if (inflated == null || inflated.length == 0) {
                    LOG.error("Could not inflate {} bytes for handle {}", buffer.size(), fileHandle);
                    failCurrentDownload();
                    return;
                }
                LOG.debug("Inflated {} bytes to {}", buffer.size(), inflated.length);

                final File written;
                try {
                    written = writeSyncFile(syncFile, inflated);
                } catch (final IOException e) {
                    LOG.error("Could not save the V2 file for handle " + fileHandle, e);
                    failCurrentDownload();
                    return;
                }

                final SyncFileDownloadedDeviceEvent event = new SyncFileDownloadedDeviceEvent();
                event.syncFile = syncFile;
                event.localPath = written.getAbsolutePath();
                rememberIfActivity(syncFile);
                // Goes through the normal event path, which is what releases
                // currentlyDownloading and hands the file to Tracks.
                evaluateGBDeviceEvent(event);
                processDownloadQueue();
            }
        });
    }

    /**
     * Where a file pulled over the newer protocol lands.
     *
     * <p>The old path gets a name from the directory entry, which carries a
     * type, a date and a file number. A protobuf sync file carries a type name
     * and a 128-bit id and no date at all, so the name is built from what
     * exists. Deterministic on purpose: the same file offered twice writes the
     * same path, which is what lets a re-offer be recognised rather than
     * duplicated.
     */
    private File writeSyncFile(final GdiFileSyncService.File syncFile, final byte[] bytes) throws IOException {
        final File out = new File(getWritableExportDirectory(), syncFileOutputPath(syncFile));
        final File parent = out.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent);
        }
        try (FileOutputStream stream = new FileOutputStream(out)) {
            stream.write(bytes);
        }
        return out;
    }

    /** The counterpart of {@code DirectoryEntry.getOutputPath()} for the newer protocol. */
    private String syncFileOutputPath(final GdiFileSyncService.File syncFile) {
        final String typeName = syncFile.hasType() && syncFile.getType().hasName()
                ? syncFile.getType().getName() : "UNKNOWN";
        return String.format(Locale.ROOT, "%s/%s_%d_%d.fit",
                typeName, typeName, syncFile.getId().getId1(), syncFile.getId().getId2());
    }

    /**
     * Give up on the file in flight and let the queue move.
     *
     * <p>Both halves are needed. Clearing {@link #currentlyDownloading} without
     * pumping the queue leaves the run stalled with nothing to restart it,
     * because a V2 transfer produces no GFDI traffic of its own.
     */
    private void failCurrentDownload() {
        synchronized (downloadLock) {
            final FileDownloadedDeviceEvent event = new FileDownloadedDeviceEvent();
            event.success = false;
            evaluateGBDeviceEvent(event);
            processDownloadQueueLocked();
        }
    }

    /**
     * Tell the watch it may reclaim a file pulled over the newer protocol.
     *
     * <p>The counterpart to {@link #archiveOnWatch}, and separate from it for
     * the same reason the two download paths are separate: the older protocol
     * addresses a file by a 16-bit directory index, the newer one by a 128-bit
     * id plus a type name, and there is no conversion between them.
     */
    public void markSyncedOnWatch(final long id1, final long id2, final String typeName) {
        final GdiFileSyncService.File.Builder builder = GdiFileSyncService.File.newBuilder()
                .setId(GdiFileSyncService.FileId.newBuilder().setId1(id1).setId2(id2));
        // Only the id reaches the wire — the type is here because the vendored
        // handler inspects it when configured to fetch unknown files, and
        // omitting it entirely would change that decision rather than leave it
        // alone.
        if (typeName != null && !typeName.isEmpty()) {
            builder.setType(GdiFileSyncService.FileType.newBuilder().setName(typeName));
        }
        final GdiFileSyncService.File file = builder.build();
        final GdiFileSyncService.FileSyncService command =
                protocolBufferHandler.getFileSyncServiceHandler().markSynced(file);
        if (command == null) {
            // Upstream refuses to clear the sync flag on types it does not
            // itself download (settings, sport definitions, watch state — see
            // FileType.FILETYPE's `pull` flag) because doing so could make the
            // watch treat internal state as reclaimable. Tracks still pulls
            // them, since it ships bytes to a server that can read anything —
            // but with no way to tell the watch to stop offering this exact
            // id, it would otherwise be pulled and uploaded again on every
            // single future sync, forever. This call only happens once the
            // bytes are already confirmed on the server, so remembering here
            // costs nothing but a few bytes of preference and saves every
            // later sync from repeating radio time on data it already has.
            LOG.debug("Watch offers no way to mark {}/{} ({}) as synced; "
                    + "remembering not to pull it again", id1, id2, typeName);
            rememberDeliveredUnmarkable(syncFileKey(id1, id2, typeName));
            return;
        }
        sendProtobufRequest("mark file as synced",
                GdiSmartProto.Smart.newBuilder().setFileSyncService(command).build());
    }

    /** Stable identity for a newer-protocol file, independent of the local filename. */
    private static String syncFileKey(final long id1, final long id2, final String typeName) {
        return (typeName == null || typeName.isEmpty() ? "UNKNOWN" : typeName) + "_" + id1 + "_" + id2;
    }

    // ── missed-activity recovery (see FileSyncServiceHandler.recovering) ─────

    private static final String PREF_KNOWN_ACTIVITY_IDS = "garmin_known_activity_ids";
    private static final String PREF_LAST_ACTIVITY_RECOVERY = "garmin_last_activity_recovery";
    private static final String PREF_RECOVERY_PAGE = "garmin_activity_recovery_page";
    private static final long RECOVERY_INTERVAL_MS = 6L * 3600_000L;
    private static final long RECOVERY_WINDOW_S = 60L * 86400L;
    /** Seconds from the Unix epoch to Garmin's (1989-12-31T00:00:00Z). */
    private static final long GARMIN_EPOCH_S = 631065600L;

    /**
     * Where the last recovery listing ended, as the watch's page id, or null
     * before the first one has finished.
     *
     * <p>Page ids are a sequence the watch assigns as files are written, and
     * a listing asked to start from one returns only what came after it. That
     * turns the recovery listing from an every-few-hours sweep of months of
     * files into a page or so per sync, which is what lets it run on every
     * sync — and it has to. Measured on 2026-09-30: a run saved at 18:33 was
     * absent from the 18:36 listing of unsynced files, with nothing else on
     * the phone having talked to the watch, so it would have sat on the watch
     * until the next six-hourly sweep. The cause is the watch's own Wi-Fi
     * Auto Upload to a Garmin Connect account: an activity uploaded the moment
     * it is saved is born flagged synced (file status 0 where every other new
     * file reads 1), with no phone involved. Confirmed by two recordings made
     * with the phone's Bluetooth off, one with Tracks' Explore sync disabled;
     * both arrived pre-flagged. Onboarding now tells people to turn it off
     * (WatchUploadsStep), but anyone who has not still has their activities
     * reach Tracks through this.
     */
    public Integer activityRecoveryStartPage() {
        final int page = getDevicePrefs().getPreferences().getInt(PREF_RECOVERY_PAGE, 0);
        return page > 0 ? page : null;
    }

    public void rememberActivityRecoveryPage(final int page) {
        if (page > 0) {
            getDevicePrefs().getPreferences().edit()
                    .putInt(PREF_RECOVERY_PAGE, page)
                    .putLong(PREF_RECOVERY_DONE_AT, System.currentTimeMillis() / 1000L)
                    .apply();
        }
    }

    private static final String PREF_RECOVERY_DONE_AT = "garmin_activity_recovery_done_at";

    /** See {@link #takeActivitiesSyncedElsewhere}. */
    private final java.util.concurrent.atomic.AtomicInteger syncedElsewhere =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * How many recovered activities, since the last call, were recorded after
     * the previous recovery listing finished — and so were flagged synced by
     * something other than Tracks in between. On this watch that is its own
     * Wi-Fi Auto Upload (docs/garmin-ble-protocol.md), so the app answers it
     * by switching that off again. Older recordings are not counted: a first
     * sweep finds weeks of activities Garmin Connect legitimately took before
     * Tracks existed, and those say nothing about today's settings.
     */
    public int takeActivitiesSyncedElsewhere() {
        return syncedElsewhere.getAndSet(0);
    }

    /**
     * True at most once per interval: a sweep from the first page rather than
     * from {@link #activityRecoveryStartPage}. Kept as the backstop for the
     * page-id assumption, and it is how the first page id is learned. Claiming
     * the slot here keeps a failed run from retrying in a loop.
     */
    public boolean activityRecoveryDue() {
        final SharedPreferences prefs = getDevicePrefs().getPreferences();
        final long now = System.currentTimeMillis();
        if (now - prefs.getLong(PREF_LAST_ACTIVITY_RECOVERY, 0L) < RECOVERY_INTERVAL_MS) {
            return false;
        }
        prefs.edit().putLong(PREF_LAST_ACTIVITY_RECOVERY, now).apply();
        return true;
    }

    /**
     * Whether a listed activity is recent and has never come down to this phone.
     *
     * <p>Recency comes from the id: the high 32 bits of id1 are the recording's
     * Garmin timestamp (checked against files whose start times are known).
     * That is what bounds the first recovery after install, when nothing is
     * known yet, to weeks of activities rather than the watch's whole history.
     */
    public boolean wantsRecoveredActivity(final GdiFileSyncService.File file) {
        final long recordedS = (file.getId().getId1() >>> 32) + GARMIN_EPOCH_S;
        if (System.currentTimeMillis() / 1000L - recordedS > RECOVERY_WINDOW_S) {
            return false;
        }
        final boolean wanted = !getDevicePrefs()
                .getStringSet(PREF_KNOWN_ACTIVITY_IDS, Collections.emptySet())
                .contains(activityKey(file));
        final long lastDone = getDevicePrefs().getPreferences().getLong(PREF_RECOVERY_DONE_AT, 0L);
        if (wanted && lastDone > 0 && recordedS >= lastDone) {
            syncedElsewhere.incrementAndGet();
        }
        return wanted;
    }

    private static String activityKey(final GdiFileSyncService.File file) {
        // By id alone: the type name the watch reports is not stable across connections.
        return file.getId().getId1() + "_" + file.getId().getId2();
    }

    private void rememberIfActivity(final GdiFileSyncService.File file) {
        if (!file.hasType()
                || FileType.FILETYPE.findByTypeName(file.getType().getName()) != FileType.FILETYPE.ACTIVITY) {
            return;
        }
        final SharedPreferences prefs = getDevicePrefs().getPreferences();
        final Set<String> updated = new HashSet<>(
                prefs.getStringSet(PREF_KNOWN_ACTIVITY_IDS, Collections.emptySet()));
        if (updated.add(activityKey(file))) {
            prefs.edit().putStringSet(PREF_KNOWN_ACTIVITY_IDS, updated).apply();
        }
    }

    private static final String PREF_DELIVERED_UNMARKABLE_FILES = "garmin_delivered_unmarkable_files";

    /**
     * Stop fetching a file that stays on the watch on purpose (a course or a
     * saved place, see DeviceIntegration.keepPulledOnDevice). The same list as
     * the files the watch will not let us mark, because the effect wanted is
     * the same: delivered, so never queued again, and the watch's copy alone.
     */
    public void rememberKeptOnWatch(final long id1, final long id2, final String typeName) {
        rememberDeliveredUnmarkable(syncFileKey(id1, id2, typeName));
    }

    private boolean isDeliveredUnmarkable(final String key) {
        return getDevicePrefs()
                .getStringSet(PREF_DELIVERED_UNMARKABLE_FILES, Collections.emptySet())
                .contains(key);
    }

    private void rememberDeliveredUnmarkable(final String key) {
        final SharedPreferences prefs = getDevicePrefs().getPreferences();
        final Set<String> updated = new HashSet<>(
                prefs.getStringSet(PREF_DELIVERED_UNMARKABLE_FILES, Collections.emptySet()));
        if (updated.add(key)) {
            prefs.edit().putStringSet(PREF_DELIVERED_UNMARKABLE_FILES, updated).apply();
        }
    }

    // ── installed-app management ─────────────────────────────────────────────
    //
    // The watch keeps a list of Connect IQ apps it has, and will delete one on
    // request — the same GDI service Garmin Connect uses. Tracks drives it so
    // the phone can remove the music app (or any other) without a cable. The
    // response arrives asynchronously on the BLE thread; these one-shot
    // listeners hand it back to whoever asked.

    private volatile Consumer<List<GdiInstalledAppsService.InstalledAppsService.InstalledApp>> appListListener;
    private volatile Consumer<Boolean> appDeleteListener;

    public void setAppListListener(final Consumer<List<GdiInstalledAppsService.InstalledAppsService.InstalledApp>> listener) {
        appListListener = listener;
    }

    public void setAppDeleteListener(final Consumer<Boolean> listener) {
        appDeleteListener = listener;
    }

    /**
     * Ask the watch which apps of one type it has installed.
     *
     * Deliberately one type rather than ALL. A fenix answers ALL with its
     * entire furniture — every activity profile and built-in — which is both a
     * lot of bytes over a 23-byte-MTU link and a list nothing should offer to
     * delete.
     */
    public void requestInstalledApps(final GdiInstalledAppsService.InstalledAppsService.AppType type) {
        sendProtobufRequest("list installed apps", GdiSmartProto.Smart.newBuilder()
                .setInstalledAppsService(GdiInstalledAppsService.InstalledAppsService.newBuilder()
                        .setGetInstalledAppsRequest(
                                GdiInstalledAppsService.InstalledAppsService.GetInstalledAppsRequest.newBuilder()
                                        .setAppType(type)))
                .build());
    }

    /** Delete one installed app, named by the id and type the watch reported. */
    public void deleteInstalledApp(final byte[] storeAppId,
                                   final GdiInstalledAppsService.InstalledAppsService.AppType type) {
        sendProtobufRequest("delete app", GdiSmartProto.Smart.newBuilder()
                .setInstalledAppsService(GdiInstalledAppsService.InstalledAppsService.newBuilder()
                        .setDeleteAppRequest(
                                GdiInstalledAppsService.InstalledAppsService.DeleteAppRequest.newBuilder()
                                        .setStoreAppId(ByteString.copyFrom(storeAppId))
                                        .setAppType(type)))
                .build());
    }

    public void onAppInfoReq() {
        LOG.debug("Watch asked for app info");
    }

    public void onAppListReceived(
            final List<GdiInstalledAppsService.InstalledAppsService.InstalledApp> apps) {
        LOG.debug("Watch reported {} installed app(s)", apps == null ? 0 : apps.size());
        final Consumer<List<GdiInstalledAppsService.InstalledAppsService.InstalledApp>> listener = appListListener;
        if (listener != null) {
            listener.accept(apps == null ? java.util.Collections.emptyList() : apps);
        }
    }

    public void onAppDeleteResult(final boolean ok) {
        LOG.debug("Watch reported app delete {}", ok ? "ok" : "failed");
        final Consumer<Boolean> listener = appDeleteListener;
        if (listener != null) {
            listener.accept(ok);
        }
    }

    /**
     * An image to show alongside a notification on the watch.
     *
     * <p>Null: Tracks forwards notification text only. Attachments mean
     * decoding and rescaling a bitmap per notification, which is real battery
     * cost on a device whose whole point is lasting a week in the field.
     */
    protected Bitmap getNotificationAttachmentBitmap(final int notificationId) {
        return null;
    }

    protected void unregisterHandler(final MessageHandler registered) {
        messageHandlers.remove(registered);
    }

    protected void registerHandler(final MessageHandler handler) {
        if (handler != null && !messageHandlers.contains(handler)) {
            messageHandlers.add(handler);
        }
    }

    public List<MessageHandler> getMessageHandlers() {
        return Collections.unmodifiableList(messageHandlers);
    }

    // ── Outgoing plumbing ────────────────────────────────────────────────────

    /**
     * Send a protobuf request wrapped in a GFDI envelope.
     *
     * <p>Garmin runs several services (file sync, app config, device settings)
     * over protobuf carried inside GFDI rather than as native GFDI messages.
     * The vendored handlers build the payload; this puts it on the wire.
     */
    /**
     * Open an Explore sync as soon as the watch is ready.
     *
     * <p>Here rather than in {@code completeInitialization} because that runs
     * while the handshake is still in flight; this is the point where the watch
     * has answered everything and will actually talk. See
     * {@link ExploreSyncHandler#startSyncRequest} for why Tracks asks rather
     * than waiting to be asked.
     *
     * <p>Failures are logged and swallowed: an Explore sync is additive to
     * everything else this connection does, and a watch that will not open one
     * should not cost the user their file sync.
     */
    /**
     * Mark a course for removal and push that decision now.
     *
     * <p>Returns false when this phone has never learned the watch's UUID for
     * that name, which happens before the first Explore sync of a connection.
     * A caller should treat that as "not yet", not as "cannot".
     */
    public boolean removeCourseFromWatch(final String courseName) {
        if (!protocolBufferHandler.getExploreSyncHandler().tombstoneCourseNamed(courseName)) {
            return false;
        }
        // Straight into a sync rather than waiting for the next connection: the
        // watch only reads the phone's library during a session, so a tombstone
        // sitting in preferences changes nothing until one runs.
        startExploreSync();
        return true;
    }

    private void startExploreSync() {
        try {
            sendProtobufRequest(
                    "start explore sync",
                    GdiSmartProto.Smart.newBuilder()
                            .setExploreSyncService(
                                    protocolBufferHandler.getExploreSyncHandler().startSyncRequest())
                            .build());
        } catch (final Exception e) {
            LOG.warn("Could not start an Explore sync", e);
        }
    }

    public void sendProtobufRequest(final String taskName, final GdiSmartProto.Smart payload) {
        sendOutgoingMessage(taskName, protocolBufferHandler.prepareProtobufRequest(payload));
    }

    public void sendOutgoingMessage(final String taskName, final GFDIMessage message) {
        if (message == null) {
            return;
        }
        sendOutgoingMessage(taskName, message.getOutgoingMessage());
    }

    public void sendOutgoingMessage(final String taskName, final byte[] message) {
        if (message == null) {
            return;
        }
        final ICommunicator comm = communicator;
        if (comm == null) {
            LOG.warn("Dropping '{}': no communicator (watch not connected)", taskName);
            return;
        }
        LOG.debug("OUTGOING {}: {}", taskName, GB.hexdump(message));
        comm.sendMessage(taskName, message);
    }

    /**
     * Acknowledge a received message.
     *
     * <p>Separate from {@link #sendOutgoingMessage} because the acknowledgement
     * is a different byte stream from the reply — {@code getAckBytestream}
     * rather than {@code getOutgoingMessage} — and a message can carry both.
     */
    private void sendAck(final String taskName, final GFDIMessage message) {
        if (message == null) {
            return;
        }
        sendOutgoingMessage(taskName, message.getAckBytestream());
    }
}
