// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.race

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.tracks.app.ui.components.axisFormat
import com.tracks.app.ui.components.chartPopupColors
import com.tracks.app.ui.components.chartScale
import com.tracks.app.ui.components.claimInspectDrags
import com.tracks.app.ui.components.drawInspection
import com.tracks.app.ui.components.drawYAxis
import com.tracks.app.ui.components.holdToInspect
import com.tracks.app.ui.components.measureGutter
import com.tracks.core.format.Units
import com.tracks.core.race.CourseProfile
import com.tracks.core.race.RacePredictor

/**
 * The course profile under the race plan's map: elevation by distance, and
 * the plan's target — pace, power or HR ceiling, whichever the sport coaches
 * by ([CourseProfile.metric]) — on a second axis, so a climb and what the
 * plan asks of it read together. The web draws the same plot
 * (ElevationChart.jsx).
 *
 * Two y-axes on purpose: the measures share only distance, and reading one
 * against the other at the same spot is the plot's whole job. Elevation is a
 * neutral area behind, the target a line in its metric's colour from the
 * activity graphs — an orange elevation sat too close to the HR line's red.
 * Pace runs faster-up, as everywhere in the app.
 *
 * Hold to read it; [onInspect] gets the point being read, which the screen
 * marks on the map above, and null when the finger lifts.
 */
@Composable
internal fun CourseProfileChart(
    points: List<CourseProfile.Point>,
    metric: CourseProfile.Metric?,
    onInspect: (CourseProfile.Point?) -> Unit,
) {
    val imperial = Units.imperial
    val distUnit = if (imperial) "mi" else "km"
    val eleUnit = if (imperial) "ft" else "m"
    fun dist(m: Double) = m / 1000 / if (imperial) 1.60934 else 1.0
    fun ele(m: Double) = if (imperial) m * 3.28084 else m
    fun target(v: Double?): String = when {
        v == null -> "—"
        metric == CourseProfile.Metric.PACE -> RacePredictor.formatTime(v * if (imperial) 1.60934 else 1.0) + "/$distUnit"
        metric == CourseProfile.Metric.POWER -> "${v.toInt()} W"
        else -> "${v.toInt()} bpm"
    }
    val series = metric?.takeIf { points.any { it.target != null } }
    val color = when (series) {
        CourseProfile.Metric.PACE -> PACE
        CourseProfile.Metric.POWER -> POWER
        CourseProfile.Metric.HR -> HR
        null -> null
    }

    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
    val popup = chartPopupColors()
    val eleScale = remember(points) { chartScale(points.minOf { ele(it.ele) }, points.maxOf { ele(it.ele) }) }
    val eleFormat = remember(eleScale) { axisFormat(eleScale) }
    val gutter = remember(eleScale) { measureGutter(eleScale, measurer, labelStyle, eleFormat) }
    val targets = points.mapNotNull { it.target }
    val tScale = remember(points, series) {
        targets.takeIf { it.isNotEmpty() }?.let {
            if (series == CourseProfile.Metric.PACE) paceScale(it.min(), it.max()) else chartScale(it.min(), it.max(), target = 3)
        }
    }
    val tFormat: (Double) -> String = { v ->
        if (series == CourseProfile.Metric.PACE) RacePredictor.formatTime(v * if (imperial) 1.60934 else 1.0) else "${v.toInt()}"
    }
    val rightGutter = remember(tScale) { tScale?.let { measureGutter(it, measurer, labelStyle, tFormat) } ?: 0f }
    var inspectX by remember { mutableStateOf<Float?>(null) }
    var widthPx by remember { mutableFloatStateOf(0f) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Key(ELEVATION.copy(alpha = 0.5f), "Elevation ($eleUnit)", tall = true)
            if (series != null && color != null) {
                Key(color, when (series) {
                    CourseProfile.Metric.PACE -> "Target pace"
                    CourseProfile.Metric.POWER -> "Target power"
                    CourseProfile.Metric.HR -> "HR ceiling"
                }, tall = false)
            }
        }
        Box(Modifier.fillMaxWidth().height(150.dp)) {
            Canvas(
                Modifier.fillMaxWidth().height(150.dp)
                    .onSizeChanged { widthPx = it.width.toFloat() }
                    .claimInspectDrags()
                    .holdToInspect { x ->
                        inspectX = x
                        onInspect(x?.let { pointAt(points, it, gutter, rightGutter, widthPx) })
                    },
            ) {
                val plotH = size.height - 16.dp.toPx()
                val left = gutter
                val right = size.width - rightGutter
                val total = points.last().m.takeIf { it > 0 } ?: 1.0
                fun xOf(m: Double) = left + ((m / total) * (right - left)).toFloat()
                fun yEle(v: Double) = plotH * (1f - eleScale.fraction(ele(v)))
                fun yT(v: Double): Float {
                    val f = tScale!!.fraction(v)
                    // Faster-up: a smaller sec/km sits higher.
                    return plotH * if (series == CourseProfile.Metric.PACE) f else 1f - f
                }

                drawYAxis(eleScale, measurer, labelStyle, muted, grid, plotH, gutter, format = eleFormat)
                tScale?.ticks?.forEach { t ->
                    val layout = measurer.measure(tFormat(t), labelStyle)
                    val y = (yT(t) - layout.size.height / 2f).coerceIn(0f, plotH - layout.size.height)
                    drawText(layout, color = muted, topLeft = Offset(size.width - layout.size.width, y))
                }

                val area = Path().apply {
                    moveTo(xOf(points.first().m), plotH)
                    points.forEach { lineTo(xOf(it.m), yEle(it.ele)) }
                    lineTo(xOf(points.last().m), plotH)
                    close()
                }
                drawPath(area, ELEVATION.copy(alpha = 0.25f))
                if (series != null && color != null && tScale != null) {
                    val line = Path()
                    var started = false
                    points.forEach { p ->
                        val t = p.target ?: return@forEach
                        if (!started) line.moveTo(xOf(p.m), yT(t)) else line.lineTo(xOf(p.m), yT(t))
                        started = true
                    }
                    drawPath(line, color, style = Stroke(2.dp.toPx()))
                }

                // Distance labels at the start, middle and finish.
                listOf(0.0, total / 2, total).forEach { m ->
                    // Whole units on a long course; tenths on a short one, where
                    // the middle of a 3 km course is not "2 km".
                    val fmt = if (dist(total) < 10) "%.1f" else "%.0f"
                    val layout = measurer.measure("${fmt.format(dist(m))} $distUnit", labelStyle)
                    val x = (xOf(m) - layout.size.width / 2f).coerceIn(left, size.width - layout.size.width)
                    drawText(layout, color = muted, topLeft = Offset(x, size.height - layout.size.height))
                }

                val held = inspectX ?: return@Canvas
                val p = pointAt(points, held, gutter, rightGutter, size.width)
                drawInspection(
                    measurer, labelStyle,
                    x = xOf(p.m),
                    y = if (series != null && p.target != null && tScale != null) yT(p.target!!) else yEle(p.ele),
                    plotHeight = plotH,
                    text = "%.1f $distUnit · %d $eleUnit".format(dist(p.m), ele(p.ele).toInt()) +
                        if (series != null) " · ${target(p.target)}" else "",
                    dot = color ?: ELEVATION,
                    colors = popup,
                )
            }
        }
    }
}

