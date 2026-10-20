// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.health

import com.tracks.core.api.DailyMetricFull
import org.junit.Assert.assertEquals
import org.junit.Test

class WeeklyIntensityTest {

    /** The goal is weekly: the dial reads the seven days ending each day, vigorous counting twice (as the web). */
    @Test
    fun `intensity is a rolling week with vigorous minutes doubled`() {
        val state = HealthUiState(
            days = listOf(
                DailyMetricFull("2026-03-01", intensityModerateMin = 30, intensityVigorousMin = 0),
                DailyMetricFull("2026-03-05", intensityModerateMin = 10, intensityVigorousMin = 20),
                DailyMetricFull("2026-03-09", intensityModerateMin = 5),
            ),
        )
        assertEquals(listOf(30.0, 30.0, 30.0, 30.0, 80.0, 80.0, 80.0, 50.0, 55.0), state.weeklyIntensity.values)
    }

    /** The chart had gaps: a day with no intensity minutes is zero, not missing. */
    @Test
    fun `a quiet day is a zero, not a gap`() {
        val state = HealthUiState(
            days = listOf(
                DailyMetricFull("2026-03-01", intensityModerateMin = 70),
                DailyMetricFull("2026-03-08"),
                DailyMetricFull("2026-03-09"),
            ),
        )
        assertEquals(9, state.weeklyIntensity.dates.size)
        assertEquals(listOf(0.0, 0.0), state.weeklyIntensity.values.takeLast(2))
    }
}
