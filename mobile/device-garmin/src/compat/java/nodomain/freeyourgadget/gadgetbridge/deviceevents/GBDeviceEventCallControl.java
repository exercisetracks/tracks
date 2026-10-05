// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.deviceevents;

import android.content.Context;

import androidx.annotation.NonNull;

import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

/**
 * The watch asked to do something about an incoming call.
 *
 * <p>Carried outward as an event. Tracks does not act on it yet — accepting or
 * rejecting a call needs telephony permissions the app does not request, and
 * asking for them to support a button most users never press is a poor trade.
 * The event still crosses the boundary so that stays a decision rather than a
 * silent drop.
 */
public class GBDeviceEventCallControl extends GBDeviceEvent {
    public enum Event {
        UNKNOWN,
        ACCEPT,
        END,
        REJECT,
        START,
        IGNORE,
    }

    public Event event = Event.UNKNOWN;

    @Override
    public String toString() {
        return "call control: " + event;
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