/**
 * Pace ticks on the clock's own steps — 15 s, 30 s, a minute — rather than
 * [chartScale]'s decimal ones, which put gridlines at 3:20, 4:10, 5:00.
 */
private fun paceScale(low: Double, high: Double): com.tracks.app.ui.components.ChartScale {
    val span = (high - low).coerceAtLeast(1.0)
    val step = listOf(15.0, 30.0, 60.0, 120.0, 300.0).firstOrNull { span / it <= 4 } ?: 600.0
    val min = kotlin.math.floor(low / step) * step
    val max = kotlin.math.ceil(high / step) * step
    val ticks = generateSequence(min) { it + step }.takeWhile { it <= max + 0.001 }.toList()
    return com.tracks.app.ui.components.ChartScale(min, max.coerceAtLeast(min + step), ticks)
}

/** The point nearest a finger at [x], on a plot between the two gutters. */
private fun pointAt(points: List<CourseProfile.Point>, x: Float, left: Float, rightGutter: Float, width: Float): CourseProfile.Point {
    val total = points.last().m
    val m = ((x - left) / (width - rightGutter - left)).coerceIn(0f, 1f) * total
    return points.minBy { kotlin.math.abs(it.m - m) }
}

@Composable
private fun Key(color: Color, label: String, tall: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp, if (tall) 8.dp else 2.dp).background(color, RoundedCornerShape(2.dp)))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The activity graphs' colours (the web's ElevationChart uses the same). */
private val ELEVATION = Color(0xFF94A3B8)
private val PACE = Color(0xFFEC4899)
private val POWER = Color(0xFFA78BFA)
private val HR = Color(0xFFEF4444)
