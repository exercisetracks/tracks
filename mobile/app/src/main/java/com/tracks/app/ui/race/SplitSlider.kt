// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.race

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The pacing split, as a slider with an axis that says what it means.
 *
 * The convention is the server's (`_split_ramp`): a positive value is a
 * NEGATIVE split — slower first half, faster finish. The label used to read
 * the other way round from the web and the laps it produced.
 *
 * Ten steps each side rather than twenty: the laps barely move between
 * neighbouring detents, and a slider that fine was hard to land on.
 */
@Composable
internal fun SplitSlider(
    spread: Float,
    totalSec: Double?,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
) {
    Column {
        Text(splitDescription(spread.toDouble(), totalSec), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = spread,
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            valueRange = -1f..1f,
            steps = 19,
        )
        Row(Modifier.fillMaxWidth()) {
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            val style = MaterialTheme.typography.labelSmall
            Text(axisLabel(-1.0, totalSec), style = style, color = muted, modifier = Modifier.weight(1f))
            Text("Even", style = style, color = muted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            Text(axisLabel(1.0, totalSec), style = style, color = muted, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
    }
}

/** The slider's position, snapped to its detents: tenths. */
internal fun snapSplit(value: Float): Double = (value * 10).roundToInt() / 10.0

/**
 * How much faster (positive) or slower the second half is than the first,
 * as a fraction of the first half and in seconds of [totalSec].
 *
 * From the linear ramp: its halves average 1 ± 0.04·spread, so the halves
 * take T(1 ± 0.04·s)/2 and differ by 0.04·s·T. Hills change the laps but not
 * this, which is about the plan's intent rather than the course.
 */
internal fun halfSplit(spread: Double, totalSec: Double?): Pair<Double, Double?> {
    val pct = 2 * HALF_OFFSET * spread / (1 + HALF_OFFSET * spread)
    return pct to totalSec?.let { HALF_OFFSET * spread * it }
}

internal fun splitDescription(spread: Double, totalSec: Double?): String {
    if (abs(spread) < 0.05) return "Even pacing"
    val (pct, sec) = halfSplit(spread, totalSec)
    val amount = sec?.let { "${clock(abs(it))} (${percent(pct)})" } ?: percent(pct)
    return if (spread > 0) "Negative split — second half $amount faster"
    else "Positive split — second half $amount slower"
}

private fun axisLabel(spread: Double, totalSec: Double?): String {
    val (pct, sec) = halfSplit(spread, totalSec)
    val sign = if (spread > 0) "−" else "+"
    return sec?.let { "$sign${clock(abs(it))}" } ?: "$sign${percent(pct)}"
}

private fun percent(p: Double) = "${(abs(p) * 100).roundToInt()}%"

private fun clock(sec: Double): String {
    val s = sec.roundToInt()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/**
 * Half of `_MAX_SPLIT_SPREAD` (0.08): the first and last laps sit ±8% from
 * even at the slider's ends, so each half averages ±4%.
 */
private const val HALF_OFFSET = 0.04
