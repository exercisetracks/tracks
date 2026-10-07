// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.spec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** The web's lib/workoutColors.test.js, case for case. */
class WorkoutColorsTest {
    @Test
    fun a_long_run_has_a_colour_of_its_own_not_grey() {
        assertEquals("blue", WorkoutColors.hue("long", "running"))
        assertNotEquals(WorkoutColors.level("easy"), WorkoutColors.level("long"))
    }

    @Test
    fun stretching_strength_runs_and_rides_are_all_different_hues() {
        val hues = listOf(
            WorkoutColors.hue("flexibility", "flexibility_training"),
            WorkoutColors.hue("strength", "strength_training"),
            WorkoutColors.hue("easy", "running"),
            WorkoutColors.hue("easy_spin", "cycling"),
        )
        assertEquals(4, hues.toSet().size)
    }

    @Test
    fun a_mobility_session_is_stretching_whatever_its_sport() {
        assertEquals("stretching", WorkoutColors.family("mobility", "running"))
    }

    @Test
    fun an_unheard_of_type_is_the_middle_shade_of_its_family_and_a_race_has_no_hue() {
        assertEquals(2, WorkoutColors.level("something_new"))
        assertEquals("cyan", WorkoutColors.hue("something_new", "swimming"))
        assertNull(WorkoutColors.hue("race", "running"))
    }
}
