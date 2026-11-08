// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.race

import com.tracks.core.api.TrainingGoal
import com.tracks.core.local.LocalRacePlans
import com.tracks.core.race.RaceLap
import com.tracks.core.race.SportPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Race day on the Race Plans screen: the Start button, and the prediction's line. */
class RaceDayTest {

    private val goal = TrainingGoal(id = 1, goalType = "event", eventSport = "running", eventDate = "2026-11-08")
    private val lap = RaceLap(1, 1000, 300.0, "5:00/km", 0.0, 1.0, 300.0, "5:00/km", 1.0, null)
    private val running = LocalRacePlans.Prediction(3000.0, listOf(lap))

    /**
     * Shown on the race's day and no other: before, there is training to do,
     * and a button that starts the race on any day is one that gets pressed
     * by mistake mid-plan; after, the race is history.
     */
    @Test
    fun `start race shows on the day of the race and on no other`() {
        assertTrue(isRaceDay(goal, running, "2026-11-08"))
        assertFalse(isRaceDay(goal, running, "2026-11-07"))
        assertFalse(isRaceDay(goal, running, "2026-11-09"))
    }

    /** The guided race paces running legs; with no plan to pace, there is nothing to start. */
    @Test
    fun `start race needs a run with a plan to pace`() {
        assertFalse(isRaceDay(goal, null, "2026-11-08"))
        assertFalse(isRaceDay(goal, running.copy(family = "cycling"), "2026-11-08"))
        assertFalse(isRaceDay(goal, running.copy(laps = emptyList()), "2026-11-08"))
    }

    private val projection = SportPlan.Projection(
        vdotToday = 44.9, vdotRaceDay = 46.1, adherence = 0.86,
        tsbToday = -12.4, tsbRaceDay = 14.0, todaySeconds = 2870.2, todayTime = "47:50",
    )

    @Test
    fun `the line under the prediction says what today would be and what race day credits`() {
        assertEquals(
            "Today 47:50 · VDOT 44.9 → 46.1 by race day · 86% of the plan done",
            projectionLine(projection, "46:05"),
        )
    }

    @Test
    fun `on race day, or with no plan, the line has nothing to add`() {
        assertNull(projectionLine(null, "46:05"))
        assertNull(projectionLine(projection.copy(todayTime = "46:05", adherence = null), "46:05"))
    }
}
