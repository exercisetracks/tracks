// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.service.btle;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.content.Intent;

import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

/**
 * Shim for Gadgetbridge's BLE intent API — permanently disabled.
 *
 * <p>Upstream this lets other Android apps drive a connected device by
 * broadcasting intents: read this characteristic, write those bytes. It is a
 * useful power-user feature and a poor fit here. Tracks pairs one watch through
 * CompanionDeviceManager and treats it as the source of expedition data; an
 * intent surface that any installed app can send to would be both an attack
 * surface and a way for a third party to keep the radio awake.
 *
 * <p>{@link #isEnabled} returns false, so {@code AbstractBTLESingleDeviceSupport}
 * never constructs one and its field stays null — every other call site is
 * already null-guarded. The instance methods exist only to satisfy the
 * compiler and should be unreachable.
 */
public class BleIntentApi {

    public BleIntentApi(final AbstractBTLESingleDeviceSupport support, final int deviceIdx) {
        throw new UnsupportedOperationException(
                "The BLE intent API is deliberately disabled in Tracks");
    }

    /** Always false: Tracks does not expose device control to other apps. */
    public static boolean isEnabled(final GBDevice device) {
        return false;
    }

    public static Intent getBleApiIntent(final String deviceAddress, final String action) {
        return null;
    }

    public void handleBLEApiPrefs() {}

    public void initializeDevice(final TransactionBuilder builder) {}

    public void addService(final BluetoothGattService service) {}

    public void onCharacteristicChanged(final BluetoothGattCharacteristic characteristic, final byte[] value) {}

    public void onSendConfiguration(final String config) {}

    public void onReceive(final Context context, final Intent intent) {}

    public Context getContext() {
        return null;
    }

    public BtLEQueue getQueue() {
        return null;
    }

    public GBDevice getDevice() {
        return null;
    }

    public void dispose() {}
}
