// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import kotlin.math.ceil

/**
 * The run, as dials — the Health page's instrument, so the two screens read as
 * one app.
 *
 * ## Two sizes, on purpose
 *
 * A grid of equal numbers made the runner hunt for the two that matter while
 * moving: how far they have gone, and how much of *this* section is left. Those
 * two are the large dials at the top, side by side; everything else — pace,
 * time, climb — is a row of the Health page's small dials below. At a glance
 * the eye lands on the big two, which is the whole point of a screen read at
 * arm's length mid-stride.
 *
 * ## Scales from the run itself
 *
 * A distance has no "good" band the way resting heart rate does, so each arc is
 * measured against something the run already has: the plan's distance, the
 * step's target, the runner's own average pace (see RunGaugeMath.kt).
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

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HealthCard {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val big = ((maxWidth - GAP) / 2 - SLACK).coerceAtMost(BIG_MAX)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                    DistanceDial(run, planned, big, Modifier.weight(1f))
                    val step = state.step
                    if (structured && step != null) {
                        StepDial(state, step, big, Modifier.weight(1f))
                    } else {
                        ToGoDial(run, planned, plannedMs, big, Modifier.weight(1f))
                    }
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
                val paceZones = paceZones(averageMps)
                val current = run.speedMps.takeIf { it > MIN_SPEED }
                val timeScale = (plannedMs ?: HOUR_MS).toDouble()
                val dials: List<@Composable (Modifier) -> Unit> = listOf(
                    { m -> SmallDial(current, paceZones, pace(current, isRunning = true), "Pace", small, m) },
                    { m ->
                        SmallDial(averageMps.takeIf { it > MIN_SPEED }, paceZones,
                            pace(averageMps.takeIf { it > MIN_SPEED }, isRunning = true), "Avg pace", small, m)
                    },
                    { m ->
                        SmallDial(run.movingMs.toDouble(), single(timeScale, TIME_COLOR, "Moving"),
                            clock(run.movingMs), "Moving time", small, m)
                    },
                    { m -> SmallDial(current, paceZones, speed(current), "Speed", small, m) },
                    { m ->
                        val top = ceil((run.ascentM + 1) / 100.0).coerceAtLeast(1.0) * 100.0
                        SmallDial(run.ascentM, single(top, CLIMB_COLOR, "Climb"), elevation(run.ascentM), "Climb", small, m)
                    },
                    { m ->
                        SmallDial(run.elapsedMs.toDouble(), single(timeScale, TIME_COLOR.copy(alpha = 0.7f), "Total"),
                            clock(run.elapsedMs), "Total time", small, m)
                    },
                )
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

/** Distance run, filling toward the plan's distance (or the next 5 km with none). */
@Composable
private fun DistanceDial(run: RunUiState, planned: Double?, size: Dp, modifier: Modifier) {
    val scale = distanceScaleM(planned, run.distanceM)
    RadialGauge(
        value = run.distanceM,
        zones = listOf(
            GaugeZone("Started", 0.0, scale * 0.5, BLUE),
            GaugeZone("Halfway", scale * 0.5, scale * 0.9, TEAL),
            GaugeZone("Last stretch", scale * 0.9, scale, GREEN),
        ),
        label = "Distance",
        figure = distance(run.distanceM),
        caption = planned?.let { "of ${distance(it)}" },
        figureStyle = MaterialTheme.typography.headlineMedium,
        size = size,
        stroke = BIG_STROKE,
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
    val (word, color) = when (step.kind) {
        StepKind.Warmup -> "Warm-up" to AMBER
        StepKind.Work -> "Work" to ORANGE
        StepKind.Rest -> "Recover" to TEAL
        StepKind.Cooldown -> "Cool-down" to BLUE
    }
    RadialGauge(
        value = done ?: 0.0,
        zones = listOf(GaugeZone(word, 0.0, 1.0, color)),
        label = listOfNotNull(step.title, step.position).joinToString(" · "),
        figure = left,
        caption = if (step.metres != null || step.seconds != null) "left · $word" else word,
        captionColor = color,
        figureStyle = MaterialTheme.typography.headlineMedium,
        size = size,
        stroke = BIG_STROKE,
        modifier = modifier,
    )
}

/** An unstructured run's second headline: what is left of the plan, or the clock with no plan. */
@Composable
private fun ToGoDial(run: RunUiState, planned: Double?, plannedMs: Long?, size: Dp, modifier: Modifier) {
    if (planned != null) {
        RadialGauge(
            value = run.distanceM.coerceAtMost(planned),
            zones = listOf(GaugeZone("To go", 0.0, planned, TEAL)),
            label = "To go",
            figure = distance((planned - run.distanceM).coerceAtLeast(0.0)),
            caption = "left",
            figureStyle = MaterialTheme.typography.headlineMedium,
            size = size,
            stroke = BIG_STROKE,
            modifier = modifier,
        )
    } else {
        RadialGauge(
            value = run.movingMs.toDouble(),
            zones = single((plannedMs ?: HOUR_MS).toDouble(), TIME_COLOR, "Moving"),
            label = "Moving time",
            figure = clock(run.movingMs),
            figureStyle = MaterialTheme.typography.headlineMedium,
            size = size,
            stroke = BIG_STROKE,
            modifier = modifier,
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
private fun paceZones(averageMps: Double): List<GaugeZone> {
    val (lo, hi) = paceScale(averageMps) ?: return listOf(GaugeZone("Pace", 0.0, 5.0, SLATE))
    return listOf(
        GaugeZone("Slower", lo, averageMps * 0.95, ORANGE),
        GaugeZone("Steady", averageMps * 0.95, averageMps * 1.05, GREEN),
        GaugeZone("Faster", averageMps * 1.05, hi, TEAL),
    )
}

/** One band to [top]: a reading with no verdict, only a size. */
private fun single(top: Double, color: Color, label: String) = listOf(GaugeZone(label, 0.0, top, color))

// The Health page's palette (HealthMeters.kt), so a run's dials and the body's share colours.
private val TEAL = Color(0xFF14B8A6)
private val GREEN = Color(0xFF22C55E)
private val AMBER = Color(0xFFEAB308)
private val ORANGE = Color(0xFFF97316)
private val BLUE = Color(0xFF38BDF8)
private val SLATE = Color(0xFF94A3B8)
private val TIME_COLOR = Color(0xFF6366F1)
private val CLIMB_COLOR = Color(0xFFA855F7)

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
