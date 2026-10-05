// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.plan

import com.tracks.core.fit.decode.CivilDate

/**
 * Where an event goal is in its Base → Build → Peak → Taper arc.
 *
 * The web's `planPhaseInfo` (frontend/src/components/goals/helpers.js), which
 * itself mirrors the generator's `_phase_for_week` — so the phase the goal
 * card names is the phase the generated workouts were built for. The week
 * split is repeated rather than derived from [PlanBase.phaseForWeek] because
 * the card also shows how many weeks each phase gets, which that function does
 * not expose.
 *
 * [planStart] is when the plan began. The web uses the goal's `created_at`,
 * which does not sync; a phone reads the goal row's earliest field stamp,
 * which is the moment it was created on whichever device made it.
 */
object PlanPhases {

    enum class Phase(val label: String, val description: String) {
        BASE("Base", "Aerobic foundation — easy efforts promoted to aerobic, session duration extended +10%."),
        BUILD("Build", "Race-specific fitness — aerobic efforts pushed toward tempo intensity."),
        PEAK("Peak", "Sharpening — hard efforts maintained while fitness peaks before taper."),
        TAPER("Taper", "Arrive fresh — intensity capped at easy, volume reduced to 65% of normal."),
    }

    data class Info(val current: Phase, val weeks: Map<Phase, Int>)

    fun info(eventDate: CivilDate, planStart: CivilDate, today: CivilDate): Info {
        val totalDays = maxOf(1L, eventDate.epochDay - planStart.epochDay)
        // JS `Math.ceil(totalDays / 7)` on a positive integer.
        val totalWeeks = maxOf(1L, (totalDays + 6) / 7).toInt()
        val weekNum = (maxOf(0L, today.epochDay - planStart.epochDay) / 7).toInt()
        val taper = minOf(3, maxOf(1, totalWeeks / 5))
        val peak = if (totalWeeks > 6) minOf(4, maxOf(1, (totalWeeks - taper) / 4)) else 0
        val build = if (totalWeeks > 4) minOf(5, maxOf(1, (totalWeeks - taper - peak) / 3)) else 0
        val base = maxOf(1, totalWeeks - taper - peak - build)
        val current = when {
            totalWeeks <= 1 -> Phase.TAPER
            weekNum < base -> Phase.BASE
            weekNum < base + build -> Phase.BUILD
            weekNum < base + build + peak -> Phase.PEAK
            else -> Phase.TAPER
        }
        return Info(current, mapOf(Phase.BASE to base, Phase.BUILD to build, Phase.PEAK to peak, Phase.TAPER to taper))
    }
}
