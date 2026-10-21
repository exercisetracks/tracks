// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.util;

/**
 * Shim for Gadgetbridge's RtlUtils, which the vendored notification and call
 * specs call since 0.94. Upstream reorders right-to-left text for watches
 * that cannot, when a setting is on; it is off by default, and Tracks has no
 * such setting. So the fix never applies and text is left as it is.
 */
public final class RtlUtils {
    private RtlUtils() {}

    public static boolean rtlSupport() {
        return false;
    }

    public static String fixRtl(final String text) {
        return text;
    }
}
