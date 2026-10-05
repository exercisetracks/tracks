// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.service;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;

import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

/**
 * Shim for Gadgetbridge's {@code DeviceSupport}.
 *
 * <p>Upstream this interface is the contract between the device service and a
 * watch driver, and it carries the whole feature surface — alarms, contacts,
 * loyalty cards, world clocks, camera remote, forty-odd {@code onXxx} methods
 * that a driver either implements or ignores.
 *
 * <p>Tracks' equivalent contract is {@code com.tracks.device.DeviceIntegration},
 * on the other side of the vendoring boundary. So this shim is only what the
 * vendored BLE transport itself needs in order to talk about "the thing driving
 * this connection" — connection lifecycle and identity, nothing about features.
 * Everything else is reached through {@code GarminSupport} directly.
 */
public interface DeviceSupport {

    void setContext(GBDevice device, BluetoothAdapter btAdapter, Context context);

    boolean connect();

    /**
     * Whether to let Android reconnect on its own when the watch reappears.
     *
     * <p>This is the single most important battery decision in the BLE layer.
     * Autoconnect is handled by the Bluetooth controller rather than by us
     * waking to scan, which is why Tracks uses it in preference to any polling
     * loop of its own.
     */
    boolean useAutoConnect();

    boolean isConnected();

    boolean isConnecting();

    void dispose();

    GBDevice getDevice();

    BluetoothAdapter getBluetoothAdapter();

    Context getContext();

    boolean getAutoReconnect();

    void setAutoReconnect(boolean enable);

    boolean getScanReconnect();

    void setScanReconnect(boolean enable);

    /**
     * Sleep as Android integration, or null when there is none.
     *
     * <p>Always null here. Kept in the interface because the vendored realtime
     * path asks for it and null-checks the answer — that null is upstream's own
     * "not configured" signal, so Tracks does not need to fake a sender.
     */
    SleepAsAndroidSender getSleepAsAndroidSender();
}
