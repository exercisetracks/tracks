// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.activity

import com.tracks.app.ui.theme.Tokens
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.tracks.core.api.TrackPoint
import com.tracks.core.format.EMPTY
import com.tracks.core.format.elapsed
import com.tracks.core.format.elevation
import com.tracks.core.format.pace
import kotlin.math.roundToInt

/**
 * Every kilometre, and how each one went.
 *
 * The one thing on a watch that no Tracks screen had. Laps are not a substitute:
 * a lap is what the athlete pressed or what auto-lap was configured to do, so a
 * ride with auto-lap off has exactly one of them, and even where they line up
 * they answer "how did that interval go" rather than "where did I fade". Nothing
 * on the server has these — they are not stored, only derivable — which is why
 * [splits] does the arithmetic here from the recorded track.
 *
 * The bar behind each row is the point of the card. A column of paces is a
 * table you have to read; the same numbers with their relative speed drawn
 * behind them show the shape of the effort at a glance — negative split, a wall
 * at 30 km, the climb in the middle.
 */
@Composable
fun SplitsCard(
    track: List<TrackPoint>,
    recordedDistance: Double?,
    showPace: Boolean,
    modifier: Modifier = Modifier,
) {
    val splits = remember(track, recordedDistance) {
        splits(track, unitMetres = SPLIT_METRES, recordedDistance = recordedDistance)
    }
    if (splits.isEmpty()) return

    val shown = splits.take(MAX_SPLITS)
    val fastest = shown.maxOf { it.speed }
    val slowest = shown.minOf { it.speed }
    val climbed = shown.any { it.ascent >= 1.0 }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(
            if (splits.size > shown.size) "Splits (first ${shown.size} of ${splits.size})"
            else "Splits"
        )
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(vertical = 8.dp)) {
                SplitHeaderRow(showPace = showPace, showClimb = climbed)
                shown.forEach { split ->
                    SplitRow(
                        split = split,
                        fraction = relativeBar(split.speed, slowest, fastest),
                        showPace = showPace,
                        showClimb = climbed,
                    )
                }
            }
        }
    }
}

/** Kilometres. The rest of the app is metric; so is this. */
private const val SPLIT_METRES = 1000.0

/**
 * Long enough for a marathon, short enough that a 200 km ride does not turn the
 * detail screen into a spreadsheet. The header says when it has trimmed.
 */
private const val MAX_SPLITS = 60

/**
 * Where a row's bar ends, between the slowest of its peers and the fastest.
 *
 * Shared with the lap table, which draws the same device for the same reason:
 * scaled against the best row rather than against zero, because a marathon's
 * slowest kilometre is not 5% of its fastest and a chart anchored at zero makes
 * every row look identical. The gap is what is being read.
 *
 * Never zero-width — a slowest row with no bar at all reads as missing data
 * rather than as the slowest one.
 */
internal fun relativeBar(speed: Double?, slowest: Double, fastest: Double): Float {
    if (speed == null || speed <= 0) return 0f
    val span = fastest - slowest
    if (span <= 0) return 1f
    return (MIN_BAR + (1f - MIN_BAR) * ((speed - slowest) / span).toFloat()).coerceIn(MIN_BAR, 1f)
}

private const val MIN_BAR = 0.12f

/**
 * Duration is only its own column for the sports that read speed.
 *
 * A pace is minutes per kilometre and a split *is* a kilometre, so for a runner
 * the two columns held the same number twice — "12:58 12:58" down the whole
 * table, which reads as a rendering bug rather than as a tautology. Speed is
 * km/h, so for a cyclist they genuinely differ and both earn their width.
 *
 * The partial tail split is the one row where a runner's pace and time do
 * diverge, and pace is the half worth keeping: it is what compares to the rows
 * above it.
 */
private fun showsSeparateTime(showPace: Boolean) = !showPace

@Composable
private fun SplitHeaderRow(showPace: Boolean, showClimb: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val style = MaterialTheme.typography.labelSmall
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        Text("KM", style = style, color = color, modifier = Modifier.width(24.dp))
        Text(
            if (showPace) "Pace" else "Speed",
            style = style,
            color = color,
            modifier = Modifier.weight(1.4f),
        )
        if (showsSeparateTime(showPace)) {
            Text("Time", style = style, color = color, modifier = Modifier.weight(1f))
        }
        Text("HR", style = style, color = color, modifier = Modifier.width(34.dp))
        if (showClimb) {
            Text("Climb", style = style, color = color, modifier = Modifier.width(46.dp))
        }
    }
}

@Composable
private fun SplitRow(
    split: Split,
    fraction: Float,
    showPace: Boolean,
    showClimb: Boolean,
) {
    Box(Modifier.fillMaxWidth().height(30.dp)) {
        // Behind the text rather than beside it, so the numbers stay on one
        // line at this width and the bar costs no horizontal room at all.
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .padding(horizontal = 8.dp, vertical = 3.dp)
                .clip(RoundedCornerShape(Tokens.Radius.base))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val style = MaterialTheme.typography.bodySmall
            val onSurface = MaterialTheme.colorScheme.onSurface
            val muted = MaterialTheme.colorScheme.onSurfaceVariant

            Text(
                // A short tail is labelled by the distance it covered, because
                // calling it "8" next to seven full kilometres invites reading
                // its pace as a full one.
                if (split.metres >= SPLIT_METRES * 0.95) "${split.number}"
                else "${(split.metres / SPLIT_METRES * 100).roundToInt()}%",
                style = style,
                color = muted,
                modifier = Modifier.width(24.dp),
            )
            Text(
                pace(split.speed, isRunning = showPace),
                style = style,
                color = onSurface,
                modifier = Modifier.weight(1.4f),
            )
            if (showsSeparateTime(showPace)) {
                Text(
                    elapsed(split.seconds),
                    style = style,
                    color = onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                split.avgHeartRate?.toString() ?: EMPTY,
                style = style,
                color = muted,
                modifier = Modifier.width(34.dp),
            )
            if (showClimb) {
                Text(
                    if (split.ascent >= 1.0) "↑${elevation(split.ascent)}" else EMPTY,
                    style = style,
                    color = muted,
                    modifier = Modifier.width(46.dp),
                )
            }
        }
    }
}
