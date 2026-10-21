// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nodomain.freeyourgadget.gadgetbridge.proto.garmin.GdiSmartProto.PushNotificationService;

/**
 * Shim for Gadgetbridge's PushNotificationHandler (0.95), which the vendored
 * ProtocolBufferHandler routes the push-notification service to. Upstream
 * answers hydration requests from its own sample database; Tracks keeps no
 * hydration log on the phone. Declined, as the message went unhandled before
 * 0.95 too.
 */
public class PushNotificationHandler {
    private static final Logger LOG = LoggerFactory.getLogger(PushNotificationHandler.class);

    public PushNotificationHandler(final GarminSupport deviceSupport) {
    }

    public boolean handle(final PushNotificationService service) {
        LOG.info("Push notification service not handled: {}", service);
        return false;
    }
}
