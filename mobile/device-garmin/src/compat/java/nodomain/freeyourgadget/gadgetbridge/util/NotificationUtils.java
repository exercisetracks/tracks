// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.util;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

/**
 * Shim for Gadgetbridge's notification helpers.
 *
 * <p>Only one method survives the boundary: turning a package name into the
 * app label the watch should display. Tracks captures notifications with its
 * own {@code NotificationCapture}, but the vendored GFDI notification layer
 * still asks for the label when building the message.
 */
public final class NotificationUtils {
    private NotificationUtils() {}

    /**
     * Human-readable name for a package, falling back to the package name.
     *
     * <p>The fallback matters: an app can be uninstalled between the
     * notification arriving and this running, and a watch showing
     * "com.example.chat" is better than one showing nothing.
     */
    public static String getApplicationLabel(final Context context, final String packageName) {
        if (context == null || packageName == null) {
            return packageName;
        }
        try {
            final PackageManager pm = context.getPackageManager();
            final ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            return String.valueOf(pm.getApplicationLabel(info));
        } catch (final PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }
}
