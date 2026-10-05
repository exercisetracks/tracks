// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.plan

import androidx.compose.ui.graphics.Color
import com.tracks.core.api.PlannedWorkout
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

/**
 * Which of the web's chip colours a workout gets: `workoutColor()` in
 * frontend/src/components/plancalendar/constants.js, rule for rule. Strength
 * sessions are told apart by their title, as on the web, because a generated
 * "Upper Body & Core" and "Leg Day" share one workout type.
 */
fun toneKey(workoutType: String?, title: String?): String {
    val type = workoutType.orEmpty()
    if (type == "mobility") return "mobility"
    if (type == "strength") {
        val t = title.orEmpty().lowercase()
        return when {
            "lower" in t -> "strength_lower"
            "upper" in t && "core" in t -> "strength_core"
            "upper" in t -> "strength_upper"
            "push" in t -> "strength_push"
            "pull" in t -> "strength_pull"
            "leg" in t -> "strength_legs"
            "full" in t -> "strength_full"
            else -> "strength"
        }
    }
    return if (type in HUE_OF) type else "default"
}

/**
 * The Tailwind hue behind each tone. `accent` is the user's accent colour,
 * resolved by the caller from the theme; `race` is the inverted slate chip.
 */
internal val HUE_OF: Map<String, String> = mapOf(
    "easy" to "accent", "easy_spin" to "accent",
    "long_run" to "blue", "tempo" to "amber", "intervals" to "red",
    "race_pace" to "orange", "fartlek" to "purple", "short_quality" to "pink",
    "race" to "race", "endurance" to "sky", "sweet_spot" to "teal",
    "aerobic" to "cyan", "strength" to "violet", "mobility" to "teal",
    "strength_lower" to "orange", "strength_upper" to "indigo", "strength_core" to "rose",
    "strength_push" to "sky", "strength_pull" to "violet", "strength_legs" to "amber",
    "strength_full" to "yellow", "default" to "slate",
)

/** A chip's fill, text and outline. */
data class Tone(val fill: Color, val text: Color, val border: Color)

/**
 * Tailwind v3's 100/200/300/700/800/900 steps for each hue the chips use — the
 * web's `bg-X-100 text-X-700 border-X-200` in light, and
 * `bg-X-900/40 text-X-300 border-X-800` in dark.
 */
private val PALETTE: Map<String, LongArray> = mapOf(
    "blue" to longArrayOf(0xFFDBEAFE, 0xFFBFDBFE, 0xFF93C5FD, 0xFF1D4ED8, 0xFF1E40AF, 0xFF1E3A8A),
    "amber" to longArrayOf(0xFFFEF3C7, 0xFFFDE68A, 0xFFFCD34D, 0xFFB45309, 0xFF92400E, 0xFF78350F),
    "red" to longArrayOf(0xFFFEE2E2, 0xFFFECACA, 0xFFFCA5A5, 0xFFB91C1C, 0xFF991B1B, 0xFF7F1D1D),
    "orange" to longArrayOf(0xFFFFEDD5, 0xFFFED7AA, 0xFFFDBA74, 0xFFC2410C, 0xFF9A3412, 0xFF7C2D12),
    "purple" to longArrayOf(0xFFF3E8FF, 0xFFE9D5FF, 0xFFD8B4FE, 0xFF7E22CE, 0xFF6B21A8, 0xFF581C87),
    "pink" to longArrayOf(0xFFFCE7F3, 0xFFFBCFE8, 0xFFF9A8D4, 0xFFBE185D, 0xFF9D174D, 0xFF831843),
    "sky" to longArrayOf(0xFFE0F2FE, 0xFFBAE6FD, 0xFF7DD3FC, 0xFF0369A1, 0xFF075985, 0xFF0C4A6E),
    "teal" to longArrayOf(0xFFCCFBF1, 0xFF99F6E4, 0xFF5EEAD4, 0xFF0F766E, 0xFF115E59, 0xFF134E4A),
    "cyan" to longArrayOf(0xFFCFFAFE, 0xFFA5F3FC, 0xFF67E8F9, 0xFF0E7490, 0xFF155E75, 0xFF164E63),
    "violet" to longArrayOf(0xFFEDE9FE, 0xFFDDD6FE, 0xFFC4B5FD, 0xFF6D28D9, 0xFF5B21B6, 0xFF4C1D95),
    "indigo" to longArrayOf(0xFFE0E7FF, 0xFFC7D2FE, 0xFFA5B4FC, 0xFF4338CA, 0xFF3730A3, 0xFF312E81),
    "rose" to longArrayOf(0xFFFFE4E6, 0xFFFECDD3, 0xFFFDA4AF, 0xFFBE123C, 0xFF9F1239, 0xFF881337),
    "yellow" to longArrayOf(0xFFFEF9C3, 0xFFFEF08A, 0xFFFDE047, 0xFFA16207, 0xFF854D0E, 0xFF713F12),
    // Slate for the default chip: 100/200/300 and the web's text-slate-600 in the 700 slot.
    "slate" to longArrayOf(0xFFF1F5F9, 0xFFE2E8F0, 0xFFCBD5E1, 0xFF475569, 0xFF334155, 0xFF1E293B),
)

/** The chip tone for [key] ([toneKey]'s answer), light or [dark], with [accent] for the accent hue. */
fun tone(key: String, dark: Boolean, accent: Color, surface: Color): Tone {
    val hue = HUE_OF[key] ?: "slate"
    if (hue == "accent") {
        return if (dark) Tone(accent.copy(alpha = 0.22f), accent, accent.copy(alpha = 0.45f))
        else Tone(accent.copy(alpha = 0.14f), accent, accent.copy(alpha = 0.35f))
    }
    if (hue == "race") {
        return if (dark) Tone(Color.White, Color(0xFF0F172A), Color(0xFFCBD5E1))
        else Tone(Color(0xFF0F172A), Color.White, Color(0xFF334155))
    }
    val p = PALETTE.getValue(hue)
    return if (dark) {
        // `bg-X-900/40` over the card: the 900 step at 40%, composited here so
        // the chip is opaque and text contrast does not depend on what is under it.
        val base = Color(p[5])
        Tone(lerpOver(surface, base, 0.4f), Color(p[2]), Color(p[4]))
    } else {
        Tone(Color(p[0]), Color(p[3]), Color(p[1]))
    }
}

private fun lerpOver(under: Color, over: Color, alpha: Float) = Color(
    red = under.red + (over.red - under.red) * alpha,
    green = under.green + (over.green - under.green) * alpha,
    blue = under.blue + (over.blue - under.blue) * alpha,
    alpha = 1f,
)
