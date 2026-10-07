// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.workout

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tracks.app.run.RunUiState
import com.tracks.app.run.clock
import com.tracks.app.ui.dashboard.GaugeZone
import com.tracks.app.ui.dashboard.RadialGauge
import com.tracks.app.ui.health.HealthCard
import com.tracks.core.format.distance
import com.tracks.core.format.elevation
import com.tracks.core.format.pace
import com.tracks.core.format.speed

/**
 * The run, as dials — the Health page's instrument, so the two screens read as
 * one app.
 *
 * ## Two sizes, on purpose
 *
 * A grid of equal numbers made the runner hunt for the two that matter while
 * moving. Those two are the large dials at the top, side by side: how far they
 * have gone, and whether they are on pace. Everything else — the section's
 * countdown, time, climb — is a row of the Health page's small dials below.
 *
 * Pace is the big one rather than the section's countdown, which it used to
 * be. The countdown is spoken at every change (RunCues) and changes slowly;
 * pace is the number a runner corrects stride by stride, and the one a glance
 * has to answer.
 *
 * ## Each dial is the watch's dial
 *
 * - **Distance** is cut into the plan's sections, so the arc says where the
 *   warm-up ends and each rep begins, and the fill crossing a gap is the run
 *   crossing into the next one ([distanceSegments]).
 * - **Pace** is Garmin's pace gauge: green in the middle at the section's target,
 *   slower to the left, faster to the right, a needle rather than a fill
 *   ([paceTarget]).
 * - **Climb** is Garmin's elevation gauge: ascent fills the left half, descent
 *   the right, on one scale so the two can be compared ([climbScaleM]).
 *
 * Sized from the width it is given, like the Health grid: two large dials fill
 * a row on any phone, and the small ones drop from three across to two where
 * three would be unreadable. Capped, so a tablet does not get dinner plates.
 */
