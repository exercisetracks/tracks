// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.plan

import java.time.LocalDate
import java.time.YearMonth

/**
 * The weeks a month grid needs, Monday-first and always whole.
 *
 * Padding out to complete weeks is what keeps the columns meaning a weekday.
 * Without it the first row is short and every day below sits under the wrong
 * heading — which on a training plan means reading Saturday's long run as a
 * Wednesday session.
 *
 * (The month view itself is in [TrainingContent]; it replaced a grid of
 * coloured dots with the web's titled chips.)
 */
internal fun weeksOf(month: YearMonth): List<List<LocalDate>> {
    val first = month.atDay(1)
    // DayOfWeek.value is Monday=1; back up to the Monday on or before the 1st.
    val start = first.minusDays((first.dayOfWeek.value - 1).toLong())
    val last = month.atEndOfMonth()
    val end = last.plusDays((7 - last.dayOfWeek.value).toLong())

    val weeks = mutableListOf<List<LocalDate>>()
    var cursor = start
    while (!cursor.isAfter(end)) {
        weeks += (0L until 7L).map { cursor.plusDays(it) }
        cursor = cursor.plusWeeks(1)
    }
    return weeks
}

/** The label a workout type shows in a legend or a detail header. */
internal fun workoutTypeLabel(type: String): String =
    type.replace('_', ' ').replaceFirstChar { it.uppercase() }.ifBlank { "Workout" }
