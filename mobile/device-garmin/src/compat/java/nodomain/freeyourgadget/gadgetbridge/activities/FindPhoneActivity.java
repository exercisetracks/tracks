// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.activities;

/**
 * Shim for Gadgetbridge's find-my-phone screen.
 *
 * <p>Pressing "find phone" on the watch makes the phone ring. Tracks has no
 * such screen yet — it is a genuinely useful feature and a plausible thing to
 * add later, but it needs a UI, an audio focus policy and a way to stop the
 * ringing, none of which belong in the vendored protocol layer.
 *
 * <p>The action constants keep upstream's values so the event still carries its
 * meaning across the boundary for whoever implements it.
 */
public class FindPhoneActivity {
    public static final String ACTION_FOUND =
            "nodomain.freeyourgadget.gadgetbridge.findphone.action.found";
    public static final String ACTION_RING =
            "nodomain.freeyourgadget.gadgetbridge.findphone.action.ring";
    public static final String ACTION_VIBRATE =
            "nodomain.freeyourgadget.gadgetbridge.findphone.action.vibrate";
    public static final String EXTRA_RING = "extra_ring";
}
