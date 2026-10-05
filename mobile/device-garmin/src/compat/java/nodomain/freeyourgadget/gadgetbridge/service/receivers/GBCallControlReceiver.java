// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.service.receivers;

/**
 * Shim for Gadgetbridge's call-control receiver.
 *
 * <p>Accepting or rejecting a call from the watch needs telephony permissions
 * Tracks does not request. Constants keep upstream's values so a later pull
 * that wires this up stays consistent.
 */
public class GBCallControlReceiver {
    public static final String ACTION_CALLCONTROL =
            "nodomain.freeyourgadget.gadgetbridge.callcontrol";
    public static final String EXTRA_CALLCMD = "command";
}
