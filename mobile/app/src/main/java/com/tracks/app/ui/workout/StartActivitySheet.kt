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
    Climbing("rock_climbing", "Climbing", Group.Timed),
    Other("generic", "Other", Group.Timed);

    enum class Group { Recorded, Built, Timed }

    companion object {
        /**
         * Which kind an activity in the history counts as, for "Frequent".
         * Yoga and pilates are flexibility here: a flexibility session is
         * yoga, which is why yoga is no longer a kind of its own.
         */
        fun of(sport: String?, subSport: String?): StartKind? {
            val s = sport?.lowercase().orEmpty()
            val sub = subSport?.lowercase().orEmpty()
            return when {
                s == "running" -> Run
                s == "walking" -> Walk
                s == "hiking" -> Hike
                s == "cycling" || s == "mountain_biking" -> if (s == "mountain_biking" || sub == "mountain") Mtb else Ride
                s == "cross_country_skiing" -> XcSki
                s == "rowing" -> Row
                s in setOf("kayaking", "paddling", "stand_up_paddleboarding") -> Paddle
                s == "strength_training" || sub == "strength_training" -> Strength
                s in setOf("flexibility_training", "yoga", "pilates") ||
                    sub in setOf("flexibility_training", "yoga", "pilates") -> Flexibility
                s in setOf("rock_climbing", "climbing") || sub in setOf("bouldering", "indoor_climbing") -> Climbing
                else -> null
            }
        }

        /** The kinds done most, most first (at most [max]), from per-kind counts. */
        fun frequent(counts: Map<StartKind, Int>, max: Int = 4): List<StartKind> =
            counts.filterValues { it > 0 }.entries
                .sortedWith(compareByDescending<Map.Entry<StartKind, Int>> { it.value }.thenBy { it.key.ordinal })
                .take(max).map { it.key }
    }
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
fun StartActivitySheet(
    onDismiss: () -> Unit,
    onStart: (StartChoice) -> Unit,
    /** How often each kind appears in the history, for "Frequent" and the preselection. */
    counts: Map<StartKind, Int> = emptyMap(),
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val frequent = remember(counts) { StartKind.frequent(counts) }
    // The usual one, ready to go; a run for someone with no history.
    var kind by remember(frequent) { mutableStateOf<StartKind?>(frequent.firstOrNull() ?: StartKind.Run) }
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
            // What you usually do first, from your own history; then the rest.
            if (frequent.isNotEmpty()) {
                KindGroup("Frequent", frequent, kind) { kind = it }
                KindGroup("Other options", StartKind.entries - frequent.toSet(), kind) { kind = it }
            } else {
                KindGroup(null, StartKind.entries, kind) { kind = it }
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
                    val words = if (k == StartKind.Flexibility) FLEX_WORDS else STRENGTH_WORDS
                    val levels = QuickSession.Intensity.entries
                    Text("Intensity: ${words[intensity.ordinal]}", style = MaterialTheme.typography.labelLarge)
                    // Five steps on a slider rather than three buttons: finer,
                    // and one thumb-width control instead of a row of words.
                    Slider(
                        value = intensity.ordinal.toFloat(),
                        onValueChange = { intensity = levels[it.toInt().coerceIn(0, levels.lastIndex)] },
                        valueRange = 0f..levels.lastIndex.toFloat(),
                        steps = levels.size - 2,
                    )
                    NeutralButton(
                        if (picking) "Hide muscles" else if (emphasis.isEmpty()) "Focus on muscles?" else "Focus on muscles (${emphasis.size})",
                        onClick = { picking = !picking },
                        modifier = Modifier.fillMaxWidth(),
                    )
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

private val FLEX_WORDS = listOf("Very gentle", "Gentle", "Moderate", "Deep", "Very deep")
private val STRENGTH_WORDS = listOf("Very light", "Light", "Moderate", "Hard", "Very hard")

/** One group of kinds under its heading, as pills large enough to hit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KindGroup(title: String?, kinds: List<StartKind>, selected: StartKind?, onPick: (StartKind) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        title?.let {
            Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            kinds.forEach { k -> KindPill(k.label, k == selected) { onPick(k) } }
        }
    }
}

/**
 * The bar's pill, bigger and outlined when not chosen: on the sheet's own
 * surface the bar pill's faint fill was the same colour as the sheet, in both
 * themes, so the choices did not read as pills at all.
 */
@Composable
private fun KindPill(text: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(percent = 50),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(text, Modifier.padding(horizontal = 16.dp, vertical = 9.dp), style = MaterialTheme.typography.labelLarge)
    }
}
