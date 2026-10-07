// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.plan

import androidx.compose.ui.graphics.Color
import com.tracks.core.api.PlannedWorkout
import com.tracks.core.spec.WorkoutColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * The shape of the training plan on a phone: a week of days, each with its
 * workouts, and the colour each workout is drawn in.
 *
 * Kept free of Compose layout so the grouping and the colour rules can be
 * tested without a screen — they are the two things that must match the web.
 */

/** One day of the agenda. Rest days are kept: a week that hides them reads denser than it is. */
data class AgendaDay(val date: LocalDate, val workouts: List<PlannedWorkout>)

/** Monday of the week [date] falls in — the web's calendar starts its weeks on Monday too. */
fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

/** The seven days from [monday], each with its workouts in title order within the day. */
fun agendaWeek(monday: LocalDate, workouts: List<PlannedWorkout>): List<AgendaDay> {
    val byDate = workouts.groupBy { it.scheduledDate.take(10) }
    return (0L until 7L).map { i ->
        val d = monday.plusDays(i)
        AgendaDay(d, byDate[d.toString()].orEmpty().sortedWith(DAY_ORDER))
    }
}

/**
 * Within a day: the session that matters first. A race above everything, then
 * endurance work, then strength, then mobility — the order the web's calendar
 * cell reads in, which lists generated workouts in the order they were built.
 */
private val DAY_ORDER = compareBy<PlannedWorkout>(
    {
        when (it.workoutType) {
            "race" -> 0
            "strength" -> 2
            "mobility", "flexibility" -> 3
            else -> 1
        }
    },
    { it.title },
)

// ── Colours ──────────────────────────────────────────────────────────────────

/** A chip's fill, text and outline. */
data class Tone(val fill: Color, val text: Color, val border: Color, val dot: Color)

/**
 * A workout's chip colours: its family's hue at its intensity's shade, from
 * spec/workout_colors.yaml through [WorkoutColors] — the web's
 * lib/workoutColors.js, which reads the same generated tables.
 *
 * Light is the web's `bg-X-<fill> text-X-<text> border-X-<border>`; dark its
 * `dark:bg-X-<darkFill>/<alpha>`, composited over [surface] here so the chip is
 * opaque and its text contrast does not depend on what is under it.
 */
fun workoutTone(workoutType: String?, sport: String?, dark: Boolean, surface: Color): Tone {
    val hue = WorkoutColors.hue(workoutType, sport)
        ?: return if (dark) Tone(Color.White, Color(0xFF0F172A), Color(0xFFCBD5E1), Color.White)
        else Tone(Color(0xFF0F172A), Color.White, Color(0xFF334155), Color(0xFF0F172A))
    val sh = WorkoutColors.shade(WorkoutColors.level(workoutType))
    fun c(step: Int) = Color(WorkoutColors.argb(hue, step))
    return if (dark) {
        Tone(lerpOver(surface, c(sh.darkFill), sh.darkFillAlpha), c(sh.darkText), c(sh.darkBorder), c(sh.dot))
    } else {
        Tone(c(sh.fill), c(sh.text), c(sh.border), c(sh.dot))
    }
}

private fun lerpOver(under: Color, over: Color, alpha: Float) = Color(
    red = under.red + (over.red - under.red) * alpha,
    green = under.green + (over.green - under.green) * alpha,
    blue = under.blue + (over.blue - under.blue) * alpha,
    alpha = 1f,
)
