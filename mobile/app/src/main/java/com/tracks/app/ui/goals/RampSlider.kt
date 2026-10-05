// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.goals

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tracks.app.ui.components.Explain
import com.tracks.app.ui.components.InfoTip
import com.tracks.app.ui.theme.Tokens
import kotlin.math.roundToInt

/**
 * The fitness goal's slider: CTL change per week, −2 … +6 in half points.
 *
 * Drawn like [IntensitySlider] (the web's `RampSlider` in formControls.jsx is
 * its twin): a graded track, the value in the colour of where it sits. The
 * track turns red past +3 so the risk is visible before it is chosen, not
 * explained after. Everything else — what CTL is, why it stops at +6 — is behind the
 * "?"; the form carries no paragraphs.
 */
@Composable
fun RampSlider(value: Double, onValueChange: (Double) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s1_5)) {
            Text(
                "${GoalOptions.rampLabel(value)} · ${GoalOptions.rampWord(value)}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = rampColor(value),
            )
            InfoTip(Explain.FitnessRamp)
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .background(Brush.horizontalGradient(colorStops = RAMP_TRACK), RoundedCornerShape(50)),
            )
            Slider(
                value = value.toFloat(),
                onValueChange = { onValueChange(snapRamp(it)) },
                valueRange = GoalOptions.RAMP_MIN.toFloat()..GoalOptions.RAMP_MAX.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = rampColor(value),
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("−2", "+6").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Half-point steps, without the 11 tick marks a stepped Slider would draw over the track. */
internal fun snapRamp(v: Float): Double =
    ((v * 2f).roundToInt() / 2.0).coerceIn(GoalOptions.RAMP_MIN, GoalOptions.RAMP_MAX)

private val DETRAIN = Color(0xFF60A5FA)
private val HOLD = Color(0xFF10B981)
private val BUILD = Color(0xFFF59E0B)
private val RISK = Color(0xFFEF4444)

/** Where on the −2 … +6 track a value sits, 0 … 1. */
private fun at(v: Double) = ((v - GoalOptions.RAMP_MIN) / (GoalOptions.RAMP_MAX - GoalOptions.RAMP_MIN)).toFloat()

/** Blue to green up to 0, green to amber up to +3, then a hard edge into red. */
private val RAMP_TRACK = arrayOf(
    0f to DETRAIN,
    at(0.0) to HOLD,
    at(GoalOptions.RAMP_RISK) to BUILD,
    at(GoalOptions.RAMP_RISK) + 0.001f to RISK,
    1f to RISK,
)

/** The colour of a value — the band it falls in, as the track shows it. */
fun rampColor(v: Double): Color = when {
    v > GoalOptions.RAMP_RISK -> RISK
    v < 0 -> DETRAIN
    v < 1 -> HOLD
    else -> BUILD
}
