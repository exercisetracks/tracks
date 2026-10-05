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
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The plan-intensity slider as the web draws it (`IntensitySlider` in
 * frontend/src/components/goals/formControls.jsx): a track graded from easy
 * blue to max red, the stop names beneath it, and the value's label in the
 * colour of where it sits. 0.05 steps, as on the web — the phone once offered
 * only the five named stops, so a plan set to 1.10 on the web could not be
 * reproduced, or even shown exactly, on the phone.
 */
@Composable
fun IntensitySlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .background(Brush.horizontalGradient(INTENSITY_GRADIENT), RoundedCornerShape(50)),
            )
            Slider(
                value = value,
                onValueChange = { onValueChange(snap(it)) },
                onValueChangeFinished = onValueChangeFinished,
                valueRange = 0.5f..1.5f,
                enabled = enabled,
                colors = SliderDefaults.colors(
                    thumbColor = intensityColor(value.toDouble()),
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            INTENSITY_STOPS.forEach { (_, label) ->
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * The strength-focus slider (strength_tier, 1–5), drawn as [IntensitySlider]
 * is — the web's `StrengthSlider` is its twin. It replaced a row of five
 * numbered buttons whose numbers meant nothing until the label above them
 * was read; the stop words beneath say what each end is.
 */
@Composable
fun StrengthFocusSlider(value: Int, onValueChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .background(Brush.horizontalGradient(INTENSITY_GRADIENT), RoundedCornerShape(50)),
            )
            Slider(
                value = value.toFloat(),
                onValueChange = { onValueChange(it.roundToInt().coerceIn(1, 5)) },
                valueRange = 1f..5f,
                colors = SliderDefaults.colors(
                    thumbColor = strengthColor(value),
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(GoalOptions.STRENGTH_FOCUS.first().second, GoalOptions.STRENGTH_FOCUS.last().second).forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A tier's colour: its place on the same track as the intensity slider's (1 at Easy, 5 at Max). */
fun strengthColor(tier: Int): Color = intensityColor(0.5 + (tier.coerceIn(1, 5) - 1) * 0.25)

/** The web's 0.05 step, without the 19 tick marks a stepped Slider would draw over the gradient. */
internal fun snap(v: Float): Float = ((v * 20f).roundToInt() / 20f).coerceIn(0.5f, 1.5f)

internal val INTENSITY_STOPS = listOf(0.5 to "Easy", 0.75 to "Light", 1.0 to "Moderate", 1.25 to "Hard", 1.5 to "Max")

/** The web's `linear-gradient(to right, #60a5fa, #34d399, #10b981, #f59e0b, #ef4444)`. */
private val INTENSITY_GRADIENT = listOf(
    Color(0xFF60A5FA), Color(0xFF34D399), Color(0xFF10B981), Color(0xFFF59E0B), Color(0xFFEF4444),
)

/**
 * `intensityColor` from frontend/src/components/goals/helpers.js: the colour of
 * a value, interpolated in HSL between the stops as the web does.
 */
fun intensityColor(v: Double): Color {
    val c = v.coerceIn(0.5, 1.5)
    return when {
        c <= 0.5 -> Color(0xFF60A5FA)
        c <= 0.75 -> { val t = (c - 0.5) / 0.25; Color.hsl((157 + (140 - 157) * t).toFloat(), (0.70 + 0.10 * t).toFloat(), (0.55 + 0.02 * t).toFloat()) }
        c <= 1.0 -> Color(0xFF10B981)
        c <= 1.25 -> { val t = (c - 1.0) / 0.25; Color.hsl((160 - 119 * t).toFloat(), 0.85f, (0.55 - 0.15 * t).toFloat()) }
        else -> { val t = (c - 1.25) / 0.25; Color.hsl((41 - 41 * t).toFloat(), (0.80 + 0.05 * t).toFloat(), (0.50 - 0.02 * t).toFloat()) }
    }
}

