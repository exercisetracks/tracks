// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.deviceevents;

import android.content.Context;

import androidx.annotation.NonNull;

import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

/**
 * Battery level reported by the watch.
 *
 * <p>Upstream's version writes to greenDAO, raises low-battery notifications and
 * opens a battery activity — 15 imports for what is, on the wire, one number.
 * Here it is that number, carried outward through
 * {@code AbstractDeviceSupport.evaluateGBDeviceEvent} for Tracks to display.
 *
 * <p>Public fields rather than accessors because the vendored code assigns them
 * directly, and matching upstream's shape is what keeps refreshes a file copy.
 */
public class GBDeviceEventBatteryInfo extends GBDeviceEvent {
    /** Percentage, 0-100. */
    public short level = -1;

    /** Battery index for watches that report more than one (most report zero). */
    public int batteryIndex = 0;

    @Override
    public String toString() {
        return "battery[" + batteryIndex + "]=" + level + "%";
    }

    /**
     * Upstream acts on the event here — writing to its database, posting a
     * notification, broadcasting to its UI. Tracks does none of that from
     * inside the vendored layer: the event is fanned out by
     * {@code AbstractDeviceSupport.evaluateGBDeviceEvent} to listeners on the
     * Tracks side, which are the only ones that can reach the server.
     */
    @Override
    public void evaluate(@NonNull final Context context, @NonNull final GBDevice device) {
        // Intentionally empty — see the class note.
    }
}
