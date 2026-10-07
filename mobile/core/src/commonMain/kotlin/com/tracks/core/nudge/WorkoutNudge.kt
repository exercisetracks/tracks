// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.nudge

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow

/**
 * When to send the day's one workout reminder, and whether to send it at all.
 *
 * ## The idea
 *
 * Engagement-driven apps time their notifications to the moment a person is
 * most likely to act on them, learned from when that person has acted before.
 * The same technique works for a habit worth having: someone who runs at 6:30
 * on weekdays and 9:00 on Saturdays is best nudged a little before *those*
 * times, not at a fixed hour picked by the app. A nudge after the window has
 * passed is noise; one well before it is forgotten by the time it matters.
 *
 * What this deliberately does *not* borrow from those apps is volume: one
 * notification a day at most, none on a day already trained or a planned rest
 * day, and nothing in the night. The goal is a habit, not attention.
 *
 * ## How the time is learned
 *
 * Every past start is a point on a 24-hour circle, weighted by
 *
 * - **recency** — halving every [HALF_LIFE_DAYS], so a move from evening to
 *   morning runs is followed within a few weeks rather than averaged away;
 * - **day of week** — the same weekday counts most, the same kind of day
 *   (weekday / weekend) next, the other kind barely. Weekend and weekday habits
 *   are usually different, and a per-weekday model alone has too few points.
 *
 * Those points are smoothed with a Gaussian kernel ([KERNEL_SIGMA_MIN]) that
 * wraps at midnight, and the peak is the habitual start. The reminder goes
 * [LEAD_MIN] before it: time to change and get out of the door.
 *
 * A mean or median was the obvious alternative and is wrong for this shape of
 * data: someone who trains at 7:00 and 19:00 has a median near 13:00, a time
 * they never train. The kernel peak picks one of the real habits.
 *
 * ## When it is not trusted
 *
 * With fewer than [MIN_STARTS] recent starts, or a peak holding less than
 * [MIN_PEAK_SHARE] of the weight within an hour either side (times scattered
 * across the day — there is no habit to aim at), the user's fallback time is
 * used instead. A confidently-wrong learned time is worse than a predictable
 * one the user chose.
 *
 * Pure integer inputs, no clock and no time zone: the caller converts its own
 * timestamps, which keeps this testable and platform-free.
 */
object WorkoutNudge {

    /**
     * One past workout start, already in the phone's local time.
     *
     * @param minuteOfDay 0..1439
     * @param dayOfWeek ISO, 1 = Monday … 7 = Sunday
     * @param daysAgo whole days before the day being planned; 0 = that day
     */
    data class Start(val minuteOfDay: Int, val dayOfWeek: Int, val daysAgo: Int)

    /**
     * The chosen time.
     *
     * @param habitMinute the learned habitual start, when one was trusted —
     *   what the reminder's text and the settings line can mention.
     */
    data class Timing(val minuteOfDay: Int, val learned: Boolean, val habitMinute: Int? = null)

    /** How far back a start still counts. A season; older habits rarely still hold. */
    const val WINDOW_DAYS = 120

    /** Recency half-life of a start's weight. */
    const val HALF_LIFE_DAYS = 28.0

    /** Fewer recent starts than this and the fallback is used. */
    const val MIN_STARTS = 4

    /** Share of the weight that must sit within ±[PEAK_BAND_MIN] of the peak to trust it. */
    const val MIN_PEAK_SHARE = 0.3

    const val PEAK_BAND_MIN = 60

    /** Kernel width: starts 40 minutes apart read as the same habit. */
    const val KERNEL_SIGMA_MIN = 40.0

    /** How long before the habitual start the reminder comes. */
    const val LEAD_MIN = 30

    /**
     * The window a reminder may land in. A habit of 5:30 runs gets its reminder
     * at 6:00 rather than at 5:00 — waking someone for a run they may have
     * deliberately skipped is the kind of nudge that gets a channel switched off.
     */
    const val EARLIEST_MIN = 6 * 60
    const val LATEST_MIN = 21 * 60

    private const val DAY_MIN = 24 * 60
    private const val GRID_STEP_MIN = 5

    private const val SAME_WEEKDAY = 2.0
    private const val SAME_KIND_OF_DAY = 1.0
    private const val OTHER_KIND_OF_DAY = 0.25

    /**
     * The reminder time for a day falling on [dayOfWeek], learned from
     * [history] where it can be, else [fallbackMinute].
     */
    fun timing(history: List<Start>, dayOfWeek: Int, fallbackMinute: Int): Timing {
        val fallback = Timing(clamp(fallbackMinute), learned = false)
        val recent = history.filter { it.daysAgo in 0..WINDOW_DAYS && it.minuteOfDay in 0 until DAY_MIN }
        if (recent.size < MIN_STARTS) return fallback

        val weighted = recent.map { it.minuteOfDay to weight(it, dayOfWeek) }
        val total = weighted.sumOf { it.second }
        if (total <= 0.0) return fallback

        val peak = (0 until DAY_MIN step GRID_STEP_MIN).maxBy { minute ->
            weighted.sumOf { (m, w) ->
                val d = circularDistance(minute, m).toDouble()
                w * exp(-(d * d) / (2 * KERNEL_SIGMA_MIN * KERNEL_SIGMA_MIN))
            }
        }
        val share = weighted.filter { (m, _) -> circularDistance(peak, m) <= PEAK_BAND_MIN }.sumOf { it.second } / total
        if (share < MIN_PEAK_SHARE) return fallback

        val at = ((peak - LEAD_MIN) % DAY_MIN + DAY_MIN) % DAY_MIN
        return Timing(clamp(at), learned = true, habitMinute = peak)
    }

    /**
     * Whether today gets a reminder at all.
     *
     * @param trainedToday a workout has already been started today
     * @param planActive the plan has sessions near today, so a day without one
     *   is a rest day rather than an unplanned one
     * @param plannedToday today's planned sessions, by workout type
     * @param plannedTodayComplete every one of them is already marked done
     */
    fun shouldRemind(
        trainedToday: Boolean,
        planActive: Boolean,
        plannedToday: List<String>,
        plannedTodayComplete: Boolean,
    ): Boolean {
        if (trainedToday) return false
        val sessions = plannedToday.filter { it != "rest" }
        // A plan that left today empty, or put a rest day in it, meant it.
        // Nagging on a rest day teaches someone to ignore the reminder on the
        // days it matters.
        if (planActive && sessions.isEmpty()) return false
        if (sessions.isNotEmpty() && plannedTodayComplete) return false
        return true
    }

    private fun weight(start: Start, dayOfWeek: Int): Double {
        val recency = 0.5.pow(start.daysAgo / HALF_LIFE_DAYS)
        val day = when {
            start.dayOfWeek == dayOfWeek -> SAME_WEEKDAY
            isWeekend(start.dayOfWeek) == isWeekend(dayOfWeek) -> SAME_KIND_OF_DAY
            else -> OTHER_KIND_OF_DAY
        }
        return recency * day
    }

    private fun isWeekend(dayOfWeek: Int) = dayOfWeek >= 6

    /** Minutes apart on the clock face, so 23:50 and 00:10 are 20 apart, not 1420. */
    private fun circularDistance(a: Int, b: Int): Int {
        val d = abs(a - b) % DAY_MIN
        return min(d, DAY_MIN - d)
    }

    private fun clamp(minute: Int): Int = minute.coerceIn(EARLIEST_MIN, LATEST_MIN)
}
