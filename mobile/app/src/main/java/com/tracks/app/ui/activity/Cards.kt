// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.activity

import com.tracks.app.ui.theme.Tokens
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.tracks.app.ui.components.StatValue
import com.tracks.core.api.ActivityDetail
import com.tracks.core.api.Lap
import com.tracks.core.format.EMPTY
import com.tracks.core.format.distance
import com.tracks.core.format.elapsed
import com.tracks.core.format.elevation
import com.tracks.core.format.pace

/** One figure and its label, as a layout hands it to [StatGrid]. */
data class Stat(val value: String, val label: String)

/**
 * Stats in rows of three.
 *
 * Fixed at three rather than a responsive grid: these are short strings at a
 * known size, three fits every phone width this app targets, and a `LazyGrid`
 * inside the detail screen's scroll would nest two scrollables — which Compose
 * allows and which behaves badly under a finger.
 */
@Composable
fun StatGrid(stats: List<Stat>, modifier: Modifier = Modifier) {
    if (stats.isEmpty()) return
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            stats.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { stat ->
                        StatValue(stat.value, stat.label, Modifier.weight(1f))
                    }
                    // Pad the last row so two stats do not stretch to fill it.
                    repeat(3 - row.size) { Column(Modifier.weight(1f)) {} }
                }
            }
        }
    }
}

/**
 * The numbers a watch computed that Tracks does not recompute.
 *
 * Training effect, TSS, VO2max and the rest come off the device already
 * calculated against the athlete's own physiology; the server stores them and
 * this shows them. Hidden entirely when the activity carries none, which is
 * the common case for anything imported from a phone rather than a watch.
 */
@Composable
fun TrainingEffectCard(detail: ActivityDetail, modifier: Modifier = Modifier) {
    val stats = listOfNotNull(
        (detail.trainingStressScore ?: detail.effectiveTss)?.let {
            Stat(oneDecimal(it), "TSS")
        },
        detail.intensityFactor?.let { Stat(twoDecimals(it), "Intensity") },
        detail.aerobicTrainingEffect?.let { Stat(oneDecimal(it), "Aerobic TE") },
        detail.anaerobicTrainingEffect?.let { Stat(oneDecimal(it), "Anaerobic TE") },
        detail.vo2maxEstimate?.let { Stat(oneDecimal(it), "VO₂max") },
        detail.efficiencyFactor?.let { Stat(twoDecimals(it), "Efficiency") },
        detail.aerobicDecoupling?.let { Stat("${oneDecimal(it)}%", "Decoupling") },
        // Garmin's mountain-bike pair: how rough the terrain was, and how
        // smoothly it was ridden. Recorded on every MTB activity in the
        // database and shown on no screen until now.
        detail.totalGrit?.takeIf { it > 0 }?.let { Stat(oneDecimal(it), "Grit") },
        detail.avgFlow?.takeIf { it > 0 }?.let { Stat(oneDecimal(it), "Flow") },
    )
    val felt = listOfNotNull(
        feelLabel(detail.workoutFeel)?.let { Stat(it, "Feel") },
        effortLabel(detail.workoutRpe)?.let { Stat(it, "Effort") },
    )
    if (stats.isEmpty() && felt.isEmpty()) return

    // Here rather than as a card of its own so every sport layout carries it:
    // the prompts follow any workout, not only runs.
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (felt.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("How it felt")
                StatGrid(felt)
            }
        }
        if (stats.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("Training effect")
                StatGrid(stats)
            }
        }
    }
}

/**
 * The watch's "how did you feel" answer, in its own words. Recorded as 0–100 in
 * steps of 25; anything between steps reads as the nearest one.
 */
internal fun feelLabel(feel: Int?): String? {
    val f = feel?.takeIf { it in 0..100 } ?: return null
    return listOf("Very weak", "Weak", "Normal", "Strong", "Very strong")[(f + 12) / 25]
}

/**
 * Perceived effort, recorded ×10 (70 is 7/10), with the band the watch shows
 * beside the number. Zero means the question was skipped, not "no effort".
 */
internal fun effortLabel(rpe: Int?): String? {
    val r = rpe?.takeIf { it in 1..100 } ?: return null
    val score = (r + 5) / 10
    val band = when (score) {
        0, 1, 2 -> "Very light"
        3, 4 -> "Light"
        5, 6 -> "Moderate"
        7, 8 -> "Hard"
        9 -> "Very hard"
        else -> "Maximum"
    }
    return "${score.coerceAtLeast(1)}/10 · $band"
}

/**
 * Laps, as a table.
 *
 * Four columns became six. The phone showed lap number, distance, time and
 * *either* pace or heart rate — the two swapped by sport, so a runner could
 * never see their lap HR and a cyclist could never see anything else. The web
 * shows seven columns and the watch shows five; showing three and a half was
 * the outlier.
 *
 * The extra ones are conditional rather than always present. A pool swim has no
 * ascent and an activity recorded without a strap has no heart rate, and a
 * column of em dashes costs the width that the columns with data need.
 *
 * Capped at [MAX_LAPS] rows. A long ride with auto-lap every kilometre can carry
 * hundreds, and this sits inside the detail screen's single scroll — so the cap
 * is what keeps the screen from becoming a lap list with an activity attached.
 * The count in the header stays honest about what was trimmed.
 */
