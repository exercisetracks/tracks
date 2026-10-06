// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.health

import com.tracks.core.api.DailyMetricFull
import com.tracks.core.api.MealLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * One number for calories eaten.
 *
 * There used to be two that never agreed: a typed daily total that drove the
 * Eaten dial, and the meal log, which the dial ignored. Somebody who logged
 * three meals saw an empty dial. The log is the source now, and these pin down
 * how it is counted. Dates are anchored to today, so nothing here ages out.
 */
class EatenTest {

    private val today: LocalDate = LocalDate.now()

    /** Noon local time on [day], as the UTC stamp a phone writes. */
    private fun noon(day: LocalDate): String =
        day.atTime(12, 0).atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneOffset.UTC)
            .toOffsetDateTime().toString()

    private fun food(id: Int, day: LocalDate, kcal: Int) =
        MealLog(id = id, name = "food $id", calories = kcal, loggedAt = noon(day))

    private fun state(days: List<DailyMetricFull>, log: List<MealLog>) =
        HealthUiState(range = HealthRange.Month, days = days, mealLog = log)

    @Test
    fun `a day's food is added up`() {
        val eaten = state(emptyList(), listOf(food(1, today, 350), food(2, today, 520))).eaten
        assertEquals(listOf(today.toString()), eaten.dates)
        assertEquals(870.0, eaten.values.single(), 0.0)
    }

    @Test
    fun `a typed total from before food was logged is kept`() {
        val old = today.minusDays(3)
        val eaten = state(listOf(DailyMetricFull(date = old.toString(), caloriesIn = 2100.0)), emptyList()).eaten
        assertEquals(2100.0, eaten.values.single(), 0.0)
    }

    @Test
    fun `a day with food logged is not also counted from its typed total`() {
        // Both on one day would double it.
        val eaten = state(
            listOf(DailyMetricFull(date = today.toString(), caloriesIn = 2100.0)),
            listOf(food(1, today, 400)),
        ).eaten
        assertEquals(400.0, eaten.values.single(), 0.0)
    }

    @Test
    fun `food from before the window is left out`() {
        val eaten = state(emptyList(), listOf(food(1, today.minusDays(60), 500), food(2, today, 300))).eaten
        assertEquals(listOf(today.toString()), eaten.dates)
    }

    @Test
    fun `an evening meal west of Greenwich is filed under its own day`() {
        // 7pm in Los Angeles is already tomorrow in UTC; matching the stamp's
        // text against the date filed it there.
        val la = ZoneId.of("America/Los_Angeles")
        val stamp = LocalDate.of(2026, 3, 10).atTime(19, 0).atZone(la)
            .withZoneSameInstant(ZoneOffset.UTC).toOffsetDateTime().toString()
        assertEquals("2026-03-10", localDayOf(stamp, la))
    }

    @Test
    fun `a stamp that does not parse has no day`() {
        assertNull(localDayOf("not a time"))
    }
}
