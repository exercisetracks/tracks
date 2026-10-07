// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.activity

import com.tracks.app.ui.components.ChartScale
import com.tracks.app.ui.components.chartScale

/**
 * The y axis for one of an activity's recorded streams.
 *
 * ## Why not zero, and why not the data's own extent
 *
 * These charts were left to Vico's automatic range, which starts at zero. For
 * a stream that lives far from zero that is nearly the whole chart wasted: a
 * ride along a river at 108–116 m drew a ruler-flat line pressed against the
 * top of a 0–120 axis, and heart rate between 122 and 133 looked the same.
 *
 * Fitting the axis exactly to the data is the opposite mistake. Every ride
 * would then fill its chart top to bottom, and eight metres of riverbank
 * would look like an alpine pass. So the axis is centred on the data with a
 * margin either side, but never spans less than [minSpan] — a channel's
 * sense of what a small change is. A gentle ride reads as gentle, a hilly one
 * fills the chart, and both are in the middle of the frame.
 *
 * [floor] keeps a channel that cannot go negative (heart rate, speed, power,
 * cadence) from labelling its axis below zero when the centring would push it
 * there; the axis is slid up rather than cropped. Elevation passes null: a
 * walk below sea level is real.
 */
internal fun streamScale(values: List<Double>, minSpan: Double, floor: Double? = 0.0): ChartScale {
    val lo = values.min()
    val hi = values.max()
    val span = maxOf((hi - lo) / DATA_SHARE, minSpan)
    val mid = (lo + hi) / 2
    var from = mid - span / 2
    var to = mid + span / 2
    if (floor != null && from < floor && lo >= floor) {
        to += floor - from
        from = floor
    }
    // Rounded outwards to round ticks, trying a few counts and keeping the
    // tightest. Aimed at one count alone, the step is set by the span and
    // rounds up hard — a 906 m window took a 250 m step, rounded out to
    // 1250 m, and a climb that should have filled the chart filled half.
    return INTERVALS.map { chartScale(from, to, target = it) }.minBy { it.span }
}

/** How much of the height the data takes before [minSpan] steps in. */
private const val DATA_SHARE = 0.85

/** Four to six intervals: up to seven labels, which a 140dp chart holds. */
private val INTERVALS = listOf(4, 5, 6)
