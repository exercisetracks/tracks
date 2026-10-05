// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nodomain.freeyourgadget.gadgetbridge.proto.garmin.GdiAppConfigService;

/**
 * Shim for watch-app configuration.
 *
 * <p>Upstream this renders a settings screen for a Connect IQ app installed on
 * the watch, driven by a schema the watch sends. Tracks does not manage watch
 * apps — it moves workouts, courses and activity files — so there is no screen
 * to render and nothing to write back.
 */
public class AppConfigHandler {
    private static final Logger LOG = LoggerFactory.getLogger(AppConfigHandler.class);

    public AppConfigHandler(final GarminSupport deviceSupport) {
    }

    /** @return false, meaning "not handled" — Tracks manages no watch apps. */
    public boolean process(final GdiAppConfigService.AppConfigService appConfigService) {
        LOG.debug("Ignoring watch app config — Tracks does not manage watch apps");
        return false;
    }
}
