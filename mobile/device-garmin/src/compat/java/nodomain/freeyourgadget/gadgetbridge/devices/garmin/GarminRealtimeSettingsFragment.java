// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.devices.garmin;

/**
 * Shim for Gadgetbridge's live watch-settings screen.
 *
 * <p>Upstream this renders a settings UI defined by the watch itself, sent over
 * protobuf. Tracks does not expose watch settings — they are configured on the
 * watch — so only the broadcast constants the protocol handler names survive,
 * keeping upstream's values.
 */
public final class GarminRealtimeSettingsFragment {
    private GarminRealtimeSettingsFragment() {}

    public static final String ACTION_SCREEN_DEFINITION =
            "nodomain.freeyourgadget.gadgetbridge.devices.garmin.realtime_settings.screen_definition";
    public static final String ACTION_SCREEN_STATE =
            "nodomain.freeyourgadget.gadgetbridge.devices.garmin.realtime_settings.screen_state";
    public static final String ACTION_CHANGE =
            "nodomain.freeyourgadget.gadgetbridge.devices.garmin.realtime_settings.change";
    public static final String EXTRA_PROTOBUF = "protobuf";
}
