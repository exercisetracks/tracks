// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.workout

import kotlin.math.ceil

/**
 * The numbers behind the run screen's dials, kept apart from the drawing so
 * they can be tested without a screen.
 *
 * A dial needs a scale as well as a reading, and most of a run's readings have
 * no published one — there is no "good" distance. So each scale here is the
 * run's own: the plan's distance, the step's target, the runner's average pace.
 */

/**
 * Where the distance dial's arc ends: the planned distance, or with no plan
 * the next 5 km mark past what has been run, so the arc always has somewhere
 * to go and a 12 km run is not pinned at the end of a 5 km scale.
 */
fun distanceScaleM(plannedM: Double?, coveredM: Double): Double {
    plannedM?.takeIf { it > 0 }?.let { return it }
    val next = ceil((coveredM + 1.0) / FIVE_K) * FIVE_K
    return next.coerceAtLeast(FIVE_K)
}

/**
 * How much of the current step is done, 0 to 1, in whichever unit it ends in
 * — distance beats time when a step has both, matching the step runner.
 * Null for a step with no target (it ends when the runner says so).
 */
fun stepDone(step: GuidedStep, coveredM: Double, remainingSec: Int): Double? = when {
    step.metres != null && step.metres > 0 -> (coveredM / step.metres).coerceIn(0.0, 1.0)
    step.seconds != null && step.seconds > 0 ->
        ((step.seconds - remainingSec).toDouble() / step.seconds).coerceIn(0.0, 1.0)
    else -> null
}

/**
 * The pace dial's scale, as speeds: the run's own average, 25% either side.
 *
 * Against the average because that is the question a runner glances down with
 * — "am I holding it?" — and it needs no target the plan may not have given.
 * Null until there is an average to compare with.
 */
fun paceScale(averageMps: Double): Pair<Double, Double>? {
    if (averageMps < MIN_SPEED) return null
    return averageMps * (1 - PACE_SPREAD) to averageMps * (1 + PACE_SPREAD)
}

/** Below this a phone is standing still, and a pace would be a large number of nothing. */
const val MIN_SPEED = 0.1

private const val FIVE_K = 5000.0
private const val PACE_SPREAD = 0.25
