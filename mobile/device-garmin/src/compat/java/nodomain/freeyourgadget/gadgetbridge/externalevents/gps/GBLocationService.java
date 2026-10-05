// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.externalevents.gps;

import android.content.Context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

/**
 * Shim for Gadgetbridge's phone-GPS relay — deliberately inert.
 *
 * <p>Upstream this streams the phone's location to the watch so a watch without
 * its own GPS can record a track. Tracks does the opposite by design: the watch
 * is the sensor and the phone is a transport.
 *
 * <p>This is the single largest battery decision in the project. Continuous
 * phone GPS is the most expensive thing an Android app can do short of keeping
 * the screen on, and an expedition tool that flattens the phone battery to
 * duplicate a sensor the watch already has is worse than useless — the phone is
 * also the map and the emergency communicator.
 */
public final class GBLocationService {
    private static final Logger LOG = LoggerFactory.getLogger(GBLocationService.class);

    private GBLocationService() {}

    public static void start(final Context context, final GBDevice device,
                             final GBLocationProviderType type, final int interval) {
        LOG.info("Ignoring request to start phone {} relay — the watch is the GPS source", type);
    }

    public static void stop(final Context context, final GBDevice device) {
        // Never started, so nothing to stop.
    }
}
