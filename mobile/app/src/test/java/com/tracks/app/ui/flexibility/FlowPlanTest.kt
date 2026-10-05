// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.flexibility

import com.tracks.core.api.FlowStretch
import com.tracks.core.api.Stretch
import kotlin.test.Test
import kotlin.test.assertEquals

class FlowPlanTest {

    private val library = mapOf(
        "Cat Cow" to Stretch(name = "Cat Cow", durationPerSideSec = 30),
        "Low Lunge" to Stretch(name = "Low Lunge", durationPerSideSec = 40, eachSide = true),
    )

    /** The player runs holds; a repeated group is unrolled round by round (the watch file loops it). */
    @Test
    fun `a repeated group plays its members once per round with the group rest after each round`() {
        val holds = FlowPlan.holds(
            listOf(
                FlowStretch(exerciseName = "Cat Cow", groupUid = "g", groupRounds = 2, groupRestSeconds = 20),
                FlowStretch(exerciseName = "Low Lunge", groupUid = "g", groupRounds = 2, groupRestSeconds = 20),
            ),
            library,
        )
        assertEquals(listOf("Cat Cow", "Low Lunge", "Low Lunge", "Cat Cow", "Low Lunge", "Low Lunge"), holds.map { it.name })
        // Members follow at once; the round's last hold carries the group rest.
        assertEquals(listOf(0, 0, 20, 0, 0, 20), holds.map { it.restSeconds })
    }

    @Test
    fun `a rest block lengthens the rest after the hold before it`() {
        val holds = FlowPlan.holds(
            listOf(
                FlowStretch(exerciseName = "Cat Cow", restSeconds = 10),
                FlowStretch(itemKind = "rest", durationSeconds = 45),
                FlowStretch(exerciseName = "Cat Cow"),
            ),
            library,
        )
        assertEquals(55, holds.first().restSeconds)
        assertEquals(2, holds.size)
    }
}
