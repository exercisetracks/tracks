// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.workout

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracks.app.run.RunPhase
import com.tracks.app.run.RunRecorder
import com.tracks.app.run.clock
import com.tracks.core.format.distance

/**
 * The way back to a workout in progress, across the top of every page.
 *
 * Leaving the workout screen mid-session is normal — checking the map at a
 * junction, glancing at the plan between sets — and the way back used to be
 * the dashboard's workout card, which is three taps and assumes you remember
 * where you started it. With a run still recording and the phone in a hand
 * that is also holding a water bottle, it should be one tap from anywhere.
 *
 * Shown on every page but the map, which has no chrome by design (see
 * [com.tracks.app.ui.TracksNavHost]); the run's notification is the way back
 * from there. Says what state the session is in, because "paused" is the
 * thing most worth noticing when you have wandered off: a paused run left in a
 * pocket is the one that gets forgotten.
 */
@Composable
fun ActiveWorkoutBar(session: ActiveWorkout.Session, state: GuidedUiState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val run by RunRecorder.state.collectAsStateWithLifecycle()

    val (status, color, live) = when {
        state.isRecorded -> when (run.phase) {
            RunPhase.Recording -> Triple("Recording", GREEN, true)
            RunPhase.Paused -> Triple("Paused", AMBER, false)
            RunPhase.Finished -> Triple("Finished — tap to save", BLUE, false)
            RunPhase.Idle -> Triple("Starting", BLUE, false)
        }
        state.done -> Triple("Finished — tap to log", BLUE, false)
        state.running -> Triple("In progress", GREEN, true)
        else -> Triple("Paused", AMBER, false)
    }
    val detail = when {
        state.isRecorded && run.phase != RunPhase.Finished ->
            listOf(distance(run.distanceM).takeIf { run.distanceM > 0 }, clock(run.movingMs))
        state.isRecorded -> emptyList()
        state.done -> emptyList()
        else -> listOf("Step ${state.index + 1} of ${state.steps.size}", state.step?.title)
    }.filterNotNull()

    Surface(
        modifier.fillMaxWidth().clickable(onClick = onOpen),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusDot(color, pulsing = live)
            Column(Modifier.weight(1f)) {
                Text(
                    session.title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    (listOf(status) + detail).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "Back to the workout")
        }
    }
}

/** Breathing while the session runs, still while it waits — readable from the corner of an eye. */
@Composable
private fun StatusDot(color: Color, pulsing: Boolean) {
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "workout-dot")
        val value by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "workout-dot-alpha",
        )
        value
    } else {
        1f
    }
    Box(
        Modifier
            .size(10.dp)
            .alpha(alpha)
            .background(color, CircleShape),
    )
}

private val GREEN = Color(0xFF22C55E)
private val AMBER = Color(0xFFEAB308)
private val BLUE = Color(0xFF38BDF8)
