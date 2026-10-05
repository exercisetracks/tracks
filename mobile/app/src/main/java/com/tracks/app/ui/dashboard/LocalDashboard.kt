// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.dashboard

import com.tracks.core.api.ActivityCalendarPoint
import com.tracks.core.api.ActivitySummary
import com.tracks.core.api.MetricsSummary
import com.tracks.core.api.SportBreakdown
import com.tracks.core.api.WeeklyVolumePoint
import com.tracks.core.plan.Matching
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * The figures the server does not send, and the phone can work out.
 *
 * All of these exist in the mirror already — every one is a column on
 * [ActivitySummary] — and none of them is in `/metrics/summary`, which answers
 * the same six questions the web dashboard asks and no more. Computing them
 * here is not duplication: it is the only place they can come from without a
 * new endpoint.
 */
data class DashboardExtras(
    val totalAscentM: Double? = null,
    val totalCalories: Int? = null,
    val longestDistanceM: Double? = null,
    val longestDurationSec: Int? = null,
    val activeDays: Int = 0,
    val avgHeartRate: Int? = null,
    val busiestSport: String? = null,
)

/** Everything a dashboard window needs, worked out from the phone's own copy. */
data class LocalDashboard(
    val summary: MetricsSummary,
    val bySport: List<SportBreakdown>,
    val calendar: List<ActivityCalendarPoint>,
    val weeklyVolume: List<WeeklyVolumePoint>,
    val extras: DashboardExtras,
)

/**
 * The dashboard, computed from the mirror.
 *
 * ## Why the phone does this at all
 *
 * Every windowed slice of the dashboard was a request. That is fine with a
 * server and useless without one: a window the phone had never opened had no
 * cached document, so tapping "7 days" on a mountain produced an empty screen —
 * and a window it *had* opened showed whatever was true the last time there was
 * signal, which after a fortnight of watch syncs with no server is a month-old
 * answer to a question about the last week.
 *
 * The inputs are all local. Counts, distances, durations and per-day totals are
 * arithmetic over rows the phone already holds — there is no model here, no
 * exponential average, nothing that needs the server's history. So the phone
 * can answer these four itself, and does, immediately and for any window.
 *
 * ## What it is not
 *
 * It is not the fitness model. CTL, ATL and TSB are 42-day exponential averages
 * over every activity ever recorded, and computing them from a mirror that
 * holds a window would draw a fitness collapse that never happened. Nor is it
 * readiness or VO₂max, which come off the watch's own sensors through the
 * server. Those stay fetched, and stay absent when they cannot be.
 *
 * ## Where it can disagree with the server
 *
 * The server's own query drops activities from unclaimed devices and sports the
 * user has hidden; the mirror has neither concept, and it excludes merged trips
 * where the server's metrics do not. So a count here can differ by a few from
 * the same count fetched. That is why the server's answer replaces this one the
 * moment it arrives rather than the other way round — this is the answer when
 * there is no better one, not a second opinion.
 *
 * ## Which day an activity is on
 *
 * The day it started in the account's zone — [Matching.localDate], the
 * server's `activity_local_date` — not the first ten characters of the stored
 * UTC timestamp. Both sides used to take the UTC day, on purpose, so the two
 * calendars agreed; but it put an 18:04 run in California on tomorrow's
 * square. The server now buckets by the local day too (calculators/local_day.py),
 * so they still agree, on the right day. The window starts at the local
 * midnight of [after], as the server's does.
 *
 * @param after ISO date, or null for lifetime: the first local day of the window.
 * @param sport raw FIT sport name to narrow the calendar and volume to, as the
 * cross-filter does. Null is every sport.
 * @param offsetSecondsAt the account zone's UTC offset at an instant —
 * `ZoneOffsets.of(settings.timezone)`.
 */
fun computeDashboard(
    activities: List<ActivitySummary>,
    after: String?,
    sport: String?,
    offsetSecondsAt: (epochSeconds: Long) -> Int,
): LocalDashboard {
    val days = activities.mapNotNull { a -> a.startedAt?.let { Matching.localDate(it, offsetSecondsAt) }?.let { a to it } }
    val window = days.filter { (_, day) -> after == null || day >= after.take(10) }
    val filtered = if (sport == null) window else window.filter { it.first.sport == sport }

    return LocalDashboard(
        summary = summaryOf(window.map { it.first }),
        bySport = breakdownOf(window.map { it.first }),
        calendar = calendarOf(filtered),
        weeklyVolume = volumeOf(filtered),
        extras = extrasOf(window),
    )
}

/**
 * The six headline figures, by the server's own rules.
 *
 * Average distance counts only activities that went somewhere — a treadmill
 * session with no distance would otherwise drag the average of a week's running
 * down by a third. That is the server's rule (`avg … filter(distance > 0)`) and
 * is repeated here so the number does not change when the network does.
 */
