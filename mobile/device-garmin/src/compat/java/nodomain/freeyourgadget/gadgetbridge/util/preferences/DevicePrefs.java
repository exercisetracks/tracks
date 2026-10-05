// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.util.preferences;

import android.content.SharedPreferences;

import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

/**
 * Shim for Gadgetbridge's per-device preferences.
 *
 * <p>Upstream this is backed by a settings screen per watch model, generated
 * from a coordinator's declared capabilities. Tracks has no such screen — the
 * watch is configured on the watch, and the phone's job is to move files — so
 * this reads from the same {@link SharedPreferences} store and answers the
 * handful of questions the vendored protocol code actually asks, with defaults
 * chosen for an expedition tool.
 *
 * <p>The defaults are the interesting part; each one is a battery or
 * correctness decision rather than a copy of upstream's.
 */
public class DevicePrefs extends Prefs {
    private final GBDevice gbDevice;

    public DevicePrefs(final SharedPreferences preferences, final GBDevice gbDevice) {
        super(preferences);
        this.gbDevice = gbDevice;
    }

    public GBDevice getDevice() {
        return gbDevice;
    }

    /**
     * Whether to download file types we do not recognise.
     *
     * <p>True. Tracks does not parse FIT on the phone at all — it ships the
     * bytes to its own server, which does. So "unknown to Gadgetbridge's
     * importer" says nothing about whether Tracks can use the file, and
     * skipping it would silently lose data that the server could have read.
     */
    public boolean getFetchUnknownFiles() {
        return getBoolean("fetch_unknown_files", true);
    }

    /**
     * Drop to a low-power connection interval when idle.
     *
     * <p>True, and the reason this class exists at all. Between transfers the
     * watch and phone exchange almost nothing, and holding a fast connection
     * interval to carry no data is pure drain. The transport raises priority
     * again for the duration of a transfer.
     */
    public boolean getConnectionPriorityLowPower() {
        return getBoolean("connection_priority_low_power", true);
    }

    /**
     * Force the older GATT connection path.
     *
     * <p>False. This exists upstream for controllers that misbehave with the
     * modern API; it is a compatibility escape hatch, not a default, and
     * turning it on unconditionally would cost throughput on every device.
     */
    public boolean getConnectionForceLegacyGatt() {
        return getBoolean("connection_force_legacy_gatt", false);
    }
}
