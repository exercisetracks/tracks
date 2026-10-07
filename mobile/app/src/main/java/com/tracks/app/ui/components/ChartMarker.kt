// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisGuidelineComponent
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.component.shapeComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.compose.common.insets
import com.patrykandpatrick.vico.core.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.core.cartesian.marker.ColumnCartesianLayerMarkerTarget
import com.patrykandpatrick.vico.core.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.core.cartesian.marker.LineCartesianLayerMarkerTarget
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Press and hold on a chart to read the point under your finger — the phone's
 * answer to the web's hover tooltip, which every desktop chart has.
 *
 * One marker for every Vico chart in the app, so they all read alike: a pill
 * with the day (from [AxisDates], when the model carries dates) and each
 * series' value, a dot on each line, and a guideline down to the axis. Vico
 * shows it while the finger is down and moves it as the finger slides; the
 * fitted charts do not scroll ([fittedScrollState]), so the slide is free to
 * move the marker rather than pan.
 *
 * [names] labels the series in layer order ("Fitness", "Fatigue"); missing
 * names show the bare value. [format] turns a value into text — whole numbers
 * by default, since most of these are bpm, CTL points or minutes.
 */
@Composable
fun rememberChartMarker(
    names: List<String> = emptyList(),
    format: (Double) -> String = ::wholeOrOneDecimal,
): CartesianMarker {
    val label = rememberTextComponent(
        color = MaterialTheme.colorScheme.inverseOnSurface,
        textSize = 12.sp,
        lineCount = 4,
        padding = insets(8.dp, 4.dp),
        background = rememberShapeComponent(
            fill(MaterialTheme.colorScheme.inverseSurface),
            CorneredShape.rounded(8.dp.value),
        ),
    )
    val formatter = remember(names, format) { MarkerText(names, format) }
    return rememberDefaultCartesianMarker(
        label = label,
        valueFormatter = formatter,
        indicator = { color -> shapeComponent(fill(color), CorneredShape.Pill) },
        indicatorSize = 8.dp,
        guideline = rememberAxisGuidelineComponent(),
    )
}

private class MarkerText(
    private val names: List<String>,
    private val format: (Double) -> String,
) : DefaultCartesianMarker.ValueFormatter {
    override fun format(context: CartesianDrawingContext, targets: List<CartesianMarker.Target>): CharSequence {
        val values = targets.flatMap { t ->
            when (t) {
                is LineCartesianLayerMarkerTarget -> t.points.map { it.entry.y }
                is ColumnCartesianLayerMarkerTarget -> t.columns.map { it.entry.y }
                else -> emptyList()
            }
        }
        val parts = values.mapIndexed { i, v ->
            names.getOrNull(i)?.let { "$it ${format(v)}" } ?: format(v)
        }
        val day = targets.firstOrNull()?.x?.let { x ->
            val dates = context.model.extraStore.getOrNull(AxisDates).orEmpty()
            dates.getOrNull(x.toInt())?.let { iso ->
                runCatching { LocalDate.parse(iso).format(MARKER_DATE) }.getOrNull()
            }
        }
        return (listOfNotNull(day) + parts.joinToString(" · ")).joinToString("\n")
    }
}

private val MARKER_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy")

/** 54 for 54.0, 3.2 for 3.24 — the values these charts carry. */
fun wholeOrOneDecimal(v: Double): String =
    if (abs(v - v.roundToLong()) < 0.05) v.roundToLong().toString() else "%.1f".format(v)
