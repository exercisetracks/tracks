// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.util.gpx;

import java.nio.charset.StandardCharsets;

/**
 * Shim for Gadgetbridge's GPX parser — magic numbers only.
 *
 * <p>Only these two constants cross the boundary, and only for sniffing:
 * {@code FileUtils} uses them to decide whether a byte array looks like GPX.
 * They are byte arrays rather than strings because the check runs against raw
 * file bytes before any encoding is known, and both UTF-8 and UTF-16 forms have
 * to be recognised.
 *
 * <p>Tracks does not parse GPX on the phone: routes come from its own server,
 * which already holds them as courses.
 */
public final class GpxParser {
    private GpxParser() {}

    public static final byte[][] XML_HEADER = {
            "<?xml".getBytes(StandardCharsets.UTF_8),
            "<?xml".getBytes(StandardCharsets.UTF_16LE),
            "<?xml".getBytes(StandardCharsets.UTF_16BE),
    };

    public static final byte[][] GPX_START = {
            "<gpx".getBytes(StandardCharsets.UTF_8),
            "<gpx".getBytes(StandardCharsets.UTF_16LE),
            "<gpx".getBytes(StandardCharsets.UTF_16BE),
    };
}
