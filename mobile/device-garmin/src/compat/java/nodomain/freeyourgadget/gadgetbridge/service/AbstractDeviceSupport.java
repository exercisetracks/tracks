// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.service;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

/**
 * Shim for Gadgetbridge's {@code AbstractDeviceSupport}.
 *
 * <p>Upstream's is 654 lines, and nearly all of it is one job done many times:
 * a device event arrives from the watch, and it is turned into a system
 * broadcast, a database write, or a notification. Every one of those three
 * destinations is something Tracks answers differently — its data goes to its
 * own server, its UI reads a SQLDelight mirror, and it has no reason to
 * broadcast a watch's address to every app on the phone.
 *
 * <p>So this keeps the parts the vendored BLE transport genuinely needs —
 * identity, connection state, the Bluetooth adapter — and replaces the event
 * fan-out with a listener list that {@code GarminIntegration} subscribes to.
 * That is the whole point of the boundary: events leave the vendored code as
 * plain objects and Tracks decides what they mean.
 */
public abstract class AbstractDeviceSupport implements DeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(AbstractDeviceSupport.class);

    /** Receives every device event the vendored protocol code raises. */
    public interface EventListener {
        void onDeviceEvent(GBDeviceEvent event);
    }

    protected GBDevice gbDevice;
    protected BluetoothAdapter btAdapter;
    protected Context context;
    protected boolean autoReconnect;
    protected boolean scanReconnect;

    // Copy-on-write because events are raised from the BLE callback thread
    // while the UI subscribes and unsubscribes from the main thread.
    private final List<EventListener> eventListeners = new CopyOnWriteArrayList<>();

    @Override
    public void setContext(final GBDevice device, final BluetoothAdapter btAdapter, final Context context) {
        this.gbDevice = device;
        this.btAdapter = btAdapter;
        this.context = context;
    }

    public void addEventListener(final EventListener listener) {
        eventListeners.add(listener);
    }

    public void removeEventListener(final EventListener listener) {
        eventListeners.remove(listener);
    }

    /**
     * Hand a device event to whoever is listening.
     *
     * <p>Upstream this method is a ~200-line switch that broadcasts intents,
     * writes to greenDAO, and raises notifications. Here it is a fan-out and
     * nothing more — interpreting the event is Tracks' job, on the other side
     * of the boundary, where it can reach the server and the local mirror.
     *
     * <p>A listener that throws must not take the BLE connection down with it,
     * hence the per-listener catch: losing one subscriber is recoverable,
     * dropping the watch connection mid-transfer is not.
     */
    public void evaluateGBDeviceEvent(final GBDeviceEvent deviceEvent) {
        if (deviceEvent == null) {
            return;
        }
        for (final EventListener listener : eventListeners) {
            try {
                listener.onDeviceEvent(deviceEvent);
            } catch (final Exception e) {
                LOG.error("Device event listener failed for {}", deviceEvent, e);
            }
        }
    }

    /**
     * Upstream applies a changed preference to the connected device. Tracks
     * carries no per-device settings screen, so there is nothing to push; the
     * BLE base class still calls {@code super} from its own override.
     */
    public void onSendConfiguration(final String config) {
        LOG.debug("Ignoring configuration change '{}' — Tracks has no device settings screen", config);
    }

    /**
     * Per-device preferences. Subclasses narrow the return type — Garmin's
     * returns GarminPrefs — which is why this is not final.
     */
    public nodomain.freeyourgadget.gadgetbridge.util.preferences.DevicePrefs getDevicePrefs() {
        return nodomain.freeyourgadget.gadgetbridge.GBApplication.getDevicePrefs(getDevice());
    }

    @Override
    public GBDevice getDevice() {
        return gbDevice;
    }

    @Override
    public BluetoothAdapter getBluetoothAdapter() {
        return btAdapter;
    }

    @Override
    public Context getContext() {
        return context;
    }

    @Override
    public boolean isConnected() {
        return gbDevice != null && gbDevice.isConnected();
    }

    @Override
    public boolean isConnecting() {
        return gbDevice != null && gbDevice.isConnecting();
    }

    public boolean isInitialized() {
        return gbDevice != null && gbDevice.isInitialized();
    }

    @Override
    public boolean getAutoReconnect() {
        return autoReconnect;
    }

    @Override
    public void setAutoReconnect(final boolean enable) {
        this.autoReconnect = enable;
    }

    @Override
    public boolean getScanReconnect() {
        return scanReconnect;
    }

    @Override
    public void setScanReconnect(final boolean enable) {
        this.scanReconnect = enable;
    }

    /** Always null — see {@link SleepAsAndroidSender}. */
    @Override
    public SleepAsAndroidSender getSleepAsAndroidSender() {
        return null;
    }

    /**
     * How long to wait after bonding before discovering services.
     *
     * <p>Kept because {@code BtLEQueue} asks for it. The delay exists because
     * some controllers report bonding complete before the link is actually
     * usable, and discovering too early yields an empty service list.
     */
    public long getServiceDiscoveryDelay(final boolean bonded) {
        return 0;
    }

    /** Upstream lets a driver rewrite notification text. Tracks does not. */
    public String customStringFilter(final String inputString) {
        return inputString;
    }

    public boolean canReconnect() {
        return true;
    }

    public List<GBDeviceEvent> getPendingEvents() {
        return new ArrayList<>();
    }
}
