// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a recording says. The split was the total time at the mark -- "kilometre
 * 2, 20 minutes" at the end of two 10-minute kilometres -- which a runner hears
 * as a 20-minute kilometre.
 */
class SpokenCuesTest {

    private val run = SpokenCues.sport("running")

    @Test
    fun a_split_says_that_kilometres_pace_not_the_time_so_far() {
        assertEquals("Kilometre 2. Pace 10 minutes.", SpokenCues.split(run, 2, 600_000, imperial = false))
    }

    @Test
    fun an_imperial_runner_hears_miles() {
        assertEquals("Mile 1. Pace 9 minutes 30 seconds.", SpokenCues.split(run, 1, 570_000, imperial = true))
        assertEquals(1609.344, SpokenCues.splitMetres(run, imperial = true), 1e-9)
    }

    @Test
    fun a_ride_says_its_speed_every_five_kilometres() {
        val ride = SpokenCues.sport("cycling")
        assertEquals(5000.0, SpokenCues.splitMetres(ride, imperial = false), 1e-9)
        // 5 km in 10 minutes is 30 km/h.
        assertEquals("Kilometre 10. Average 30 kilometres per hour.", SpokenCues.split(ride, 2, 600_000, imperial = false))
    }

    @Test
    fun a_row_says_its_time_per_five_hundred_metres() {
        val row = SpokenCues.sport("rowing")
        assertEquals("Kilometre 3. 2 minutes per 500 metres.", SpokenCues.split(row, 3, 240_000, imperial = false))
    }

    @Test
    fun the_start_names_the_sport_and_the_first_stage() {
        assertEquals("Starting run. Walk for 5 minutes.", SpokenCues.start(run, "Walk for 5 minutes"))
        assertEquals("Starting ride.", SpokenCues.start(SpokenCues.sport("cycling"), null))
    }

    @Test
    fun the_finish_thanks_the_first_name_in_varied_words() {
        assertEquals("Run complete! Good work, Hawk.", SpokenCues.finish(run, "Hawk Fugagli", 0))
        val lines = (0 until SpokenCues.PRAISE.size).map { SpokenCues.finish(run, "Hawk", it) }.toSet()
        assertTrue("every praise is used", lines.size == SpokenCues.PRAISE.size)
        assertEquals("Run complete! Great job.", SpokenCues.finish(run, "  ", 1))
    }
}
