// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.log;

import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes the vendored Gadgetbridge code's logging visible on a device.
 *
 * <p>SLF4J is an API with no implementation. Without a provider on the classpath
 * it prints "No SLF4J providers were found" once and then silently discards
 * every call — which is exactly what happened the first time this app talked to
 * a real watch: Gadgetbridge's own logs filled logcat while ours produced not a
 * single line from a layer that is ~200 files of protocol code. Debugging BLE
 * blind is not a thing anyone should attempt.
 *
 * <p>This is written out rather than pulled in because the alternatives are
 * poor for this project. {@code logback-android} is a large dependency for a
 * file-logging feature we do not want; {@code slf4j-android} has not been
 * updated for the SLF4J 2.x service-provider mechanism. The 2.x SPI is small
 * enough that implementing it is less code than either.
 *
 * <p>Registered through {@code META-INF/services/org.slf4j.spi.SLF4JServiceProvider}.
 */
public final class AndroidSlf4jProvider implements SLF4JServiceProvider {

    /**
     * The version of the SLF4J API this was written against. SLF4J compares it
     * to its own and warns on a mismatch — which is a useful nudge if the API
     * ever moves under us, so it is stated honestly rather than pinned to
     * whatever silences the check.
     */
    private static final String REQUESTED_API_VERSION = "2.0.99";

    private final ILoggerFactory loggerFactory = new AndroidLoggerFactory();
    private final IMarkerFactory markerFactory = new BasicMarkerFactory();
    private final MDCAdapter mdcAdapter = new NOPMDCAdapter();

    @Override
    public ILoggerFactory getLoggerFactory() {
        return loggerFactory;
    }

    @Override
    public IMarkerFactory getMarkerFactory() {
        return markerFactory;
    }

    /**
     * No MDC. It is a per-thread context map for correlating requests in a
     * server; nothing in this app has anything to put in one, and Android's log
     * has nowhere to show it.
     */
    @Override
    public MDCAdapter getMDCAdapter() {
        return mdcAdapter;
    }

    @Override
    public String getRequestedApiVersion() {
        return REQUESTED_API_VERSION;
    }

    @Override
    public void initialize() {
        // Nothing to set up: android.util.Log is always available.
    }

    /**
     * One logger per name, cached.
     *
     * <p>Concurrent because loggers are requested from static initialisers all
     * over the vendored code, on whichever thread happens to touch the class
     * first — including the BLE callback thread.
     */
    private static final class AndroidLoggerFactory implements ILoggerFactory {
        private final Map<String, org.slf4j.Logger> loggers = new ConcurrentHashMap<>();

        @Override
        public org.slf4j.Logger getLogger(final String name) {
            return loggers.computeIfAbsent(name, AndroidLogger::new);
        }
    }
}
