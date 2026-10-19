// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tracks.app.ui.body.MusclePicker
import com.tracks.app.ui.components.BarPill
import com.tracks.app.ui.components.NeutralButton
import com.tracks.app.ui.components.PrimaryButton
import com.tracks.app.ui.profile.SegmentedChoice
import com.tracks.core.strengthplan.QuickSession

/**
 * What can be started by hand, and how each kind starts.
 *
 * - **Recorded** sports move across the ground or water, so they are recorded
 *   with GPS (the guided run screen), with an optional distance or time target
 *   that becomes the one stage to aim for.
 * - **Built** sessions -- strength and flexibility -- are made on the spot from
 *   a length, an intensity and, if wanted, muscles to favour (QuickSession).
 * - **Timed** ones have nothing to measure but time (and heart rate when the
 *   watch streams it): one open stage, saved as an activity in their sport.
 */
enum class StartKind(val sport: String, val label: String, val group: Group) {
    Run("running", "Run", Group.Recorded),
    Walk("walking", "Walk", Group.Recorded),
    Hike("hiking", "Hike", Group.Recorded),
    Ride("cycling", "Ride", Group.Recorded),
    Mtb("mountain_biking", "MTB", Group.Recorded),
    XcSki("cross_country_skiing", "XC ski", Group.Recorded),
    Row("rowing", "Row", Group.Recorded),
    Paddle("kayaking", "Paddle", Group.Recorded),
    Strength("strength_training", "Strength", Group.Built),
    Flexibility("flexibility_training", "Flexibility", Group.Built),
    Yoga("yoga", "Yoga", Group.Timed),
    Climbing("rock_climbing", "Climbing", Group.Timed),
    Other("generic", "Other", Group.Timed);

    enum class Group { Recorded, Built, Timed }
}

/** What the sheet asks for once a kind is picked. */
data class StartChoice(
    val kind: StartKind,
    val targetKm: Double? = null,
    val targetMinutes: Int? = null,
    val minutes: Int = 30,
    val intensity: QuickSession.Intensity = QuickSession.Intensity.Medium,
    val emphasis: Set<String> = emptySet(),
)

/**
 * The dashboard's "Start activity": pick what, then how much, then go.
 *
 * Every control a kind does not use is absent, so a run asks only for an
 * optional target and a flexibility flow only for its length, intensity and
 * muscles. [onStart] builds the workout (the caller has the libraries) and
 * opens it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StartActivitySheet(onDismiss: () -> Unit, onStart: (StartChoice) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var kind by remember { mutableStateOf<StartKind?>(null) }
    var target by remember { mutableStateOf("none") }
    var distanceKm by remember { mutableFloatStateOf(5f) }
    var targetMin by remember { mutableFloatStateOf(30f) }
    var minutes by remember { mutableFloatStateOf(30f) }
    var intensity by remember { mutableStateOf(QuickSession.Intensity.Medium) }
    var emphasis by remember { mutableStateOf(emptySet<String>()) }
    var picking by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Start an activity", style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StartKind.entries.forEach { k ->
                    BarPill(text = k.label, selected = k == kind, onClick = { kind = k })
                }
            }
            val k = kind
            when (k?.group) {
                StartKind.Group.Recorded -> {
                    Text("Target (optional)", style = MaterialTheme.typography.labelLarge)
                    SegmentedChoice(
                        options = listOf("none" to "None", "distance" to "Distance", "time" to "Time"),
                        selected = target,
                        onSelect = { target = it },
                    )
                    if (target == "distance") {
                        Text("${"%.1f".format(distanceKm)} km", style = MaterialTheme.typography.titleMedium)
                        Slider(distanceKm, { distanceKm = (it * 2).toInt() / 2f }, valueRange = 0.5f..(if (k == StartKind.Ride || k == StartKind.Mtb) 200f else 50f))
                    }
                    if (target == "time") {
                        Text("${targetMin.toInt()} min", style = MaterialTheme.typography.titleMedium)
                        Slider(targetMin, { targetMin = (it / 5).toInt() * 5f }, valueRange = 5f..300f)
                    }
                }
                StartKind.Group.Built -> {
                    Text("Length: ${minutes.toInt()} min", style = MaterialTheme.typography.labelLarge)
                    Slider(minutes, { minutes = ((it / 5).toInt() * 5).coerceIn(5, 90).toFloat() }, valueRange = 5f..90f, steps = 16)
                    Text("Intensity", style = MaterialTheme.typography.labelLarge)
                    val words = if (k == StartKind.Flexibility) listOf("Gentle", "Moderate", "Deep") else listOf("Light", "Moderate", "Hard")
                    SegmentedChoice(
                        options = QuickSession.Intensity.entries.mapIndexed { i, it -> it.name to words[i] },
                        selected = intensity.name,
                        onSelect = { intensity = QuickSession.Intensity.valueOf(it) },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NeutralButton(if (picking) "Hide muscles" else "Focus on muscles", onClick = { picking = !picking }, small = true)
                        Text(
                            if (emphasis.isEmpty()) "Optional: a balanced full-body session"
                            else "${emphasis.size} chosen, about 2 in 5 picks",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (picking) MusclePicker(selected = emphasis, onSelectedChange = { emphasis = it })
                }
                StartKind.Group.Timed -> Text(
                    "Timed, with your heart rate when the watch streams it. Saved as an activity when you finish.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                null -> Unit
            }
            PrimaryButton(
                k?.let { "Start ${it.label.lowercase()}" } ?: "Pick an activity",
                enabled = k != null,
                onClick = {
                    val chosen = k ?: return@PrimaryButton
                    onStart(
                        StartChoice(
                            kind = chosen,
                            targetKm = distanceKm.toDouble().takeIf { target == "distance" && chosen.group == StartKind.Group.Recorded },
                            targetMinutes = targetMin.toInt().takeIf { target == "time" && chosen.group == StartKind.Group.Recorded },
                            minutes = minutes.toInt(),
                            intensity = intensity,
                            emphasis = emphasis,
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            )
        }
    }
}
