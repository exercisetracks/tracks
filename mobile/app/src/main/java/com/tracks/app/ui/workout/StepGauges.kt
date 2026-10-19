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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracks.app.ui.dashboard.GaugeZone
import com.tracks.app.ui.dashboard.RadialGauge
import com.tracks.app.ui.health.HEART_RATE_ZONES
import com.tracks.app.ui.health.HealthCard
import com.tracks.device.garmin.live.LiveHeartRate

/**
 * A strength, mobility or other guided session's instruments -- the run
 * screen's gauge cluster for a session with no distance or pace.
 *
 * Large: the step on now (time or reps left, its arc how much is done) and the
 * whole session's progress. Small: the set within its exercise, time spent,
 * time left, and heart rate whenever the watch is streaming one.
 */
@Composable
fun StepGauges(state: GuidedUiState) {
    val step = state.step ?: return
    val live by LiveHeartRate.reading.collectAsStateWithLifecycle()
    val total = state.steps.size.coerceAtLeast(1)
    val doneSteps = state.index
    val left = remainingSeconds(state)

    HealthCard {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val big = ((maxWidth - 12.dp) / 2 - 4.dp).coerceAtMost(200.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val timed = step.seconds
                RadialGauge(
                    value = if (timed != null) (timed - state.remaining).toDouble() else 0.0,
                    zones = listOf(GaugeZone(step.title, 0.0, (timed ?: 1).toDouble(), kindColor(step.kind))),
                    label = "This step",
                    figure = when {
                        timed != null -> countdown(state.remaining)
                        step.exercise?.reps != null -> "${step.exercise.reps} reps"
                        else -> step.detail ?: "Go"
                    },
                    caption = if (timed != null) "left" else step.detail,
                    captionColor = kindColor(step.kind),
                    figureStyle = MaterialTheme.typography.headlineMedium,
                    size = big,
                    stroke = 14.dp,
                    modifier = Modifier.weight(1f),
                )
                RadialGauge(
                    value = doneSteps.toDouble(),
                    zones = listOf(GaugeZone("Session", 0.0, total.toDouble(), SESSION_COLOR)),
                    label = "Session",
                    figure = "${state.index + 1} / $total",
                    caption = "steps",
                    figureStyle = MaterialTheme.typography.headlineMedium,
                    size = big,
                    stroke = 14.dp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val small = ((maxWidth - 24.dp) / 3 - 4.dp).coerceAtMost(120.dp)
            val bpm = live?.bpm
            val dials = buildList<@Composable (Modifier) -> Unit> {
                add { m ->
                    RadialGauge(
                        value = null, zones = listOf(GaugeZone("Set", 0.0, 1.0, kindColor(step.kind))),
                        label = if (setOf(step) == null && state.next?.let(::setOf) != null) "Next set" else "Set",
                        figure = setOf(step) ?: state.next?.let(::setOf) ?: "—", size = small, stroke = 9.dp,
                        figureStyle = MaterialTheme.typography.titleMedium, modifier = m,
                    )
                }
                add { m ->
                    RadialGauge(
                        value = state.elapsedS.toDouble(),
                        zones = listOf(GaugeZone("Elapsed", 0.0, (state.elapsedS + (left ?: 0)).coerceAtLeast(1).toDouble(), TIME)),
                        label = "Elapsed", figure = countdown(state.elapsedS), size = small, stroke = 9.dp,
                        figureStyle = MaterialTheme.typography.titleMedium, modifier = m,
                    )
                }
                add { m ->
                    RadialGauge(
                        value = null, zones = listOf(GaugeZone("Left", 0.0, 1.0, TIME.copy(alpha = 0.7f))),
                        label = "Left", figure = left?.let { "~${countdown(it)}" } ?: "—", size = small, stroke = 9.dp,
                        figureStyle = MaterialTheme.typography.titleMedium, modifier = m,
                    )
                }
                if (bpm != null) add { m ->
                    RadialGauge(
                        value = bpm.toDouble(), zones = HEART_RATE_ZONES,
                        label = "Heart rate", figure = "$bpm", caption = "bpm", size = small, stroke = 9.dp,
                        figureStyle = MaterialTheme.typography.titleMedium, modifier = m,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                dials.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { it(Modifier.weight(1f)) }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** "2 / 4" out of a step's position ("Set 2 of 4 · Left"), or null. */
private fun setOf(step: GuidedStep): String? =
    step.position?.let { Regex("""(\d+) of (\d+)""").find(it) }?.destructured?.let { (a, b) -> "$a / $b" }

/**
 * Roughly how long is left: the current step's countdown plus every later
 * timed step. Open steps (a set) have no clock and add nothing, so this is
 * a floor, which the "~" says.
 */
internal fun remainingSeconds(state: GuidedUiState): Int? {
    if (state.steps.isEmpty()) return null
    val later = state.steps.drop(state.index + 1).sumOf { it.seconds ?: 0 }
    return (if (state.step?.seconds != null) state.remaining else 0) + later
}

private val SESSION_COLOR = Color(0xFF14B8A6)
private val TIME = Color(0xFF38BDF8)
