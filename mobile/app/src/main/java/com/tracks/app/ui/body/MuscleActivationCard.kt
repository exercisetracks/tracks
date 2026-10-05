// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.body

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tracks.core.spec.computeMuscleActivation
import com.tracks.core.spec.muscleLabel
import com.tracks.core.api.StrengthSet as LoggedSet
import com.tracks.core.spec.StrengthSet as ActivationInput

/**
 * What a strength session actually worked, drawn on a body.
 *
 * The activation itself is computed here rather than fetched: the sets are
 * already on the phone for the card above this one, and
 * [computeMuscleActivation] is the same reduction the browser and the backend
 * run, kept honest by a shared fixture corpus. Asking the server for a number
 * it would derive from data we are already holding would add a request, a
 * loading state, and a way for the two to disagree.
 */
@Composable
fun MuscleActivationCard(sets: List<LoggedSet>, modifier: Modifier = Modifier) {
    val result = remember(sets) {
        computeMuscleActivation(
            sets.map {
                ActivationInput(
                    setType = it.setType,
                    exerciseCategory = it.exerciseCategory,
                    repetitions = it.repetitions,
                )
            }
        )
    }
    // Cardio and warm-up categories map to no muscles at all, so a session made
    // only of those produces an empty result. Drawing two grey bodies under a
    // "Muscles worked" heading would be a worse answer than not drawing them.
    if (result.activation.isEmpty()) return

    val activation = remember(result) {
        result.activation.mapValues { (_, v) -> v.toFloat() }
    }
    val worked = remember(result) {
        result.activation.entries.sortedByDescending { it.value }.take(TOP_MUSCLES)
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "MUSCLES WORKED",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BodySide("Front", BodyView.Front, activation, Modifier.weight(1f))
                    BodySide("Back", BodyView.Back, activation, Modifier.weight(1f))
                }
                Text(
                    // The diagram says where; this says which, because several
                    // muscles share one shape and the reader cannot name a
                    // colour. Ordered hardest first.
                    worked.joinToString(" · ") { muscleLabel(it.key) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BodySide(
    label: String,
    view: BodyView,
    activation: Map<String, Float>,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BodyDiagram(
            activation = activation,
            view = view,
            // The artwork is twice as tall as it is wide, and letting the
            // canvas pick its own height inside a Row gives it none at all.
            modifier = Modifier.fillMaxWidth().aspectRatio(BODY_ASPECT),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Enough to say what the session was, short enough to read at a glance. */
private const val TOP_MUSCLES = 5

/** 724 × 1448 in the source artwork. */
private const val BODY_ASPECT = 0.5f
