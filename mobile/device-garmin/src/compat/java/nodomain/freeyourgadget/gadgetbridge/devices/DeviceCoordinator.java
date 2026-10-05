// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.devices;

import android.bluetooth.BluetoothDevice;

/**
 * Shim for Gadgetbridge's {@code DeviceCoordinator}.
 *
 * <p>Upstream this is a large interface — one implementation per supported
 * watch — describing everything a device can do, which settings screens it
 * gets, and how to build its sample providers. Tracks does not have that shape:
 * capabilities live in {@code com.tracks.device.DeviceCapabilities} on the
 * {@code DeviceIntegration} side of the boundary.
 *
 * <p>Only two methods survive the vendoring boundary, both in the BLE transport
 * and both about how to open the GATT connection. So this is those two, with
 * answers chosen for an expedition tool rather than copied from upstream.
 */
public class DeviceCoordinator {

    /**
     * Whether this transport may ask Android to change the BLE connection
     * priority at all.
     *
     * <p>True. What that buys is described in
     * {@code GarminSupport.updateConnectionPriority}, which is what actually
     * raises the priority for a transfer and lowers it afterwards — this flag
     * only opens the door.
     *
     * <p>An earlier version of this comment claimed the vendored transport
     * dropped to low power between transfers. It does not: it sets the priority
     * exactly once, at service discovery, and never touches it again. The
     * measured cost of believing that was a 1.1 MB activity taking ten minutes.
     */
    public boolean supportsConnectionPriority() {
        return true;
    }

    /**
     * PHY layers to request when connecting.
     *
     * <p>1M only. 2M is faster but shorter-ranged and not universally
     * supported; LE Coded trades throughput for range. Garmin's own companion
     * protocol negotiates on 1M, and a watch on the wrist is centimetres away,
     * so there is nothing to buy here.
     */
    public int getBlePhyMask() {
        return BluetoothDevice.PHY_LE_1M_MASK;
    }
}
