// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.plan

import com.tracks.core.api.PlannedWorkout
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AgendaTest {

    private fun w(id: Int, date: String, type: String, title: String = type) =
        PlannedWorkout(id = id, scheduledDate = date, workoutType = type, title = title)

    @Test
    fun a_week_starts_on_monday_whatever_day_it_is_asked_from() {
        assertEquals(LocalDate.of(2026, 9, 21), weekStart(LocalDate.of(2026, 9, 27)))
        assertEquals(LocalDate.of(2026, 9, 21), weekStart(LocalDate.of(2026, 9, 21)))
    }

    /** Rest days stay in the week: hiding them makes a three-session week read like a seven. */
    @Test
    fun a_week_keeps_its_rest_days_and_puts_each_workout_on_its_day() {
        val days = agendaWeek(
            LocalDate.of(2026, 9, 21),
            listOf(w(1, "2026-09-22", "easy"), w(2, "2026-09-24", "tempo"), w(3, "2026-10-01", "easy")),
        )
        assertEquals(7, days.size)
        assertEquals(listOf(0, 1, 0, 1, 0, 0, 0), days.map { it.workouts.size })
    }

    @Test
    fun within_a_day_the_race_comes_first_and_mobility_last() {
        val day = agendaWeek(
            LocalDate.of(2026, 9, 21),
            listOf(
                w(1, "2026-09-21", "mobility", "Hips"),
                w(2, "2026-09-21", "strength", "Leg Day"),
                w(3, "2026-09-21", "race", "5K"),
                w(4, "2026-09-21", "easy", "Shakeout"),
            ),
        ).first()
        assertEquals(listOf(3, 4, 2, 1), day.workouts.map { it.id })
    }

    /** Long runs used to fall through to the grey chip. */
    @Test
    fun a_long_run_is_coloured_like_a_run_and_not_like_an_easy_one() {
        val surface = androidx.compose.ui.graphics.Color.White
        val easy = workoutTone("easy", "running", dark = false, surface = surface)
        val long = workoutTone("long", "running", dark = false, surface = surface)
        assertNotEquals(easy.fill, long.fill)
        assertNotEquals(workoutTone("long", "running", false, surface).fill, workoutTone("long", "cycling", false, surface).fill)
    }
}
