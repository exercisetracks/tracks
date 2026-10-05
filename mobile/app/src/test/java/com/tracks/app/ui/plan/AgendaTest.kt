// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.plan

import com.tracks.core.api.PlannedWorkout
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

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

    /** The web tells strength sessions apart by title; the phone must colour them the same. */
    @Test
    fun strength_sessions_take_the_webs_colour_from_their_title() {
        assertEquals("strength_lower", toneKey("strength", "Lower Body"))
        assertEquals("strength_core", toneKey("strength", "Upper Body & Core"))
        assertEquals("strength_upper", toneKey("strength", "Upper Body"))
        assertEquals("strength_legs", toneKey("strength", "Leg Day"))
        assertEquals("strength_push", toneKey("strength", "Push Day"))
        assertEquals("strength", toneKey("strength", "Posterior Chain"))
    }

    @Test
    fun an_unknown_type_falls_back_to_the_neutral_chip() {
        assertEquals("default", toneKey("field_test", "FTP test"))
        assertEquals("tempo", toneKey("tempo", "Tempo"))
        assertEquals("mobility", toneKey("mobility", "Lower-Body Flush"))
    }
}
