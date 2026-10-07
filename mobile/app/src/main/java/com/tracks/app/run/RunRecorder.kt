// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.run

import android.os.SystemClock
import com.tracks.core.run.RunFix
import com.tracks.core.run.RunTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RunPhase { Idle, Recording, Paused, Finished }

data class RunUiState(
    val phase: RunPhase = RunPhase.Idle,
    val distanceM: Double = 0.0,
    val ascentM: Double = 0.0,
    val descentM: Double = 0.0,
    /** Wall time since the start button, including pauses. */
    val elapsedMs: Long = 0,
    /** Time the clock was actually running. */
    val movingMs: Long = 0,
    /**
     * Moving time that counted toward the distance — moving time less a guided
     * run's walking warm-up and cool-down ([RunRecorder.setCounting]). What
     * average pace is computed against, so leaving the walk's distance out does
     * not leave its five minutes in and make the pace slower than it was.
     */
    val runningMs: Long = 0,
    val speedMps: Double = 0.0,
    val fixCount: Int = 0,
    /** Metres of uncertainty on the last fix; null before the first one. */
    val accuracyM: Double? = null,
    /** The last fix — where a guided race is on its course (RaceGuide.CourseProgress). */
    val lat: Double? = null,
    val lng: Double? = null,
    /** (kilometre, elapsed ms) for every split so far. */
    val splits: List<Pair<Int, Long>> = emptyList(),
    val uploading: Boolean = false,
    val uploaded: Boolean = false,
    val error: String? = null,
)

/**
 * The run itself, held for the life of the process.
 *
 * ## Why a singleton rather than a ViewModel
 *
 * A run outlives every screen it might be shown on. The user starts one, locks
 * the phone, puts it in a pocket for forty minutes, opens the map halfway to
 * check a junction, and comes back to stop it. A ViewModel scoped to a
 * navigation entry dies on the first of those, and one scoped to the Activity
 * dies on the last. The service is what keeps the process alive; this is what
 * the service writes into and every screen reads from.
 *
 * ## Two clocks, and neither is the wall clock
 *
 * [RunUiState.elapsedMs] counts from the start button and includes pauses;
 * [RunUiState.movingMs] excludes them. Both come from `elapsedRealtime`, which
 * counts monotonically from boot and keeps counting in deep sleep. The system
 * clock is not usable for this: it steps when the network corrects it, and a run
 * that crosses a time-zone boundary would gain or lose an hour.
 *
 * Pace is computed against moving time, because that is what pace means. Total
 * time is the honest headline, so both are shown.
 */
object RunRecorder {

    private val _state = MutableStateFlow(RunUiState())
    val state: StateFlow<RunUiState> = _state.asStateFlow()

    private var track = RunTrack()
    private var startedAtMs = 0L
    private var startRealtime = 0L
    private var pausedAtRealtime: Long? = null
    private var pausedTotalMs = 0L
    private var announcedSplits = 0
    /** Moving time spent not counting ([setCounting]), and when the current stretch began. */
    private var excludedMs = 0L
    private var notCountingSinceMoving: Long? = null

    /** Set by the service so the recorder can speak without knowing what a Context is. */
    var cues: RunCues? = null

    val fixes: List<RunFix> get() = track.fixes
    val isActive: Boolean
        get() = _state.value.phase == RunPhase.Recording || _state.value.phase == RunPhase.Paused

    fun start() {
        track = RunTrack()
        startedAtMs = System.currentTimeMillis()
        startRealtime = SystemClock.elapsedRealtime()
        pausedAtRealtime = null
        pausedTotalMs = 0
        announcedSplits = 0
        excludedMs = 0
        notCountingSinceMoving = null
        _state.value = RunUiState(phase = RunPhase.Recording)
    }

    fun pause() {
        if (_state.value.phase != RunPhase.Recording) return
        pausedAtRealtime = SystemClock.elapsedRealtime()
        _state.update { it.copy(phase = RunPhase.Paused) }
    }

    fun resume() {
        if (_state.value.phase != RunPhase.Paused) return
        pausedAtRealtime?.let { pausedTotalMs += SystemClock.elapsedRealtime() - it }
        pausedAtRealtime = null
        _state.update { it.copy(phase = RunPhase.Recording) }
    }

