// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.workout

import com.tracks.core.api.PlannedWorkout
import java.util.concurrent.atomic.AtomicInteger

/**
 * Workouts started by hand -- the dashboard's "Start activity" -- which are
 * not on any plan.
 *
 * They run through the same guided screen as a planned workout, which reads a
 * workout by id; these live here, in memory, under negative ids so they can
 * never collide with a planned one and are never written to the plan. What
 * they leave behind is what they record: an activity, or a logged session.
 */
object AdHocWorkouts {
    private val next = AtomicInteger(-1)
    private val workouts = java.util.concurrent.ConcurrentHashMap<Int, PlannedWorkout>()

    /** Keep [workout] and return the id the guided screen opens it by. */
    fun add(workout: PlannedWorkout): Int {
        val id = next.getAndDecrement()
        workouts[id] = workout.copy(id = id)
        return id
    }

    fun get(id: Int): PlannedWorkout? = workouts[id]

    fun isAdHoc(id: Int): Boolean = id < 0
}
