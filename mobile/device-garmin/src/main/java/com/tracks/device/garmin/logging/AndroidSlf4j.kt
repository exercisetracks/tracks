// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.garmin.logging

import android.util.Log
import org.slf4j.ILoggerFactory
import org.slf4j.IMarkerFactory
import org.slf4j.Logger
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.BasicMDCAdapter
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.helpers.LegacyAbstractLogger
import org.slf4j.helpers.MessageFormatter
import org.slf4j.spi.MDCAdapter
import org.slf4j.spi.SLF4JServiceProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * Sends the vendored Garmin stack's logging to logcat.
 *
 * ## Why this exists
 *
 * Gadgetbridge's protocol code logs through SLF4J, and only `slf4j-api` was on
 * the classpath — an API with no binding discards everything. So every warning
 * the BLE stack produced went nowhere: which message the watch refused, which
 * status it refused it with, which stage of a transfer gave up. All of it was
 * being written and none of it was arriving.
 *
 * That was not a theoretical loss. A watch refusing a locations file was
 * diagnosed by reading an error string in the app's own UI, because the four
 * log lines that would have named the cause outright did not exist anywhere.
 *
 * ## Why not a library
 *
 * `slf4j-android` is a 1.7-era binding and cannot serve SLF4J 2's service
 * provider interface. The 2.x-compatible ones are third-party artifacts, and
 * this is eighty lines that adds no dependency to a project that has F-Droid
 * constraints to keep. Discovered by ServiceLoader through the descriptor in
 * `META-INF/services` — which is why the provider is kept explicitly in the
 * R8 rules, or it would be shrunk out of a release build and the logs would
 * quietly go dark again.
 */
class AndroidSlf4jProvider : SLF4JServiceProvider {

    private val loggers = ConcurrentHashMap<String, Logger>()
    private val markers = BasicMarkerFactory()
    private val mdc = BasicMDCAdapter()

    private val factory = ILoggerFactory { name ->
        loggers.getOrPut(name) { AndroidLogger(name) }
    }

    override fun getLoggerFactory(): ILoggerFactory = factory

    override fun getMarkerFactory(): IMarkerFactory = markers

    override fun getMDCAdapter(): MDCAdapter = mdc

    /** The API this was written against; SLF4J warns loudly on a mismatch. */
    override fun getRequestedApiVersion(): String = "2.0.99"

    override fun initialize() = Unit
}

/**
 * One logger, forwarding to [Log].
 *
 * Everything from DEBUG up is emitted. TRACE is not: the file-transfer code
 * traces every fragment of every file, which on a sync of twenty activities is
 * tens of thousands of lines and would push the interesting ones out of the
 * ring buffer — the exact failure this is meant to prevent.
 */
private class AndroidLogger(name: String) : LegacyAbstractLogger() {

    private val tag = tagFor(name)

    init {
        this.name = name
    }

    override fun isTraceEnabled(): Boolean = false
    override fun isDebugEnabled(): Boolean = true
    override fun isInfoEnabled(): Boolean = true
    override fun isWarnEnabled(): Boolean = true
    override fun isErrorEnabled(): Boolean = true

    override fun getFullyQualifiedCallerName(): String? = null

    override fun handleNormalizedLoggingCall(
        level: Level?,
        marker: Marker?,
        messagePattern: String?,
        arguments: Array<out Any>?,
        throwable: Throwable?,
    ) {
        // SLF4J's own `{}` substitution, so a message logged with placeholders
        // arrives rendered rather than as its template.
        val message = MessageFormatter
            .basicArrayFormat(messagePattern.orEmpty(), arguments)

        when (level) {
            Level.ERROR -> Log.e(tag, message, throwable)
            Level.WARN -> Log.w(tag, message, throwable)
            Level.INFO -> Log.i(tag, message, throwable)
            else -> Log.d(tag, message, throwable)
        }
    }

    private companion object {
        /**
         * A logcat tag from a class name.
         *
         * Prefixed so the whole vendored stack can be filtered as one — the
         * class names are Gadgetbridge's and say nothing about which app they
         * are running inside.
         */
        fun tagFor(name: String): String =
            "GB." + name.substringAfterLast('.').take(20)
    }
}
