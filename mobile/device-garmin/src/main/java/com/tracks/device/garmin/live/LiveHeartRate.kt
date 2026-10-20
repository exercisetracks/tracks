// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.garmin.live

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The watch's heart rate right now, while the Health page has asked for it.
 *
 * Fed by the watch's realtime HR stream (vendor patch 0016), which the page
 * switches on when it opens and off when it closes. Kept in memory only and
 * never sent anywhere: the Health page's history comes from the watch's own
 * all-day monitoring files, and a live reading is only ever "now".
 */
object LiveHeartRate {

    /** A reading and when it arrived (elapsed-realtime millis). */
    data class Reading(val bpm: Int, val at: Long)

    private val _reading = MutableStateFlow<Reading?>(null)
    val reading: StateFlow<Reading?> = _reading.asStateFlow()

    /** The last few minutes of readings, for the live part of the dial's chart. */
    private val _recent = MutableStateFlow<List<Reading>>(emptyList())
    val recent: StateFlow<List<Reading>> = _recent.asStateFlow()

    @Volatile
    var now: () -> Long = { android.os.SystemClock.elapsedRealtime() }

    /** How long a live reading stays "now" with nothing newer. */
    const val STALE_MS = 15_000L
    private val VALID = 25..240
    private const val KEEP_MS = 10 * 60 * 1000L

    /**
     * A reading from the stream. Off the wrist the watch keeps streaming, but
     * zeros (and on some firmware 255): those are "no reading", and they end
     * the current one at once rather than showing a heart rate of 0.
     */
    fun onReading(bpm: Int) = synchronized(this) {
        if (bpm !in VALID) {
            _reading.value = null
            return@synchronized
        }
        val r = Reading(bpm, now())
        _reading.value = r
        _recent.value = (_recent.value + r).filter { r.at - it.at <= KEEP_MS }
    }

    fun clear() = synchronized(this) {
        _reading.value = null
        _recent.value = emptyList()
    }

    /** The reading, or null once it is older than [STALE_MS]. */
    fun current(): Reading? = _reading.value?.takeIf { now() - it.at <= STALE_MS }
}
