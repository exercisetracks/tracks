// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.dashboard

import com.tracks.core.format.Units
import com.tracks.core.format.distance
import com.tracks.core.format.elapsed
import com.tracks.core.format.elevation
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * One headline figure, and whether there is one.
 *
 * [value] is null rather than "—" when nothing was recorded, because the
 * difference matters to [pickFront]: a row of three dashes is a worse front row
 * than a row of three numbers, and only a null can say which a stat is.
 */
internal data class Stat(val label: String, val value: String?)

/**
 * The twelve figures, in the order the grid reads them.
 *
 * The first six are the web app's, in its order, so the two dashboards agree
 * about what a training window looks like. The rest are the phone's own — see
 * [DashboardExtras] — computed from mirror columns `/metrics/summary` does not
 * answer for.
 *
 * Order is fixed here even though the front row is not. Whatever [pickFront]
 * lifts out, both the front row and the rest are drawn in *this* sequence, so
 * the section reads as a deliberate list with three of its entries promoted
 * rather than as a shuffled bag of numbers.
 */
internal fun statsOf(state: DashboardUiState): List<Stat> {
    val summary = state.summary
    val extras = state.extras
    return listOf(
        // Zero activities is a real answer about a window, not a missing one,
        // so this stat is never blank and is always front-row eligible.
        Stat("Activities", "${summary?.activityCount ?: 0}"),
        Stat("Distance", number(summary?.totalDistanceKm?.let(::inUnits), distanceUnit())),
        Stat("Time", number(summary?.totalDurationHours, "h")),
        Stat("Sports", summary?.sportCount?.let { "$it" }),
        Stat("Avg distance", number(summary?.avgDistanceKm?.let(::inUnits), distanceUnit(), 1)),
        Stat("Avg duration", number(summary?.avgDurationMinutes, "min")),
        Stat("Climb", extras.totalAscentM?.let { elevation(it) }),
        Stat("Calories", extras.totalCalories?.let { "$it" }),
        // Days trained, not sessions. Six rides across two days is a different
        // fortnight from six across six.
        Stat("Active days", extras.activeDays.takeIf { it > 0 }?.let { "$it" }),
        Stat("Longest", extras.longestDistanceM?.let { distance(it) }),
        Stat("Longest time", extras.longestDurationSec?.let { elapsed(it) }),
        Stat("Avg HR", extras.avgHeartRate?.let { "$it" }),
    )
}

/** Rows of three, which is what fits a phone at a size worth reading. */
internal const val OVERVIEW_COLUMNS = 3

/**
 * Which three to show when the section is closed.
 *
 * ## Why it moves at all
 *
 * Twelve figures under one heading is a wall, and the same three on top of it
 * every single time means the other nine are seen roughly never — they are one
 * tap away and nobody taps. Rotating the promotion means a month of opening the
 * app shows the whole set, and the tap is for when you want a *particular* one
 * rather than for discovering that it exists.
 *
 * ## Why the seed is a parameter
 *
 * So it is fixed for a run of the app rather than re-rolled per composition —
 * see [overviewSeed]. Numbers that reshuffle while you scroll past them are not
 * variety, they are a bug.
 *
 * ## Why blanks lose
 *
 * A stat with nothing behind it renders "—", and three dashes is the one front
 * row that says less than no front row at all. So the draw is over the
 * populated stats first and only falls back to empty ones when a window
 * genuinely has fewer than [width] figures in it — an empty week, where three
 * dashes are the honest answer.
 *
 * Returns indices into [stats], ascending, so the caller draws them in the
 * canonical order rather than in draw order.
 */
internal fun pickFront(stats: List<Stat>, seed: Long, width: Int = OVERVIEW_COLUMNS): List<Int> {
    if (stats.isEmpty() || width <= 0) return emptyList()
    val drawn = stats.indices.shuffled(Random(seed))
    val (populated, blank) = drawn.partition { stats[it].value != null }
    return (populated + blank).take(width).sorted()
}

/**
 * The draw, fixed for the life of the process.
 *
 * A `val` on an object, so it is evaluated once when the class is first touched
 * and holds until the app is killed — which is exactly "every time you open the
 * app, slightly different stats". Deriving it from the clock rather than
 * counting launches keeps it stateless: nothing to persist, nothing to migrate,
 * and no way for a stuck counter to pin the same three figures forever.
 */
internal object OverviewSeed {
    val value: Long = System.nanoTime()
}

/**
 * Presentation-only formatting — choices about this screen, not shared domain
 * formatting the web app must match.
 *
 * Null in, null out: the caller decides what an absent figure looks like, and
 * here that decision is [Stat.value] being null rather than a dash baked in.
 */
internal fun number(value: Double?, unit: String, decimals: Int = 0): String? {
    if (value == null) return null
    return if (decimals == 0) "${value.roundToInt()} $unit" else "${oneDecimal(value)} $unit"
}

internal fun oneDecimal(value: Double): String {
    val rounded = kotlin.math.round(value * 10) / 10
    return if (rounded % 1.0 == 0.0) "${rounded.toInt()}" else "$rounded"
}

/** Kilometres in the account's distance unit — the summary is metric; the person may not be. */
private fun inUnits(km: Double): Double = if (Units.imperial) km / KM_PER_MILE else km

private fun distanceUnit(): String = if (Units.imperial) "mi" else "km"

private const val KM_PER_MILE = 1.60934
