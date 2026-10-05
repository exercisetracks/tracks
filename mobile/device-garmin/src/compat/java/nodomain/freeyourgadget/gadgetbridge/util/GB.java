// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.util;

import android.content.Context;
import android.util.Log;

import nodomain.freeyourgadget.gadgetbridge.R;

/**
 * Shim for Gadgetbridge's GB utility class.
 *
 * Upstream this is 707 lines of toasts, notification channels, and
 * GBApplication access — a UI layer wearing a utility class's name. The
 * vendored protocol code calls a small part of it, almost all logging.
 *
 * Toasts and notifications are deliberately no-ops. A protocol layer has no
 * business raising UI, and on Tracks these paths run inside a WorkManager job
 * or a background service where a toast would appear with no context and no
 * way to act on it. Errors surface through the DeviceIntegration API instead,
 * where the app can decide what the user should see.
 */
public class GB {

    private static final String TAG = "GB";

    public static final int INFO = 1;
    public static final int WARN = 2;
    public static final int ERROR = 3;

    public static void log(String message, int severity, Throwable ex) {
        switch (severity) {
            case ERROR:
                Log.e(TAG, message, ex);
                break;
            case WARN:
                Log.w(TAG, message, ex);
                break;
            default:
                Log.i(TAG, message, ex);
        }
    }

    public static void toast(String message, int displayTime, int severity) {
        // See the class note: no UI from the protocol layer.
        log(message, severity, null);
    }

    public static void toast(Context context, String message, int displayTime, int severity) {
        log(message, severity, null);
    }

    public static void toast(Context context, String message, int displayTime, int severity,
                             Throwable ex) {
        log(message, severity, ex);
    }

    public static void toast(String message, int displayTime, int severity, Throwable ex) {
        log(message, severity, ex);
    }

    /** Upstream renders a progress notification; Tracks reports progress in-app. */
    public static void updateTransferNotification(String title, String text, boolean ongoing,
                                                  int percentage, Context context) {
        Log.d(TAG, "transfer: " + title + " " + percentage + "%");
    }

    public static void removeAllNotifications(Context context) { }

    /**
     * Upstream posts a notification. A no-op: see the class note — a protocol
     * layer running inside a background job should not be raising UI.
     */
    public static void notify(int id, android.app.Notification notification, Context context) { }

    // Broadcast actions and extras for Gadgetbridge's firmware-install progress
    // UI. Vendored code names them when reporting transfer progress. Tracks has
    // no such screen — progress reaches the app through
    // GarminSupport.DownloadProgressListener — but the constants must exist,
    // and they keep upstream's values so that a future pull of the code that
    // sends them stays consistent.
    public static final String ACTION_SET_PROGRESS_BAR =
            "nodomain.freeyourgadget.gadgetbridge.display_message";
    public static final String ACTION_SET_PROGRESS_TEXT =
            "nodomain.freeyourgadget.gadgetbridge.set_progress_text";
    public static final String ACTION_SET_INFO_TEXT =
            "nodomain.freeyourgadget.gadgetbridge.set_info_text";
    public static final String ACTION_SET_FINISHED =
            "nodomain.freeyourgadget.gadgetbridge.set_finished";
    public static final String DISPLAY_MESSAGE_MESSAGE = "message";
    public static final String PROGRESS_BAR_PROGRESS = "progress";
    public static final String PROGRESS_BAR_INDETERMINATE = "indeterminate";
    public static final int NOTIFICATION_ID_PHONE_FIND = 8;
    public static final String NOTIFICATION_CHANNEL_HIGH_PRIORITY_ID = "gadgetbridge_high_priority";

    /**
     * Told how a file upload to the watch is going.
     *
     * <p>This exists because {@link #updateInstallNotification} is the *only*
     * place the vendored upload state machine reports its outcome —
     * {@code FileTransferHandler.updateUploadProgress} raises a notification and
     * a few local broadcasts, and returns nothing to anyone. Upstream needs no
     * more than that, because a human is watching the notification.
     *
     * <p>Tracks needs the outcome as data: a push that failed must not be
     * reported to the server as delivered, or the server stops offering the
     * file and the workout silently never reaches the watch. Rather than patch
     * a vendored file to add a callback, the shim that already receives the call
     * forwards it.
     */
    public enum InstallOutcome {
        IN_PROGRESS,
        SUCCESS,
        FAILURE,
        /** Reported, but not in a shape this shim recognises. Treated as neither. */
        UNKNOWN,
    }

    public interface InstallProgressListener {
        void onInstallProgress(InstallOutcome outcome, int percentage);
    }

    private static volatile InstallProgressListener installProgressListener;

    public static void setInstallProgressListener(InstallProgressListener listener) {
        installProgressListener = listener;
    }

    /**
     * Work out what a progress call actually means.
     *
     * <p>Success and failure are indistinguishable by the numbers — both arrive
     * as "not ongoing, 100%" — so the only discriminator is the text. Comparing
     * it against the same string resources the caller used to build it is exact
     * rather than a guess at wording, and doing that comparison here keeps the
     * generated {@code R} class a concern of the vendored namespace rather than
     * something Tracks code has to import.
     */
    private static InstallOutcome classifyInstall(String text, boolean ongoing, Context context) {
        if (ongoing) {
            return InstallOutcome.IN_PROGRESS;
        }
        if (context == null || text == null) {
            return InstallOutcome.UNKNOWN;
        }
        if (text.equals(context.getString(R.string.installation_successful))) {
            return InstallOutcome.SUCCESS;
        }
        if (text.equals(context.getString(R.string.installation_failed_))) {
            return InstallOutcome.FAILURE;
        }
        return InstallOutcome.UNKNOWN;
    }

    /**
     * Upstream posts or updates the "installing firmware" notification.
     *
     * <p>No notification here — Tracks pushes workouts and courses, not
     * firmware, and a notification the user cannot act on during a background
     * sync is noise. Progress that matters is surfaced by the foreground service
     * that Android requires during an active transfer anyway. What this does do
     * is hand the call to {@link InstallProgressListener}, which is the seam
     * that lets the Tracks side learn whether a push worked.
     */
    public static void updateInstallNotification(String text, boolean ongoing, int percentage,
                                                 Context context) {
        log("install: " + text + " (" + percentage + "%)", INFO, null);
        final InstallProgressListener listener = installProgressListener;
        if (listener != null) {
            try {
                listener.onInstallProgress(classifyInstall(text, ongoing, context), percentage);
            } catch (final Exception e) {
                log("install progress listener failed", ERROR, e);
            }
        }
    }

    public static String hexdump(byte[] buffer, int offset, int length) {
        if (buffer == null) return "";
        int end = length == -1 ? buffer.length : Math.min(offset + length, buffer.length);
        StringBuilder sb = new StringBuilder();
        for (int i = offset; i < end; i++) {
            sb.append(String.format("%02x", buffer[i]));
        }
        return sb.toString();
    }

    public static String hexdump(byte[] buffer) {
        return hexdump(buffer, 0, -1);
    }
}