private fun summaryOf(window: List<ActivitySummary>): MetricsSummary {
    val distances = window.mapNotNull { it.distanceMeters }.filter { it > 0 }
    val durations = window.mapNotNull { it.durationSeconds }

    return MetricsSummary(
        activityCount = window.size,
        totalDistanceKm = distances.sum().takeIf { it > 0 }?.let { round2(it / 1000) },
        totalDurationHours = durations.sum().takeIf { it > 0 }?.let { round2(it / 3600.0) },
        sportCount = window.mapNotNull { it.sport }.distinct().size,
        // Devices are not mirrored, and the dashboard does not show this.
        deviceCount = 0,
        avgDistanceKm = distances.averageOrNull()?.let { round2(it / 1000) },
        avgDurationMinutes = durations.averageOrNull()
            ?.let { round1(it / 60) },
    )
}

/** Per-sport totals, commonest first — the order the breakdown list expects. */
private fun breakdownOf(window: List<ActivitySummary>): List<SportBreakdown> =
    window.filter { it.sport != null }
        .groupBy { it.sport!! }
        .map { (sport, rows) ->
            val distance = rows.mapNotNull { it.distanceMeters }.sum()
            val duration = rows.mapNotNull { it.durationSeconds }
            SportBreakdown(
                sport = sport,
                activityCount = rows.size,
                totalDistanceKm = distance.takeIf { it > 0 }?.let { round2(it / 1000) },
                totalDurationHours = duration.sum().takeIf { it > 0 }
                    ?.let { round2(it / 3600.0) },
                avgDurationMinutes = duration.averageOrNull()
                    ?.let { round1(it / 60) },
            )
        }
        .sortedByDescending { it.activityCount }

/** One entry per local day that had activity on it, as the server buckets them. */
private fun calendarOf(window: List<Pair<ActivitySummary, String>>): List<ActivityCalendarPoint> =
    window.map { it.second }
        .groupingBy { it }
        .eachCount()
        .map { (date, count) -> ActivityCalendarPoint(date, count) }
        .sortedBy { it.date }

/** Distance and time per ISO week, Monday-started, as the server buckets them. */
private fun volumeOf(window: List<Pair<ActivitySummary, String>>): List<WeeklyVolumePoint> {
    val buckets = mutableMapOf<String, MutableList<ActivitySummary>>()
    window.forEach { (activity, day) ->
        val monday = runCatching {
            LocalDate.parse(day).let { it.minusDays((it.dayOfWeek.value - 1).toLong()) }.toString()
        }.getOrNull() ?: return@forEach
        buckets.getOrPut(monday) { mutableListOf() } += activity
    }

    return buckets.entries.sortedBy { it.key }.map { (weekStart, rows) ->
        val distance = rows.mapNotNull { it.distanceMeters }.sum()
        val duration = rows.mapNotNull { it.durationSeconds }.sum()
        WeeklyVolumePoint(
            weekStart = weekStart,
            distanceKm = distance.takeIf { it > 0 }?.let { round2(it / 1000) },
            durationHours = duration.takeIf { it > 0 }?.let { round2(it / 3600.0) },
            activityCount = rows.size,
        )
    }
}

/**
 * The figures only the phone has.
 *
 * Active days rather than activity count, because they answer different
 * questions — six sessions across two days is a different fortnight from six
 * across six — and the calendar above is the only other place that distinction
 * appears at all.
 */
private fun extrasOf(dated: List<Pair<ActivitySummary, String>>): DashboardExtras {
    val window = dated.map { it.first }
    val heartRates = window.mapNotNull { it.avgHeartRate }.filter { it > 0 }
    return DashboardExtras(
        totalAscentM = window.mapNotNull { it.totalAscent }.sum().takeIf { it > 0 },
        totalCalories = window.mapNotNull { it.totalCalories }.sum().takeIf { it > 0 },
        longestDistanceM = window.mapNotNull { it.distanceMeters }.maxOrNull()?.takeIf { it > 0 },
        longestDurationSec = window.mapNotNull { it.durationSeconds }.maxOrNull()
            ?.takeIf { it > 0 },
        activeDays = dated.map { it.second }.distinct().size,
        avgHeartRate = heartRates.averageOrNull()?.roundToInt(),
        busiestSport = window.mapNotNull { it.sport }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key,
    )
}

/**
 * Null for an empty list rather than `NaN`, which formats as "NaN".
 *
 * One function over `Number` rather than an overload per element type: two
 * extensions on `List<Double>` and `List<Int>` erase to the same JVM signature
 * and will not compile together.
 */
private fun List<Number>.averageOrNull(): Double? =
    if (isEmpty()) null else sumOf { it.toDouble() } / size

private fun round2(value: Double): Double = (value * 100).roundToInt() / 100.0

private fun round1(value: Double): Double = (value * 10).roundToInt() / 10.0
