// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.util;

import android.content.BroadcastReceiver;
import android.content.Context;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

/**
 * Shim for Gadgetbridge's {@code AndroidUtils}.
 *
 * <p>Upstream's version has thirteen public methods; exactly one is reachable
 * from the code Tracks vendors — {@link #safeUnregisterBroadcastReceiver},
 * called twice from the BLE bonding path. The other twelve are theming, file
 * sharing and toasts, and between them they accounted for more resource
 * references than everything outside {@code ActivityKind} put together,
 * dragging in Material3 and androidx.cardview for colours Tracks never draws.
 *
 * <p>So this is a deletion wearing a shim's clothes. If a future pull makes one
 * of the other methods reachable, the compiler will say so.
 */
public final class AndroidUtils {
    private AndroidUtils() {}

    /**
     * Unregister a receiver that may already be unregistered.
     *
     * <p>Android throws {@link IllegalArgumentException} rather than no-opping
     * when a receiver was never registered, and the bonding path genuinely
     * cannot always know: a bond can complete, fail, or be cancelled by the
     * system, and the cleanup runs either way.
     */
    public static boolean safeUnregisterBroadcastReceiver(final Context context,
                                                          final BroadcastReceiver receiver) {
        if (context == null || receiver == null) {
            return false;
        }
        try {
            context.unregisterReceiver(receiver);
            return true;
        } catch (final IllegalArgumentException ignored) {
            // Already unregistered — the outcome the caller wanted.
            return false;
        }
    }

    public static boolean safeUnregisterBroadcastReceiver(final LocalBroadcastManager manager,
                                                          final BroadcastReceiver receiver) {
        if (manager == null || receiver == null) {
            return false;
        }
        try {
            manager.unregisterReceiver(receiver);
            return true;
        } catch (final IllegalArgumentException ignored) {
            return false;
        }
    }
}