@Composable
fun RunGauges(state: GuidedUiState, run: RunUiState) {
    val planned = state.workout?.distanceMeters?.takeIf { it > 0 }
    val plannedMs = state.workout?.durationMinutes?.takeIf { it > 0 }?.let { it * 60_000L }
    val averageMps = if (run.runningMs > 0) run.distanceM / (run.runningMs / 1000.0) else 0.0
    val structured = state.steps.size > 1
    val step = state.step
    val target = paceTarget(step?.paceZone, state.paces, averageMps)
    val current = run.speedMps.takeIf { it > MIN_SPEED }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HealthCard {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val big = ((maxWidth - GAP) / 2 - SLACK).coerceAtMost(BIG_MAX)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                    DistanceDial(state, run, planned, averageMps, big, Modifier.weight(1f))
                    PaceDial(current, target, "Pace", big, BIG_STROKE, MaterialTheme.typography.headlineMedium, Modifier.weight(1f))
                }
            }
            // What comes after this section — the one line of the old step
            // strip a dial cannot carry.
            if (structured) state.next?.let {
                Text(
                    "Next: ${listOfNotNull(it.title, it.position, it.detail).joinToString(" · ")}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        HealthCard {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = if (maxWidth < THREE_UP_MIN) 2 else 3
                val small = ((maxWidth - GAP * (columns - 1)) / columns - SLACK).coerceAtMost(SMALL_MAX)
                val timeScale = (plannedMs ?: HOUR_MS).toDouble()
                // The section's own pace on a structured run — a rep's pace is
                // the number a rep is judged by, and the whole run's average
                // has the warm-up in it. A steady run has one section, so
                // there the two are the same thing and it says so.
                val lapMps = if (state.stepMs > 0) state.stepCoveredM / (state.stepMs / 1000.0) else 0.0
                val dials = buildList<@Composable (Modifier) -> Unit> {
                    when {
                        structured && step != null -> add { m -> StepDial(state, step, small, m) }
                        planned != null -> add { m -> ToGoDial(run, planned, small, m) }
                    }
                    add { m ->
                        if (structured) {
                            PaceDial(lapMps.takeIf { it > MIN_SPEED }, target, "Lap pace", small, SMALL_STROKE, MaterialTheme.typography.titleMedium, m)
                        } else {
                            PaceDial(averageMps.takeIf { it > MIN_SPEED }, target, "Avg pace", small, SMALL_STROKE, MaterialTheme.typography.titleMedium, m)
                        }
                    }
                    add { m ->
                        SmallDial(run.movingMs.toDouble(), single(timeScale, TIME_COLOR, "Moving"),
                            clock(run.movingMs), "Moving time", small, m)
                    }
                    add { m -> SmallDial(current, speedZones(averageMps), speed(current), "Speed", small, m) }
                    add { m -> ClimbDial(run.ascentM, run.descentM, small, m) }
                    add { m ->
                        SmallDial(run.elapsedMs.toDouble(), single(timeScale, TIME_COLOR.copy(alpha = 0.7f), "Total"),
                            clock(run.elapsedMs), "Total time", small, m)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    dials.chunked(columns).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                            row.forEach { dial -> dial(Modifier.weight(1f)) }
                            // A short last row keeps its columns aligned with the rows above.
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Distance run, against the plan cut into its sections.
 *
 * One band per section, coloured by what it is, laid end to end — see
 * [distanceSegments] for where each length comes from. A run with one section
 * has nothing to cut, and keeps the plain "started, halfway, last stretch" scale.
 */
@Composable
private fun DistanceDial(state: GuidedUiState, run: RunUiState, planned: Double?, averageMps: Double, size: Dp, modifier: Modifier) {
    val segments = if (state.steps.size > 1) {
        distanceSegments(state.steps, state.index, state.stepStartsM, run.distanceM, planned, state.paces, averageMps)
    } else {
        emptyList()
    }
    val zones = if (segments.isNotEmpty()) {
        segments.map { GaugeZone(it.step.title, it.fromM, it.toM, kindColor(it.step.kind)) }
    } else {
        val scale = distanceScaleM(planned, run.distanceM)
        listOf(
            GaugeZone("Started", 0.0, scale * 0.5, BLUE),
            GaugeZone("Halfway", scale * 0.5, scale * 0.9, TEAL),
            GaugeZone("Last stretch", scale * 0.9, scale, GREEN),
        )
    }
    // The plan's figure when it has one; otherwise the sections' own sum,
    // marked as the estimate it is.
    val of = planned?.let { "of ${distance(it)}" }
        ?: segments.lastOrNull()?.let { "of ~${distance(it.toM)}" }
    RadialGauge(
        value = run.distanceM,
        zones = zones,
        label = "Distance",
        figure = distance(run.distanceM),
        // Coloured by the section the run is in, so the caption doubles as
        // the legend for whichever band the fill has reached.
        caption = of,
        figureStyle = MaterialTheme.typography.headlineMedium,
        size = size,
        stroke = BIG_STROKE,
        modifier = modifier,
    )
}

/**
 * A pace against its target, Garmin's way: a needle on a scale whose green
 * middle is the target, slow to the left and fast to the right.
 *
 * The scale is in seconds per kilometre either side of the target, so it is
 * symmetric ([paceOffset]), three tolerances wide each way ([paceReach]). The
 * figure is the pace itself; the caption is the target it is being held to,
 * coloured by the band the needle is in — so "4:30 target" goes green the
 * moment the runner is on it.
 */
@Composable
private fun PaceDial(
    mps: Double?,
    target: PaceTarget?,
    label: String,
    size: Dp,
    stroke: Dp,
    figureStyle: androidx.compose.ui.text.TextStyle,
    modifier: Modifier,
) {
    if (target == null) {
        // No plan pace and no average yet — the first seconds of a run. A
        // needle with nothing to point against would be a guess.
        RadialGauge(
            value = null,
            zones = listOf(GaugeZone(label, 0.0, 1.0, SLATE)),
            label = label,
            figure = pace(mps, isRunning = true).takeIf { mps != null },
            figureStyle = figureStyle,
            size = size,
            stroke = stroke,
            modifier = modifier,
        )
        return
    }
    val tolerance = target.toleranceSec
    val reach = paceReach(target)
    val targetText = pace(1000.0 / target.secPerKm, isRunning = true)
    RadialGauge(
        value = mps?.let { paceOffset(target, it) },
        zones = listOf(
            GaugeZone("Too slow", -reach, -tolerance * 2, BLUE_DEEP),
            GaugeZone("Slow", -tolerance * 2, -tolerance, BLUE),
            GaugeZone("On target", -tolerance, tolerance, GREEN),
            GaugeZone("Fast", tolerance, tolerance * 2, AMBER),
            GaugeZone("Too fast", tolerance * 2, reach, RED),
        ),
        // The zone in the label, because "Threshold" is what the plan calls it
        // and the clock-face pace means nothing without it.
        // Only on the big dial: a small one's label has no room for it, and
        // the big one beside it already says which zone this is.
        label = if (stroke == BIG_STROKE) target.zone?.let { "$label · ${zoneName(it)}" } ?: "$label · vs avg" else label,
        figure = pace(mps, isRunning = true),
        caption = if (target.zone != null) "$targetText target" else "$targetText avg",
        figureStyle = figureStyle,
        size = size,
        stroke = stroke,
        needle = true,
        modifier = modifier,
    )
}

/** The section the run is on, counting down in its own unit, the arc showing how much is done. */
@Composable
private fun StepDial(state: GuidedUiState, step: GuidedStep, size: Dp, modifier: Modifier) {
    val done = stepDone(step, state.stepCoveredM, state.remaining)
    val left = when {
        step.metres != null -> distance((step.metres - state.stepCoveredM).coerceAtLeast(0.0))
        step.seconds != null -> countdown(state.remaining)
        else -> step.detail ?: "Open"
    }
    val word = kindWord(step.kind)
    RadialGauge(
        value = done ?: 0.0,
        zones = listOf(GaugeZone(word, 0.0, 1.0, kindColor(step.kind))),
        label = listOfNotNull(step.title, step.position).joinToString(" · "),
        figure = left,
        caption = if (step.metres != null || step.seconds != null) "left · $word" else word,
        captionColor = kindColor(step.kind),
        figureStyle = MaterialTheme.typography.titleMedium,
        size = size,
        stroke = SMALL_STROKE,
        modifier = modifier,
    )
}

/** An unstructured run's countdown: what is left of the plan's distance. */
@Composable
private fun ToGoDial(run: RunUiState, planned: Double, size: Dp, modifier: Modifier) {
    RadialGauge(
        value = run.distanceM.coerceAtMost(planned),
        zones = listOf(GaugeZone("To go", 0.0, planned, TEAL)),
        label = "To go",
        figure = distance((planned - run.distanceM).coerceAtLeast(0.0)),
        caption = "left",
        figureStyle = MaterialTheme.typography.titleMedium,
        size = size,
        stroke = SMALL_STROKE,
        modifier = modifier,
    )
}

/**
 * Up and down, Garmin's way: ascent fills the left half of the arc from its
 * bottom end, descent the right half from its own, both rising toward the top.
 *
 * One scale for both halves ([climbScaleM]), which is what makes the dial worth
 * having — the figures say how much, the two arcs side by side say whether this
 * was a climb, a descent or a loop. Drawn here rather than by [RadialGauge]
 * because it is two gauges meeting at the top, not one with bands; the
 * geometry (start, span, reserved lap room) is the same so it sits level with
 * the dials around it.
 */
@Composable
private fun ClimbDial(ascentM: Double, descentM: Double, size: Dp, modifier: Modifier) {
    val scale = climbScaleM(ascentM, descentM)
    val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(size)) {
                val width = SMALL_STROKE.toPx()
                val style = Stroke(width = width, cap = StrokeCap.Butt)
                // RadialGauge holds back room for its lap ring; matching it
                // keeps this arc the same diameter as its neighbours'.
                val inset = width * LAP_STROKE + LAP_GAP.toPx() + width / 2f
                val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                val arc = androidx.compose.ui.geometry.Size(this.size.width - inset * 2, this.size.height - inset * 2)
                val half = SPAN / 2 - SPLIT_GAP

                fun sweep(metres: Double): Float {
                    if (metres <= 0.0) return 0f
                    // A sliver at least, so 4 m of descent on a hill climb is
                    // visibly not nothing.
                    return ((metres / scale).toFloat().coerceIn(0f, 1f) * half).coerceAtLeast(MIN_SWEEP)
                }

                // The two halves' tracks: the climb's from 7.5 o'clock up to
                // the top, the descent's from 4.5 o'clock up to the top.
                drawArc(track, START, half, false, topLeft, arc, style = style)
                drawArc(track, START + SPAN - half, half, false, topLeft, arc, style = style)

                drawArc(CLIMB_COLOR, START, sweep(ascentM), false, topLeft, arc, style = style)
                val down = sweep(descentM)
                drawArc(DESCENT_COLOR, START + SPAN - down, down, false, topLeft, arc, style = style)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "↑ ${elevation(ascentM)}",
                    style = MaterialTheme.typography.titleSmall,
                    color = CLIMB_COLOR,
                    maxLines = 1,
                )
                Text(
                    "↓ ${elevation(descentM)}",
                    style = MaterialTheme.typography.titleSmall,
                    color = DESCENT_COLOR,
                    maxLines = 1,
                )
            }
        }
        Text(
            "Climb",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun SmallDial(value: Double?, zones: List<GaugeZone>, figure: String, label: String, size: Dp, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        RadialGauge(
            value = value,
            zones = zones,
            label = label,
            figure = figure.takeIf { value != null },
            figureStyle = MaterialTheme.typography.titleMedium,
            size = size,
            stroke = SMALL_STROKE,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Current speed against the run's average: slower, steady, faster. */
private fun speedZones(averageMps: Double): List<GaugeZone> {
    val (lo, hi) = paceScale(averageMps) ?: return listOf(GaugeZone("Speed", 0.0, 5.0, SLATE))
    return listOf(
        GaugeZone("Slower", lo, averageMps * 0.95, ORANGE),
        GaugeZone("Steady", averageMps * 0.95, averageMps * 1.05, GREEN),
        GaugeZone("Faster", averageMps * 1.05, hi, TEAL),
    )
}

/** One band to [top]: a reading with no verdict, only a size. */
private fun single(top: Double, color: Color, label: String) = listOf(GaugeZone(label, 0.0, top, color))

internal fun kindColor(kind: StepKind): Color = when (kind) {
    StepKind.Warmup -> AMBER
    StepKind.Work -> ORANGE
    StepKind.Rest -> TEAL
    StepKind.Cooldown -> BLUE
}

private fun kindWord(kind: StepKind): String = when (kind) {
    StepKind.Warmup -> "Warm-up"
    StepKind.Work -> "Work"
    StepKind.Rest -> "Recover"
    StepKind.Cooldown -> "Cool-down"
}

/** The plan's zone names, as a runner says them. */
private fun zoneName(zone: String): String = when (zone) {
    "marathon" -> "Marathon"
    "repetition" -> "Rep"
    else -> zone.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

// The Health page's palette (HealthMeters.kt), so a run's dials and the body's share colours.
private val TEAL = Color(0xFF14B8A6)
private val GREEN = Color(0xFF22C55E)
private val AMBER = Color(0xFFEAB308)
private val ORANGE = Color(0xFFF97316)
private val RED = Color(0xFFEF4444)
private val BLUE = Color(0xFF38BDF8)
private val BLUE_DEEP = Color(0xFF3B82F6)
private val SLATE = Color(0xFF94A3B8)
private val TIME_COLOR = Color(0xFF6366F1)
private val CLIMB_COLOR = Color(0xFFA855F7)
private val DESCENT_COLOR = Color(0xFF38BDF8)

private const val HOUR_MS = 3_600_000L

/** The headline pair: as large as half the row allows, up to a size that still reads as a pair. */
private val BIG_MAX = 210.dp
private val BIG_STROKE = 14.dp

/** Matches the Health grid's cap and stroke. */
private val SMALL_MAX = 124.dp
private val SMALL_STROKE = 9.dp

private val GAP = 8.dp
/** Rounding headroom, as on the Health grid, so a full row never wraps by a pixel. */
private val SLACK = 2.dp
/** Below this, three small dials stop being legible and two go across instead. */
private val THREE_UP_MIN = 270.dp

// RadialGauge's geometry (Charts.kt), repeated for the climb dial so the two
// draw the same circle.
private const val START = 135f
private const val SPAN = 270f
private const val LAP_STROKE = 0.45f
private val LAP_GAP = 2.dp
/** Half a band gap each side of the top, where ascent meets descent. */
private const val SPLIT_GAP = 1.5f
private const val MIN_SWEEP = 2f
