// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.metrics

import kotlin.math.roundToInt

/**
 * The daily step, weekly intensity-minute and daily floor goals the Health
 * dials fill toward: the user's own number, or Auto.
 *
 * Auto follows what the person actually does, nudged up a little, inside a
 * band the evidence supports — so a goal is always just reachable, and never
 * climbs forever after a few big weeks:
 *
 * - **Steps**: median of the last 28 worn days, +5%, rounded to 500, kept in
 *   5,000–12,000. The mortality benefit of daily steps levels off around
 *   8,000–10,000 for over-60s and 10,000–12,000 for younger adults (Paluch et
 *   al., Lancet Public Health 2022), so a goal past 12,000 buys nothing; under
 *   5,000 is the "sedentary" line (Tudor-Locke 2013) a goal should lift people
 *   out of. 8,000 until there are 7 days to go on.
 * - **Intensity minutes**: the WHO's 150 a week (vigorous counting double),
 *   rising toward the recent weekly average +10%, up to the WHO's 300 — the
 *   top of its recommended range, beyond which it names no further benefit.
 * - **Floors**: median +10%, kept in 5–20. There is no dose-response research
 *   behind a floor count; 10 is the convention the watch makers settled on,
 *   and the band only keeps a hill-dweller's goal sane.
 *
 * The median, not the mean: one long hike would otherwise lift the next four
 * weeks' step goal. The web has a port (`components/health/goals.js`).
 */
object HealthGoals {

    /** One finished day: what the watch counted. Null where it counted nothing. */
    data class Day(val steps: Int?, val intensityMin: Int?, val floors: Int?)

    data class Goals(val steps: Int, val intensityWeekly: Int, val floors: Int)

    /** The defaults: what Auto says before there is enough history. */
    val DEFAULT = Goals(steps = 8_000, intensityWeekly = 150, floors = 10)

    /** How many finished days Auto looks back over. */
    const val WINDOW_DAYS = 28

    /** Fewer worn days than this and Auto keeps the defaults. */
    const val MIN_DAYS = 7

    /**
     * Auto, from the finished days of the last [WINDOW_DAYS] (today left out:
     * a half-day would drag the median down every morning).
     */
    fun auto(days: List<Day>): Goals {
        val worn = days.filter { (it.steps ?: 0) > 0 || it.intensityMin != null }
        val steps = days.mapNotNull { it.steps?.takeIf { s -> s > 0 } }
        val floors = days.mapNotNull { it.floors }
        return Goals(
            steps = if (steps.size < MIN_DAYS) DEFAULT.steps
            else roundTo(median(steps) * 1.05, 500).coerceIn(5_000, 12_000),
            intensityWeekly = if (worn.size < MIN_DAYS) DEFAULT.intensityWeekly
            else roundTo(worn.sumOf { it.intensityMin ?: 0 } * 7.0 / worn.size * 1.10, 10).coerceIn(150, 300),
            floors = if (floors.size < MIN_DAYS) DEFAULT.floors
            else (median(floors) * 1.10).roundToInt().coerceIn(5, 20),
        )
    }

    /**
     * The goals in force: each manual value where the setting says so, Auto
     * otherwise. [settings] is the account's settings row — the
     * `*_goal_mode` / `*_goal_manual` pairs, as the zone thresholds have.
     */
    fun resolve(auto: Goals, settings: Map<String, Any?>): Goals {
        fun manual(key: String): Int? =
            if (settings["${key}_mode"]?.toString() == "manual") {
                (settings["${key}_manual"] as? Number)?.toInt()
                    ?: settings["${key}_manual"]?.toString()?.toDoubleOrNull()?.toInt()
            } else {
                null
            }
        return Goals(
            steps = manual("step_goal") ?: auto.steps,
            intensityWeekly = manual("intensity_goal") ?: auto.intensityWeekly,
            floors = manual("floors_goal") ?: auto.floors,
        )
    }

    private fun median(values: List<Int>): Double {
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid].toDouble() else (s[mid - 1] + s[mid]) / 2.0
    }

    private fun roundTo(v: Double, step: Int): Int = (v / step).roundToInt() * step
}