    fun finish() {
        if (!isActive) return
        // A run stopped while paused has already stopped accumulating moving
        // time; closing the pause here keeps total and moving consistent.
        pausedAtRealtime?.let { pausedTotalMs += SystemClock.elapsedRealtime() - it }
        pausedAtRealtime = null
        _state.update { it.copy(phase = RunPhase.Finished) }
        tick()
    }

    /**
     * Whether what happens now counts toward the run's distance, splits and
     * pace — off for a guided run's walking warm-up and cool-down (the walk is
     * still on the map; see [RunTrack.counting]).
     */
    fun setCounting(value: Boolean) {
        if (track.counting == value) return
        track.counting = value
        val now = movingMs()
        if (!value) {
            notCountingSinceMoving = now
        } else {
            notCountingSinceMoving?.let { excludedMs += now - it }
            notCountingSinceMoving = null
        }
        tick()
    }

    /** The counted distance at each fix, for the FIT record stream. */
    fun cumulativeM(): List<Double> = track.cumulativeM()

    /** Back to nothing, once the finished run has been dealt with. */
    fun reset() {
        track = RunTrack()
        _state.value = RunUiState()
    }

    /**
     * A fix from the service.
     *
     * Dropped outright while paused: a phone sitting on a bench at a water stop
     * still reports positions, and folding them in would add the walk to the tap
     * and back to a run that was not happening.
     */
    fun onLocation(
        timestampMs: Long,
        lat: Double,
        lng: Double,
        altitudeM: Double?,
        accuracyM: Double?,
        speedMps: Double?,
    ) {
        if (_state.value.phase != RunPhase.Recording) return
        track.add(
            RunFix(
                timestampMs = timestampMs,
                elapsedMs = movingMs(),
                lat = lat,
                lng = lng,
                altitudeM = altitudeM,
                accuracyM = accuracyM,
                speedMps = speedMps,
            )
        )
        _state.update { it.copy(accuracyM = accuracyM, lat = lat, lng = lng) }
        tick()
        announceNewSplits()
    }

    /**
     * Recompute the derived numbers.
     *
     * Called on each fix and once a second by the service, because the clock has
     * to move on a screen even when the GPS has nothing new to say — a display
     * that freezes between fixes reads as a crashed app.
     */
    fun tick() {
        val splits = track.splits()
        _state.update {
            it.copy(
                distanceM = track.distanceM,
                ascentM = track.ascentM,
                descentM = track.descentM,
                elapsedMs = elapsedMs(),
                movingMs = movingMs(),
                runningMs = runningMs(),
                speedMps = track.currentSpeedMps(),
                fixCount = track.fixes.size,
                splits = splits,
            )
        }
    }

    /**
     * Say each kilometre as it is crossed.
     *
     * Only ever forward, and only ever once: [announcedSplits] is a high-water
     * mark rather than a comparison against the list length, so a recomputed
     * split list — which the interpolation can shift slightly — cannot make the
     * phone announce the same kilometre twice.
     */
    private fun announceNewSplits() {
        val splits = _state.value.splits
        while (announcedSplits < splits.size) {
            val (km, at) = splits[announcedSplits]
            announcedSplits++
            cues?.announceSplit(km, at)
        }
    }

    private fun elapsedMs(): Long =
        if (startRealtime == 0L) 0 else SystemClock.elapsedRealtime() - startRealtime

    private fun movingMs(): Long {
        val paused = pausedTotalMs + (pausedAtRealtime?.let { SystemClock.elapsedRealtime() - it } ?: 0)
        return (elapsedMs() - paused).coerceAtLeast(0)
    }

    private fun runningMs(): Long {
        val moving = movingMs()
        val open = notCountingSinceMoving?.let { moving - it } ?: 0
        return (moving - excludedMs - open).coerceAtLeast(0)
    }

    fun startedAt(): Long = startedAtMs

    fun setUploading(value: Boolean) = _state.update { it.copy(uploading = value, error = null) }

    fun setUploaded() = _state.update { it.copy(uploading = false, uploaded = true, error = null) }

    fun setError(message: String) =
        _state.update { it.copy(uploading = false, error = message) }

    private inline fun MutableStateFlow<RunUiState>.update(block: (RunUiState) -> RunUiState) {
        value = block(value)
    }
}
