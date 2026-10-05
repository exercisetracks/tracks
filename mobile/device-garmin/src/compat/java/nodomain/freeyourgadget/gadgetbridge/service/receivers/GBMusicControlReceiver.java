// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.service.receivers;

/**
 * Shim for Gadgetbridge's music-control receiver.
 *
 * <p>Tracks has its own — {@code com.tracks.device.feeds.MusicMonitor}, built on
 * MediaSessionManager, which is both more accurate about what is playing and
 * cheaper than the broadcast-and-guess approach. This type exists only so the
 * vendored music event compiles; its constants keep upstream's values.
 */
public class GBMusicControlReceiver {
    public static final String ACTION_MUSICCONTROL =
            "nodomain.freeyourgadget.gadgetbridge.musiccontrol";
    public static final String EXTRA_MUSICCMD = "command";
}