@Composable
fun LapsCard(laps: List<Lap>, showPace: Boolean, modifier: Modifier = Modifier) {
    if (laps.isEmpty()) return
    val shown = laps.take(MAX_LAPS)

    val showHr = shown.any { (it.avgHeartRate ?: 0) > 0 }
    val showClimb = shown.any { (it.totalAscent ?: 0.0) >= 1.0 }
    // Same device as the splits card: relative speed drawn behind the row, so
    // the shape of the effort is readable without comparing six-character
    // durations down a column.
    val speeds = shown.mapNotNull { lapSpeed(it) }
    val fastest = speeds.maxOrNull() ?: 0.0
    val slowest = speeds.minOrNull() ?: 0.0

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(
            if (laps.size > shown.size) "Laps (first ${shown.size} of ${laps.size})"
            else "Laps"
        )
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(vertical = 8.dp)) {
                LapRow(
                    number = "#",
                    distance = "Distance",
                    time = "Time",
                    rate = if (showPace) "Pace" else "Speed",
                    hr = "HR",
                    climb = "Climb",
                    fraction = 0f,
                    showHr = showHr,
                    showClimb = showClimb,
                    header = true,
                )
                shown.forEach { lap ->
                    LapRow(
                        number = "${lap.lapNumber}",
                        distance = distance(lap.distanceMeters),
                        time = elapsed(lap.durationSeconds?.toInt()),
                        rate = pace(lapSpeed(lap), isRunning = showPace),
                        hr = lap.avgHeartRate?.takeIf { it > 0 }?.toString() ?: EMPTY,
                        climb = lap.totalAscent?.takeIf { it >= 1.0 }
                            ?.let { "\u2191${elevation(it)}" } ?: EMPTY,
                        fraction = relativeBar(lapSpeed(lap), slowest, fastest),
                        showHr = showHr,
                        showClimb = showClimb,
                        header = false,
                    )
                }
            }
        }
    }
}

private const val MAX_LAPS = 40

@Composable
private fun LapRow(
    number: String,
    distance: String,
    time: String,
    rate: String,
    hr: String,
    climb: String,
    fraction: Float,
    showHr: Boolean,
    showClimb: Boolean,
    header: Boolean,
) {
    val style = if (header) {
        MaterialTheme.typography.labelSmall
    } else {
        MaterialTheme.typography.bodySmall
    }
    val color = if (header) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Box(Modifier.fillMaxWidth().height(if (header) 26.dp else 30.dp)) {
        if (!header && fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                    .clip(RoundedCornerShape(Tokens.Radius.base))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(number, style = style, color = muted, modifier = Modifier.width(22.dp))
            Text(distance, style = style, color = color, modifier = Modifier.weight(1.2f))
            Text(time, style = style, color = color, modifier = Modifier.weight(1.1f))
            Text(rate, style = style, color = color, modifier = Modifier.weight(1.2f))
            if (showHr) {
                Text(hr, style = style, color = muted, modifier = Modifier.width(32.dp))
            }
            if (showClimb) {
                Text(climb, style = style, color = muted, modifier = Modifier.width(46.dp))
            }
        }
    }
}

/**
 * A lap's speed from its own distance and duration.
 *
 * Not `avg_speed`: that is a moving average, so on any lap containing a stop it
 * disagrees with the pace the athlete saw on their watch — the same reason
 * [paceStat] derives rather than reads. Null when either half is missing, which
 * is what keeps a transition lap out of the bar scaling.
 */
private fun lapSpeed(lap: Lap): Double? {
    val metres = lap.distanceMeters ?: return null
    val seconds = lap.durationSeconds ?: return null
    if (metres <= 0 || seconds <= 0) return null
    return metres / seconds
}



/**
 * Strokes per hole.
 *
 * Par is not in the data — the watch records what was played, not what the card
 * said — so the bars are scaled against the worst hole of the round rather than
 * against par. That still answers the question anyone asks of a scorecard at a
 * glance: which holes went wrong.
 */
@Composable
fun GolfScorecard(laps: List<Lap>, modifier: Modifier = Modifier) {
    val holes = laps.filter { it.totalStrokes != null }
    if (holes.isEmpty()) return
    val worst = holes.maxOf { it.totalStrokes ?: 0 }.coerceAtLeast(1)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Scorecard")
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                holes.forEach { hole ->
                    val strokes = hole.totalStrokes ?: 0
                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            "$strokes",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height((strokes * MAX_BAR_HEIGHT / worst).dp)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                        Text(
                            "${hole.lapNumber}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private const val MAX_BAR_HEIGHT = 64

@Composable
fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

// Formatting these here rather than in core/format: they are presentation
// choices for this screen, not shared domain formatting the web app must match.
private fun oneDecimal(value: Double): String {
    val rounded = kotlin.math.round(value * 10) / 10
    return if (rounded % 1.0 == 0.0) "${rounded.toInt()}" else "$rounded"
}

private fun twoDecimals(value: Double): String {
    val rounded = kotlin.math.round(value * 100) / 100
    return "$rounded"
}
