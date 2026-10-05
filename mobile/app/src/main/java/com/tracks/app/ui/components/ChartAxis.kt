// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * A labelled y axis for the charts this app draws by hand.
 *
 * ## Why the hand-drawn charts needed this at all
 *
 * Vico gives its charts an axis for free, and every chart that uses Vico has
 * had one all along. The hand-drawn ones — sleep stages, the history behind a
 * dial, Body Battery — had none, which left a shape with no scale: a sleep bar
 * twice the height of its neighbour plainly means more sleep and says nothing
 * about whether that is five hours or nine, and a resting-heart-rate line that
 * dips is unreadable without knowing whether the dip is two beats or twenty.
 *
 * ## Round numbers, not the data's own
 *
 * Ticks are chosen at 1, 2, 2.5 or 5 times a power of ten, so an axis reads
 * 0/2/4/6/8 rather than 0/2.17/4.34/6.51. That is the one thing worth doing
 * properly here: an axis whose labels are as arbitrary as the data is harder to
 * read than no axis, because it invites arithmetic instead of a glance.
 */
data class ChartScale(val min: Double, val max: Double, val ticks: List<Double>) {
    val span: Double get() = (max - min).takeIf { it > 0.0 } ?: 1.0

    /** Where a value sits, 0f at the bottom of the plot and 1f at the top. */
    fun fraction(value: Double): Float = ((value - min) / span).toFloat()
}

/**
 * A scale covering [low]–[high], rounded outwards to tick boundaries.
 *
 * [anchorZero] pulls the floor down to zero, which is right for anything
 * counted — steps, calories, hours slept — where a truncated baseline
 * exaggerates every difference. It is wrong for readings that live in a narrow
 * band well away from zero: a resting heart rate axis running 0–60 wastes
 * nine-tenths of its height and flattens the only part anyone reads.
 */
fun chartScale(low: Double, high: Double, anchorZero: Boolean = false, target: Int = 4): ChartScale {
    val from = if (anchorZero) minOf(0.0, low) else low
    val to = if (high > from) high else from + 1.0
    val step = niceStep((to - from) / target.coerceAtLeast(1))
    val min = floor(from / step) * step
    val max = ceil(to / step) * step
    val ticks = buildList {
        var t = min
        // Guarded rather than while(t <= max): repeated addition of a step like
        // 0.1 drifts, and an unguarded loop on a degenerate range never ends.
        while (t <= max + step * 0.001 && size <= MAX_TICKS) {
            add(t)
            t += step
        }
    }
    return ChartScale(min, max, ticks)
}

/** 1, 2, 2.5 or 5 times a power of ten — the steps people count in. */
internal fun niceStep(raw: Double): Double {
    if (raw <= 0.0 || raw.isNaN() || raw.isInfinite()) return 1.0
    val magnitude = 10.0.pow(floor(log10(raw)))
    val normalised = raw / magnitude
    val stepped = when {
        normalised <= 1.0 -> 1.0
        normalised <= 2.0 -> 2.0
        normalised <= 2.5 -> 2.5
        normalised <= 5.0 -> 5.0
        else -> 10.0
    }
    return stepped * magnitude
}

/**
 * How much width the labels need.
 *
 * Measured rather than guessed — "58" and "11,402" want very different gutters
 * and a fixed one is wrong for whichever it was not chosen for — and measured
 * *in composition* rather than while drawing. The draw phase must not write
 * state the layout then reads: the caller needs this number to place its plot,
 * and computing it inside the Canvas would mean setting a value during draw
 * that invalidates the very composition that produced it.
 */
fun measureGutter(
    scale: ChartScale,
    measurer: TextMeasurer,
    style: TextStyle,
    format: (Double) -> String,
): Float =
    (scale.ticks.maxOfOrNull { measurer.measure(format(it), style).size.width } ?: 0)
        .toFloat() + LABEL_GAP

/** Gridlines and their labels down the left of a plot. */
fun DrawScope.drawYAxis(
    scale: ChartScale,
    measurer: TextMeasurer,
    style: TextStyle,
    labelColor: Color,
    gridColor: Color,
    plotHeight: Float,
    gutter: Float,
    plotTop: Float = 0f,
    format: (Double) -> String,
) {
    val laid: List<Pair<Double, TextLayoutResult>> = scale.ticks.map { tick ->
        tick to measurer.measure(format(tick), style)
    }

    laid.forEach { (tick, layout) ->
        val y = plotTop + plotHeight * (1f - scale.fraction(tick))
        drawLine(
            color = gridColor,
            start = Offset(gutter, y),
            end = Offset(size.width, y),
            strokeWidth = 1f,
        )
        drawText(
            textLayoutResult = layout,
            color = labelColor,
            topLeft = Offset(
                x = gutter - LABEL_GAP - layout.size.width,
                // Centred on its own line rather than sitting under it, except
                // at the edges where that would clip.
                y = (y - layout.size.height / 2f)
                    .coerceIn(plotTop, plotTop + plotHeight - layout.size.height),
            ),
        )
    }
}

/** Whole numbers unless the span is small enough that they would all read alike. */
fun axisFormat(scale: ChartScale): (Double) -> String {
    val step = scale.ticks.zipWithNext { a, b -> abs(b - a) }.minOrNull() ?: 1.0
    return if (step >= 1.0) {
        { value -> value.toInt().toString() }
    } else {
        { value -> ((value * 10).toInt() / 10.0).toString() }
    }
}

private const val LABEL_GAP = 6f
private const val MAX_TICKS = 12
