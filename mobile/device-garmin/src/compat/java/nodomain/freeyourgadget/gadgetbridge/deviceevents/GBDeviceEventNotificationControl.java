// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.deviceevents;

import android.content.Context;

import androidx.annotation.NonNull;

import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

/**
 * The watch acted on a notification — dismissed it, muted it, or replied.
 *
 * <p>Dismiss and mute are worth honouring: clearing a notification on the watch
 * should clear it on the phone, and Tracks' NotificationListenerService can do
 * that. Reply carries the typed or dictated text.
 */
public class GBDeviceEventNotificationControl extends GBDeviceEvent {
    public enum Event {
        UNKNOWN,
        DISMISS,
        DISMISS_ALL,
        OPEN,
        MUTE,
        REPLY,
    }

    public Event event = Event.UNKNOWN;

    /** Identifies which notification, matching the id Tracks sent to the watch. */
    public long handle;

    public String reply;

    public String phoneNumber;

    /** Set for a reply the watch composed against a specific wearable action. */
    public String title;

    @Override
    public String toString() {
        return "notification control: " + event + " handle=" + handle;
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
