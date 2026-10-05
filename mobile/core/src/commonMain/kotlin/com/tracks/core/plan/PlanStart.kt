// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.plan

/**
 * `calculators/plan/starting.py`: where a plan starts for someone with no
 * history of its sport, from how often they said they do it
 * (`user_settings.activity_frequency`).
 *
 * Without it every newcomer got one default athlete — a 3 km first run for
 * someone who has never run, and the same for someone who runs five times a
 * week. The server's docstring has the reasoning for each number; this is the
 * port, held to it by spec/fixtures/plan_start.json.
 *
 * Read only while there is no history (event plan: under 3 km a week of the
 * sport; fitness plan: CTL under 1). No answer leaves the starting volume as
 * it was; only the intro weeks (no quality) apply to it.
 */
object PlanStart {
    val LEVELS = listOf("never", "occasional", "1_2", "3_4", "5_plus")

    /** Weeks over which shortened first sessions grow back to full length. */
    const val RAMP_WEEKS = 6

    /** `_INTRO_WEEKS`: weeks without quality for someone with no history, by answer. */
    private val INTRO_WEEKS = mapOf(
        "never" to RAMP_WEEKS.toLong(), "occasional" to RAMP_WEEKS.toLong(), "1_2" to 3L, "3_4" to 0L, "5_plus" to 0L,
    )
    private const val UNANSWERED_INTRO = 3L

    data class Start(
        /** Starting weekly volume as a share of the sport's default. */
        val weeklyKmShare: Double,
        /** Where the running walk-break capacity model starts. */
        val effectiveWeeks: Double,
        /** The fitness plan's starting CTL (and ATL). */
        val ctl: Double,
        /** Session-length multiplier for week 0, easing to 1 over [RAMP_WEEKS]. */
        val intensity: Double,
    )

    private val START = mapOf(
        "never" to Start(0.35, 0.0, 0.0, 0.6),
        "occasional" to Start(0.6, 2.0, 8.0, 0.8),
        "1_2" to Start(1.0, 5.0, 15.0, 1.0),
        "3_4" to Start(1.5, 10.0, 30.0, 1.0),
        "5_plus" to Start(2.0, 16.0, 45.0, 1.0),
    )

    /** A family nobody was asked about borrows the nearest one that was. */
    private val BORROW = mapOf("mountain_biking" to "cycling")

    /** `frequency_for`: the answer for [family] from the settings map, or null. */
    fun frequencyFor(frequencies: Map<String, Any?>?, family: String): String? {
        if (frequencies == null) return null
        var level = frequencies[family] as? String
        if (level !in START && family in BORROW) level = frequencies[BORROW.getValue(family)] as? String
        return level?.takeIf { it in START }
    }

    /** `no_history_start`. */
    fun noHistoryStart(level: String?): Start? = level?.let { START[it] }

    /** `start_intensity`: the multiplier for plan week [weekNum] (0-based). */
    fun startIntensity(start: Start?, weekNum: Long): Double {
        if (start == null) return 1.0
        val s = start.intensity
        return s + (1.0 - s) * PlanBase.pyMin(1.0, weekNum.toDouble() / RAMP_WEEKS)
    }

    /** `intro_weeks`: how many weeks a no-history plan opens without quality. */
    fun introWeeks(level: String?): Long = level?.let { INTRO_WEEKS[it] } ?: UNANSWERED_INTRO

    /**
     * `start_quality`: whether plan week [weekNum] may hold quality sessions —
     * always with history; without, not in the intro weeks (starting.py says why).
     */
    fun startQuality(level: String?, weekNum: Long, noHistory: Boolean): Boolean =
        !noHistory || weekNum >= introWeeks(level)
}
