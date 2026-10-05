// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.activities.devicesettings;

/**
 * Shim for Gadgetbridge's device-settings preference keys.
 *
 * <p>Upstream this is a several-hundred-entry catalogue backing generated
 * settings screens. Tracks has no such screens, and only three keys survive the
 * vendoring boundary. Keys keep upstream's exact strings: they name entries in
 * a SharedPreferences file, so changing them would silently orphan any value
 * already stored.
 */
public final class DeviceSettingsPreferenceConst {
    private DeviceSettingsPreferenceConst() {}

    public static final String PREF_SYNC_CALENDAR = "pref_sync_calendar";
    public static final String PREF_WORKOUT_SEND_GPS_TO_BAND = "workout_send_gps_to_band";
    public static final String PREFS_DEVICE_GATT_SYNCHRONOUS_WRITES = "pref_device_gatt_synchronous_writes";
}
