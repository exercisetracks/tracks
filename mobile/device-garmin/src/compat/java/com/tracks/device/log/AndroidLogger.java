// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.log;

import android.util.Log;

import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;

/**
 * Routes one SLF4J logger to Android's log.
 *
 * <p>Extends {@code LegacyAbstractLogger} so SLF4J's own helpers do the tedious
 * half — the twelve overloads per level, the {@code {}} placeholder formatting,
 * pulling a trailing {@code Throwable} out of the varargs. All that is left is
 * deciding a tag and calling {@code android.util.Log}.
 */
final class AndroidLogger extends LegacyAbstractLogger {

    /**
     * Android truncated log tags at 23 characters for years, and plenty of
     * tooling still does. The vendored loggers are named after fully-qualified
     * classes — {@code nodomain.freeyourgadget.gadgetbridge.service.devices.
     * garmin.communicator.v2.CommunicatorV2} — so the tag has to be shortened,
     * and shortening from the *right* keeps the part that identifies the class.
     */
    private static final int MAX_TAG_LENGTH = 23;

    private final String tag;

    AndroidLogger(final String name) {
        this.name = name;
        this.tag = shorten(name);
    }

    private static String shorten(final String name) {
        final String simple = name.substring(name.lastIndexOf('.') + 1);
        if (simple.length() <= MAX_TAG_LENGTH) {
            return simple;
        }
        return simple.substring(simple.length() - MAX_TAG_LENGTH);
    }

    /**
     * Verbose and debug are gated behind {@code Log.isLoggable}, so a release
     * build is quiet by default while {@code adb shell setprop log.tag.X DEBUG}
     * turns a single class back on. Info and above always pass: at that level
     * the vendored code is reporting something that went wrong, and losing it
     * would defeat the point of wiring this up at all.
     */
    @Override
    public boolean isTraceEnabled() {
        return Log.isLoggable(tag, Log.VERBOSE);
    }

    @Override
    public boolean isDebugEnabled() {
        return Log.isLoggable(tag, Log.DEBUG);
    }

    @Override
    public boolean isInfoEnabled() {
        return true;
    }

    @Override
    public boolean isWarnEnabled() {
        return true;
    }

    @Override
    public boolean isErrorEnabled() {
        return true;
    }

    @Override
    protected String getFullyQualifiedCallerName() {
        // Only used for location-aware logging, which android.util.Log has no
        // concept of. Null is the documented "not supported" answer.
        return null;
    }

    @Override
    protected void handleNormalizedLoggingCall(final Level level, final Marker marker,
                                               final String messagePattern, final Object[] arguments,
                                               final Throwable throwable) {
        final String message = arguments == null
                ? messagePattern
                : MessageFormatter.basicArrayFormat(messagePattern, arguments);

        final String full = throwable == null
                ? message
                : message + '\n' + Log.getStackTraceString(throwable);

        switch (level) {
            case TRACE:
                Log.v(tag, full);
                break;
            case DEBUG:
                Log.d(tag, full);
                break;
            case INFO:
                Log.i(tag, full);
                break;
            case WARN:
                Log.w(tag, full);
                break;
            case ERROR:
            default:
                Log.e(tag, full);
                break;
        }
    }
}
