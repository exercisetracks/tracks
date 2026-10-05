// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge;

/**
 * Shim for Gadgetbridge's crash handler.
 *
 * <p>Upstream installs this as the default uncaught-exception handler so it can
 * write a crash log the user can attach to a bug report. Tracks is one app with
 * one crash handler, and a vendored library installing its own would silently
 * replace it. Kept as a type only.
 */
public class GBExceptionHandler implements Thread.UncaughtExceptionHandler {
    private final Thread.UncaughtExceptionHandler delegate;

    private final boolean notifyOnCrash;

    public GBExceptionHandler(final Thread.UncaughtExceptionHandler delegate) {
        this(delegate, false);
    }

    public GBExceptionHandler(final Thread.UncaughtExceptionHandler delegate,
                              final boolean notifyOnCrash) {
        this.delegate = delegate;
        this.notifyOnCrash = notifyOnCrash;
    }

    @Override
    public void uncaughtException(final Thread thread, final Throwable ex) {
        if (delegate != null) {
            delegate.uncaughtException(thread, ex);
        }
    }
}
