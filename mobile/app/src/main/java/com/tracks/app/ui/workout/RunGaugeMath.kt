// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.workout

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

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

// ── Distance, by section ─────────────────────────────────────────────────────

/** One section of the plan as a stretch of the distance dial, in counted metres. */
data class DistanceSegment(val fromM: Double, val toM: Double, val step: GuidedStep)

/**
 * The plan's sections laid end to end along the distance dial, so the arc shows
 * where the warm-up ends and each rep begins — and the fill crossing a boundary
 * is the run crossing it.
 *
 * ## Where each length comes from
 *
 * A section already run has its real length, from where the ViewModel saw it
 * start ([startsM], counted distance at the start of each step reached). The one
 * being run is at least what it has covered so far. The ones ahead are
 * estimates: their distance when the plan gives one, or their time at the
 * section's target pace — falling back to [fallbackMps], the runner's own
 * average — when it gives minutes. Recoveries with no pace are taken at the
 * recovery zone, since "90 s rest" is a jog or a stand and never rep pace.
 *
 * ## Fitted to the plan
 *
 * When the plan states a distance, the estimates ahead are stretched or shrunk
 * together so the dial ends where the caption under it says ("of 8.00 km") —
 * the plan's figure was itself an estimate, made with better knowledge of the
 * session's intent. Clamped, so a plan whose distance is nonsense cannot squash
 * the remaining reps to nothing.
 *
 * A walk step counts no distance (see [GuidedStep.countsDistance]) and so gets
 * no stretch of the dial; a step with no target at all is an unknown and is
 * left off rather than guessed.
 */
fun distanceSegments(
    steps: List<GuidedStep>,
    index: Int,
    startsM: List<Double>,
    coveredM: Double,
    plannedM: Double?,
    paces: Map<String, Double>?,
    fallbackMps: Double,
): List<DistanceSegment> {
    val fallback = fallbackMps.takeIf { it > MIN_SPEED } ?: DEFAULT_SPEED
    fun estimate(step: GuidedStep): Double {
        if (!step.countsDistance) return 0.0
        step.metres?.let { return it }
        val seconds = step.seconds ?: return 0.0
        val zone = step.paceZone ?: if (step.kind == StepKind.Rest) "recovery" else null
        val speed = zone?.let { paces?.get(it) }?.takeIf { it > 0 }?.let { 1000.0 / it }
            ?: if (step.kind == StepKind.Rest) fallback * REST_FRACTION else fallback
        return seconds * speed
    }

    val lengths = steps.mapIndexed { i, step ->
        val start = startsM.getOrNull(i)
        val end = startsM.getOrNull(i + 1)
        when {
            i < index && start != null && end != null -> (end - start).coerceAtLeast(0.0)
            i == index && start != null -> maxOf(estimate(step), coveredM - start)
            else -> estimate(step)
        }
    }.toMutableList()

    if (plannedM != null && plannedM > 0) {
        val known = lengths.take(index + 1).sum()
        val ahead = lengths.drop(index + 1).sum()
        if (ahead > 0 && plannedM > known) {
            val fit = ((plannedM - known) / ahead).coerceIn(MIN_FIT, MAX_FIT)
            for (i in index + 1 until lengths.size) lengths[i] *= fit
        }
    }

    var at = 0.0
    return steps.indices.mapNotNull { i ->
        val length = lengths[i]
        if (length <= 0.0) return@mapNotNull null
        DistanceSegment(at, at + length, steps[i]).also { at += length }
    }
}

// ── Pace, against a target ───────────────────────────────────────────────────

/**
 * What the pace dial centres on: a pace in seconds per kilometre, and how far
 * either side of it still counts as on target.
 *
 * [zone] is the plan's zone name when the target is the plan's; null when there
 * was none and the dial fell back to the runner's own average.
 */
data class PaceTarget(val secPerKm: Double, val toleranceSec: Double, val zone: String?)

/**
 * The step's target pace, the watch's way: the zone's pace from the plan's VDOT,
 * plus or minus [WATCH_TOLERANCE_SEC] — the same window `WorkoutFit` writes into
 * the workout a watch coaches to, so phone and wrist agree about "on pace".
 *
 * With no zone or no VDOT, the run's average stands in, with a window that
 * scales with it, so the dial still answers "am I holding it?". Null while
 * there is neither.
 */
fun paceTarget(zone: String?, paces: Map<String, Double>?, averageMps: Double): PaceTarget? {
    zone?.let { paces?.get(it) }?.takeIf { it > 0 }?.let {
        return PaceTarget(it, WATCH_TOLERANCE_SEC, zone)
    }
    if (averageMps < MIN_SPEED) return null
    val avg = 1000.0 / averageMps
    return PaceTarget(avg, avg * AVERAGE_TOLERANCE, null)
}

/**
 * How far ahead of the target pace a speed is, in seconds per kilometre:
 * positive is faster, negative slower. Measured in pace rather than speed so
 * the dial is symmetric — ten seconds slow sits as far left as ten seconds fast
 * sits right, which in speed it would not.
 */
fun paceOffset(target: PaceTarget, speedMps: Double): Double? {
    if (speedMps < MIN_SPEED) return null
    return target.secPerKm - 1000.0 / speedMps
}

/** Half the pace dial's width: three tolerances, so the green band is its middle third. */
fun paceReach(target: PaceTarget): Double = target.toleranceSec * 3

// ── Climb, up against down ───────────────────────────────────────────────────

/**
 * The climb dial's scale: what a full half of the dial means, in metres — the
 * same for both halves, which is the point of setting ascent and descent side
 * by side.
 *
 * Set by whichever of the two is larger, so the larger fills at most
 * [CLIMB_FILL] of its half and the smaller is drawn honestly against it — 300 m
 * up and 30 m down should look like a hill climb, not two half-full arcs. Rounded
 * up to a step of 1, 1.5, 2, 2.5, 3, 4, 5, 6 or 8 per decade, so the scale
 * holds still while the numbers creep rather than rescaling with every metre,
 * and the larger arc always fills between about half and four fifths.
 * Never under [MIN_CLIMB_SCALE_M], so a flat run's first few metres of GPS
 * noise do not fill the dial.
 */
fun climbScaleM(ascentM: Double, descentM: Double): Double {
    val need = maxOf(ascentM, descentM, 0.0) / CLIMB_FILL
    if (need <= MIN_CLIMB_SCALE_M) return MIN_CLIMB_SCALE_M
    val decade = 10.0.pow(floor(log10(need)))
    val step = NICE_STEPS.first { it * decade >= need - 1e-9 }
    return step * decade
}

/** Below this a phone is standing still, and a pace would be a large number of nothing. */
const val MIN_SPEED = 0.1

private const val FIVE_K = 5000.0
private const val PACE_SPREAD = 0.25

/** 6:00 per km: an estimate's speed before the run has an average of its own. */
private const val DEFAULT_SPEED = 1000.0 / 360.0
/** A recovery with no zone, as a fraction of the runner's average: a slow jog. */
private const val REST_FRACTION = 0.6
private const val MIN_FIT = 0.5
private const val MAX_FIT = 2.0

/** `WorkoutFit.paceTargets`' default margin. */
const val WATCH_TOLERANCE_SEC = 10.0
/** On target against your own average: within 4%, about 12 s/km at 5:00. */
private const val AVERAGE_TOLERANCE = 0.04

private const val CLIMB_FILL = 0.8
private const val MIN_CLIMB_SCALE_M = 20.0
private val NICE_STEPS = listOf(1.0, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0)
