// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.metrics

import kotlin.test.Test
import kotlin.test.assertEquals

class HealthGoalsTest {

    private fun days(n: Int, f: (Int) -> HealthGoals.Day) = List(n, f)

    /** A week of history is the least Auto acts on; before it, the defaults. */
    @Test
    fun `auto keeps the defaults until there are seven days`() {
        assertEquals(HealthGoals.DEFAULT, HealthGoals.auto(days(6) { HealthGoals.Day(20_000, null, null) }))
    }

    /** The goal must not climb forever: a heavy walker is capped, as the web is. */
    @Test
    fun `auto follows the median nudged up inside the band`() {
        val g = HealthGoals.auto(days(14) { HealthGoals.Day(if (it < 7) 6_000 else 30_000, 60, 40) })
        assertEquals(HealthGoals.Goals(12_000, 300, 20), g)
        assertEquals(7_500, HealthGoals.auto(days(10) { HealthGoals.Day(7_000, null, null) }).steps)
    }

    @Test
    fun `a manual goal applies only in manual mode`() {
        val auto = HealthGoals.Goals(8_000, 150, 10)
        val settings = mapOf("step_goal_mode" to "manual", "step_goal_manual" to 15_000, "floors_goal_manual" to 3)
        assertEquals(HealthGoals.Goals(15_000, 150, 10), HealthGoals.resolve(auto, settings))
    }
}
