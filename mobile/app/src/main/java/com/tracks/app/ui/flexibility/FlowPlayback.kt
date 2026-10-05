// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.flexibility

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.tracks.app.run.RunCues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The flow that is playing, for the whole process.
 *
 * Not in a ViewModel, because a flow is played with the screen off and the app
 * behind the lock screen, driven from a notification: the ViewModel goes when
 * the screen does, and the notification's buttons have to reach *something*.
 * So the session, its ticker and its voice live here, the screen and
 * [FlowPlayerService]'s notification both observe [state], and both send the
 * same commands. The service exists to keep this process in the foreground
 * for the length of the flow — see its own comment.
 */
object FlowPlayback {

    private val _state = MutableStateFlow<FlowSession?>(null)
    val state: StateFlow<FlowSession?> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null
    private var cues: RunCues? = null
    private var appContext: Context? = null

    internal var now: () -> Long = System::currentTimeMillis

    fun start(context: Context, session: FlowSession) {
        appContext = context.applicationContext
        if (cues == null) cues = RunCues(context.applicationContext)
        apply(FlowEngine.start(session, now()))
        runTicker()
        ContextCompat.startForegroundService(context, Intent(context, FlowPlayerService::class.java))
    }

    fun togglePause() {
        val s = _state.value ?: return
        _state.value = if (s.running) FlowEngine.pause(s, now()) else FlowEngine.resume(s, now())
        if (_state.value?.running == true) runTicker()
    }

    fun skip() = _state.value?.let { apply(FlowEngine.skip(it, now())) }
    fun back() = _state.value?.let { apply(FlowEngine.back(it, now())); runTicker() }
    fun extend(seconds: Int) = _state.value?.let { _state.value = FlowEngine.extend(it, seconds, now()) }

    /** The screen's own edits to the session — saving, saved, an error. */
    fun update(block: (FlowSession) -> FlowSession) {
        _state.value = _state.value?.let(block)
    }

    /** Stop playing and forget the session; the notification goes with it. */
    fun close() {
        ticker?.cancel()
        _state.value = null
        appContext?.let { it.stopService(Intent(it, FlowPlayerService::class.java)) }
        cues?.release()
        cues = null
    }

    private fun apply(step: FlowEngine.Step) {
        _state.value = step.session
        step.cues.forEach { cue ->
            // A buzz for every change of phase — the phone is on the mat, the
            // speaker may be muted — and words for whoever can hear them.
            cues?.nudge()
            cues?.speak(FlowEngine.spoken(cue))
        }
        if (step.session.phase == HoldPhase.Done) ticker?.cancel()
    }

    /** A few times a second: cheap, and a phase ends within a quarter-second of its deadline. */
    private fun runTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                val s = _state.value ?: break
                if (!s.running || s.phase == HoldPhase.Done) break
                apply(FlowEngine.tick(s, now()))
                delay(TICK_MS)
            }
        }
    }

    private const val TICK_MS = 250L
}
