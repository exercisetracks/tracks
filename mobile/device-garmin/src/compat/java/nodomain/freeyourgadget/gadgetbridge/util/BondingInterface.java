// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.util;

import android.content.Context;

import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceCandidate;

/**
 * Shim for the callback a bonding flow reports back to.
 *
 * <p>Upstream this is implemented by a pairing Activity. Tracks pairs through
 * CompanionDeviceManager, which runs the system picker and returns an
 * association that survives reboots — so the bond is usually already in place
 * before the BLE layer runs at all.
 */
public interface BondingInterface {
    void onBondingComplete(boolean success);

    GBDeviceCandidate getCurrentTarget();

    /** Whether to connect once bonded, or just bond and stop. */
    boolean getAttemptToConnect();

    void registerBroadcastReceivers();

    void unregisterBroadcastReceivers();

    Context getContext();
}
