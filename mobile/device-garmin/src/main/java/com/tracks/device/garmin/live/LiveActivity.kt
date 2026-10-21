// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.garmin.live

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the watch is recording right now, as far as the phone can see it.
 *
 * Two sources feed it, both measured against Garmin Explore
 * (docs/garmin-explore-sync.md, "The live activity"):
 *
 *  - the watch's own position reports (core service message 7), about one a
 *    second while an activity records, with nothing asked of the phone first;
 *  - the in-progress line every Explore session opens with, whose points are
 *    the track so far. That is what fills the line in when the phone was not
 *    listening for the first part of the activity.
 *
 * Plus the watch's answer to "is an activity running?" (device status
 * service), which is how a sync knows not to treat the watch's refusals during
 * a recording as a fault.
 *
 * GB-free and process-wide on purpose: the protocol layer writes, the map
 * reads, and nothing between them has to be threaded through. Phone only --
 * nothing here is sent to the server.
 */
object LiveActivity {

    enum class Status { UNKNOWN, OFF, STOPPED, PAUSED, RECORDING }

    data class Point(val lat: Double, val lng: Double, val altitude: Double?, val epochSeconds: Long?)

    data class Track(
        val status: Status = Status.UNKNOWN,
        /** The track so far, oldest first. */
        val points: List<Point> = emptyList(),
        /** When anything last arrived, elapsed-realtime millis; 0 before. */
        val updatedAt: Long = 0L,
    ) {
        /** Whether there is a live track worth drawing. */
        val live: Boolean get() = points.isNotEmpty() && status != Status.OFF && status != Status.STOPPED
        val position: Point? get() = points.lastOrNull()

        /**
         * Where the watch is, for the map's location dot: the last position
         * while the activity is live and something arrived within [STALE_MS]
         * of [now], else null and the dot goes back to the phone's own fix.
         */
        fun fix(now: Long): Point? = position?.takeIf { live && now - updatedAt <= STALE_MS }
    }

    private val _track = MutableStateFlow(Track())
    val track: StateFlow<Track> = _track.asStateFlow()

    /** Whether the watch last said it is recording or paused. */
    val recording: Boolean get() = _track.value.status.let { it == Status.RECORDING || it == Status.PAUSED }

    /**
     * The clock, replaceable by tests. Elapsed time rather than wall time so a
     * clock change mid-activity cannot make the track look stale.
     */
    @Volatile
    var now: () -> Long = { android.os.SystemClock.elapsedRealtime() }

    /** Points older than this with nothing newer are a finished activity, not a live one. */
    const val STALE_MS = 2 * 60 * 1000L

    fun onStatus(status: Status) = synchronized(this) {
        val prev = _track.value
        // A new recording starts a new track: a stop then a start must not
        // join yesterday's ride onto today's.
        val fresh = status == Status.RECORDING && (prev.status == Status.OFF || prev.status == Status.STOPPED)
        _track.value = prev.copy(
            status = status,
            points = if (fresh) emptyList() else prev.points,
            updatedAt = now(),
        )
    }

    /**
     * The watch's once-a-second reports. Appended in time order; repeats are
     * dropped. Reports after a stop are a new recording, the stop having been
     * heard (onStatus) and the start not: the watch says nothing when one
     * begins, it just starts reporting.
     */
    fun onPositions(points: List<Point>) = synchronized(this) {
        if (points.isEmpty()) return
        val prev = _track.value
        val fresh = prev.status == Status.OFF || prev.status == Status.STOPPED
        val merged = if (fresh) ArrayList() else ArrayList(prev.points)
        for (p in points) {
            val last = merged.lastOrNull()
            if (last != null && last.lat == p.lat && last.lng == p.lng) continue
            if (last?.epochSeconds != null && p.epochSeconds != null && p.epochSeconds < last.epochSeconds) continue
            merged += p
        }
        // Positions only arrive while recording, whatever the last status said.
        val status = if (prev.status == Status.PAUSED) prev.status else Status.RECORDING
        _track.value = prev.copy(status = status, points = merged, updatedAt = now())
    }

    /**
     * The in-progress line read in an Explore session: the authoritative track
     * so far. It replaces what came before it and keeps any live reports newer
     * than its last point.
     */
    fun onTrackSoFar(points: List<Point>) = synchronized(this) {
        if (points.isEmpty()) return
        val prev = _track.value
        val lastTime = points.last().epochSeconds
        val newer = if (lastTime == null) emptyList() else prev.points.filter { (it.epochSeconds ?: 0) > lastTime }
        _track.value = prev.copy(
            status = if (prev.status == Status.UNKNOWN) Status.RECORDING else prev.status,
            points = points + newer,
            updatedAt = now(),
        )
    }

    /** The link went away; what the watch is doing is no longer known. */
    fun onDisconnected() = synchronized(this) {
        _track.value = _track.value.copy(status = Status.UNKNOWN)
    }

    /** For tests and for a new pairing. */
    fun reset() = synchronized(this) { _track.value = Track() }

    /** Garmin semicircles to degrees. */
    fun degrees(semicircles: Int): Double = semicircles * (180.0 / 2147483648.0)

    /** Garmin's epoch (1989-12-31) to Unix seconds. */
    fun unixSeconds(garminSeconds: Long): Long = garminSeconds + 631_065_600L
}
