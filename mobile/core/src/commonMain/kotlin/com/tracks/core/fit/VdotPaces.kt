// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.fit

import kotlin.math.round
import kotlin.math.sqrt

/**
 * Training paces from a VDOT, so the phone can put coaching targets on a
 * workout it built itself.
 *
 * A port of `vdot_to_paces` in `app/calculators/plan/base.py`, and the only
 * piece of the plan generator that has to come across for offline generation:
 * a plan's VDOT is cached with the plan, and everything a coached endurance
 * step needs follows from it. The rest of the generator stays on the server.
 *
 * The zone fractions are percentages of velocity at VO2max, synthesised from
 * Daniels (2013) — recovery 59-66%, easy 66-74%, marathon 80-85%, threshold
 * 88-92%, interval 95-100% — and Pfitzinger & Douglas, *Advanced Marathoning*
 * (3rd ed.), for repetition work at 105%.
 */
private val VDOT_ZONES = listOf(
    "recovery" to 0.62,
    "easy" to 0.70,
    "marathon" to 0.82,
    "threshold" to 0.90,
    "interval" to 0.98,
    "repetition" to 1.05,
)

/**
 * Seconds per kilometre for each training zone at [vdot].
 *
 * Rounded to one decimal exactly as the reference does, which is not cosmetic:
 * the result is divided into a million and truncated to get a speed target, so
 * a tenth of a second here can move the integer the watch is given.
 */
fun vdotToPaces(vdot: Double): Map<String, Double> =
    VDOT_ZONES.associate { (zone, fraction) ->
        zone to round(1000 / velocityForPercentVo2Max(vdot, fraction) * 60 * 10) / 10
    }

/**
 * Metres per minute at a given fraction of VO2max, inverting Daniels' VO2 cost
 * curve — a quadratic in velocity, solved for its positive root.
 */
private fun velocityForPercentVo2Max(vdot: Double, fraction: Double): Double {
    val a = 0.000104
    val b = 0.182258
    val c = -(4.60 + vdot * fraction)
    return (-b + sqrt(b * b - 4 * a * c)) / (2 * a)
}
