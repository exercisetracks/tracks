// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.service;

/**
 * Shim for Gadgetbridge's {@code AbstractBluetoothDeviceSupport}.
 *
 * <p>Upstream added it in 0.94, between {@code AbstractDeviceSupport} and the
 * BLE support classes, to hold the Bluetooth adapter and refuse a USB
 * context. Tracks' {@link AbstractDeviceSupport} shim already keeps the
 * adapter and has no USB devices, so this is only the class the vendored
 * {@code AbstractBTLEDeviceSupport} now extends.
 */
public abstract class AbstractBluetoothDeviceSupport extends AbstractDeviceSupport {
}
