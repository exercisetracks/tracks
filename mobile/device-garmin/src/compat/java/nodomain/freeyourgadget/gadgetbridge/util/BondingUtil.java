// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.util;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shim for Gadgetbridge's bonding helpers.
 *
 * <p>Tracks establishes the bond through CompanionDeviceManager before the BLE
 * layer connects, so by the time the vendored code would start a bond, one
 * normally exists. This creates a bond directly if the OS has not already, and
 * otherwise gets out of the way.
 */
public final class BondingUtil {
    private static final Logger LOG = LoggerFactory.getLogger(BondingUtil.class);

    private BondingUtil() {}

    /**
     * Bond if not already bonded, then let the caller carry on.
     *
     * <p>The already-bonded case is the common one and must not re-bond:
     * calling createBond on a bonded device can drop the existing pairing,
     * which on a watch means the user re-pairs by hand in the field.
     */
    public static void tryBondThenComplete(final BondingInterface bondingInterface,
                                           final BluetoothDevice device) {
        if (device == null) {
            return;
        }
        if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
            bondingInterface.onBondingComplete(true);
            return;
        }
        LOG.info("Requesting a bond with {}", device.getAddress());
        bondingInterface.onBondingComplete(device.createBond());
    }

    /** Upstream returns a receiver watching for bond-state changes. */
    public static BroadcastReceiver getBondingReceiver(final BondingInterface bondingInterface) {
        return new BroadcastReceiver() {
            @Override
            public void onReceive(final Context context, final Intent intent) {
                final int state = intent.getIntExtra(
                        BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
                if (state == BluetoothDevice.BOND_BONDED) {
                    bondingInterface.onBondingComplete(true);
                } else if (state == BluetoothDevice.BOND_NONE) {
                    bondingInterface.onBondingComplete(false);
                }
            }
        };
    }

    public static BroadcastReceiver getPairingReceiver(final BondingInterface bondingInterface) {
        return getBondingReceiver(bondingInterface);
    }
}
