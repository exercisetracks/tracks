// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.workout

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The workout in progress, if there is one, for the whole app to see.
 *
 * ## Why the session outlives its screen
 *
 * The guided workout's ViewModel used to belong to its navigation entry, so
 * pressing Back mid-run destroyed it. The recording carried on in [com.tracks.app.run.RunRecorder]
 * — that singleton exists precisely so a run survives the screen — but the
 * step cursor did not. Reopening the workout built a fresh ViewModel, which saw
 * a run already recording, "began" it, and restarted the plan at the warm-up
 * forty minutes in.
 *
 * So a session's ViewModel is held by the Activity instead, under a key minted
 * here ([keyFor]), and this object remembers which key is live. Opening the
 * workout that is in progress finds the same key and the same ViewModel; opening
 * it again after it was saved or deleted mints a new key, so a finished session
 * is never shown as the start of the next one.
 *
 * It is also what the shell's "back to your workout" bar reads — a bar that
 * needs to know a workout is running from screens that know nothing about
 * workouts.
 */
object ActiveWorkout {

    data class Session(
        val workoutId: Int,
        val title: String,
        /** The ViewModel's key in the Activity's store. */
        val key: String,
    )

    private val _current = MutableStateFlow<Session?>(null)
    val current: StateFlow<Session?> = _current.asStateFlow()

    private var serial = 0

    /**
     * The ViewModel key for opening [workoutId]: the live session's, when this
     * is the workout in progress, or a new one.
     */
    fun keyFor(workoutId: Int): String =
        _current.value?.takeIf { it.workoutId == workoutId }?.key ?: "workout-$workoutId-${++serial}"

    /** The session has started its first step. */
    fun begin(session: Session) {
        _current.value = session
    }

    /**
     * The session is over — saved, logged or deleted. Only the session that is
     * live can end it, so a stale screen finishing late cannot clear another.
     */
    fun end(key: String) {
        if (_current.value?.key == key) _current.value = null
    }
}
