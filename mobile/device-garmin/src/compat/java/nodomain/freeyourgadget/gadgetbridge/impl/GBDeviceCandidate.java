// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.impl;

import android.bluetooth.BluetoothDevice;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Shim for a discovered-but-not-yet-paired device.
 *
 * <p>Upstream this carries scan results into Gadgetbridge's pairing UI. Tracks
 * pairs through CompanionDeviceManager, which runs the system's own picker and
 * hands back an association that survives reboots — so there is no scan list of
 * our own to model. Kept because the bonding helpers name the type.
 */
public class GBDeviceCandidate implements Parcelable {
    private final BluetoothDevice device;

    public GBDeviceCandidate(final BluetoothDevice device) {
        this.device = device;
    }

    /**
     * Upstream's scan-result constructor. Tracks pairs through
     * CompanionDeviceManager rather than scanning, so the extra scan metadata
     * (signal strength, advertised services, manufacturer data) has nothing to
     * filter and is accepted but not kept.
     */
    public GBDeviceCandidate(final BluetoothDevice device, final short rssi,
                             final android.os.ParcelUuid[] serviceUuids,
                             final android.util.SparseArray<byte[]> manufacturerSpecificData) {
        this(device);
    }

    protected GBDeviceCandidate(final Parcel in) {
        device = in.readParcelable(BluetoothDevice.class.getClassLoader());
    }

    public BluetoothDevice getDevice() { return device; }

    public String getMacAddress() {
        return device != null ? device.getAddress() : "";
    }

    @Override
    public void writeToParcel(final Parcel dest, final int flags) {
        dest.writeParcelable(device, flags);
    }

    @Override
    public int describeContents() { return 0; }

    public static final Creator<GBDeviceCandidate> CREATOR = new Creator<GBDeviceCandidate>() {
        @Override public GBDeviceCandidate createFromParcel(Parcel in) { return new GBDeviceCandidate(in); }
        @Override public GBDeviceCandidate[] newArray(int size) { return new GBDeviceCandidate[size]; }
    };
}
