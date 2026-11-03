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
import androidx.compose.ui.graphics.PathEffect
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
    val faster = metric == CourseProfile.Metric.PACE || metric == CourseProfile.Metric.SWIM
    /** The axis figure: a clock for paces (per 100 m for a swim), a whole number otherwise. */
    val tFormat: (Double) -> String = { v ->
        when (metric) {
            CourseProfile.Metric.PACE -> RacePredictor.formatTime(v * if (imperial) 1.60934 else 1.0)
            CourseProfile.Metric.SWIM -> RacePredictor.formatTime(v / 10)
            else -> "${v.toInt()}"
        }
    }
    fun target(v: Double?): String = when {
        v == null -> "—"
        metric == CourseProfile.Metric.PACE -> tFormat(v) + "/$distUnit"
        metric == CourseProfile.Metric.SWIM -> tFormat(v) + "/100m"
        metric == CourseProfile.Metric.POWER -> "${v.toInt()} W"
        else -> "${v.toInt()} bpm"
    }
    val series = metric?.takeIf { points.any { it.target != null } }
    val color = when (series) {
        CourseProfile.Metric.PACE, CourseProfile.Metric.SWIM -> PACE
        CourseProfile.Metric.POWER -> POWER
        CourseProfile.Metric.HR -> HR
        null -> null
    }
    val eles = points.mapNotNull { it.ele }
    val hasEle = eles.isNotEmpty()
    val hrs = points.mapNotNull { it.hr }
    if (series == null && !hasEle) return

    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
    val popup = chartPopupColors()
    val eleScale = remember(points) {
        if (hasEle) padded(eles.minOf { ele(it) }, eles.maxOf { ele(it) }, 10.0).let { (lo, hi) -> chartScale(lo, hi) } else null
    }
    val eleFormat = remember(eleScale) { eleScale?.let { axisFormat(it) } }
    val targets = points.mapNotNull { it.target }
    val tScale = remember(points, series) {
        targets.takeIf { it.isNotEmpty() }?.let {
            if (faster) paceScale(it.min(), it.max(), if (series == CourseProfile.Metric.SWIM) 10.0 else 1.0)
            else padded(it.min(), it.max(), MIN_SPAN.getValue(series!!)).let { (lo, hi) -> chartScale(lo, hi, target = 3) }
        }
    }
    // No axis of its own for the HR line: its shape is drawn, its value read.
    val hrScale = remember(points) {
        hrs.takeIf { it.isNotEmpty() }?.let { padded(it.min(), it.max(), 10.0).let { (lo, hi) -> chartScale(lo, hi) } }
    }
    // Elevation's axis on the left and the target's on the right; with no
    // elevation the target's moves to the left.
    val leftGutter = remember(eleScale, tScale) {
        when {
            eleScale != null -> measureGutter(eleScale, measurer, labelStyle, eleFormat!!)
            tScale != null -> measureGutter(tScale, measurer, labelStyle, tFormat)
            else -> 0f
        }
    }
    val rightGutter = remember(tScale, eleScale) {
        if (eleScale != null) tScale?.let { measureGutter(it, measurer, labelStyle, tFormat) } ?: 0f else 0f
    }
    var inspectX by remember { mutableStateOf<Float?>(null) }
    var widthPx by remember { mutableFloatStateOf(0f) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (hasEle) Key(ELEVATION.copy(alpha = 0.5f), "Elevation ($eleUnit)", tall = true)
            if (series != null && color != null) {
                Key(color, when (series) {
                    CourseProfile.Metric.PACE, CourseProfile.Metric.SWIM -> "Target pace"
                    CourseProfile.Metric.POWER -> "Target power"
                    CourseProfile.Metric.HR -> "HR ceiling"
                }, tall = false)
            }
            if (hrScale != null) Key(HR_LINE, "HR ceiling", tall = false)
        }
        Box(Modifier.fillMaxWidth().height(150.dp)) {
            Canvas(
                Modifier.fillMaxWidth().height(150.dp)
                    .onSizeChanged { widthPx = it.width.toFloat() }
                    .claimInspectDrags()
                    .holdToInspect { x ->
                        inspectX = x
                        onInspect(x?.let { pointAt(points, it, leftGutter, rightGutter, widthPx) })
                    },
            ) {
                val plotH = size.height - 16.dp.toPx()
                val left = leftGutter
                val right = size.width - rightGutter
                val total = points.last().m.takeIf { it > 0 } ?: 1.0
                fun xOf(m: Double) = left + ((m / total) * (right - left)).toFloat()
                fun yEle(v: Double) = plotH * (1f - eleScale!!.fraction(ele(v)))
                fun yT(v: Double): Float {
                    val f = tScale!!.fraction(v)
                    // Faster-up: a smaller sec/km sits higher.
                    return plotH * if (faster) f else 1f - f
                }
                fun yHr(v: Double) = plotH * (1f - hrScale!!.fraction(v))

                if (eleScale != null) {
                    drawYAxis(eleScale, measurer, labelStyle, muted, grid, plotH, leftGutter, format = eleFormat!!)
                    tScale?.ticks?.forEach { t ->
                        val layout = measurer.measure(tFormat(t), labelStyle)
                        val y = (yT(t) - layout.size.height / 2f).coerceIn(0f, plotH - layout.size.height)
                        drawText(layout, color = muted, topLeft = Offset(size.width - layout.size.width, y))
                    }
                } else if (tScale != null) {
                    // drawYAxis lays ticks bottom-up; a faster-up pace axis is drawn by hand.
                    tScale.ticks.forEach { t ->
                        val y = yT(t)
                        drawLine(grid, Offset(leftGutter, y), Offset(size.width, y), strokeWidth = 1f)
                        val layout = measurer.measure(tFormat(t), labelStyle)
                        drawText(layout, color = muted, topLeft = Offset(leftGutter - 6f - layout.size.width,
                            (y - layout.size.height / 2f).coerceIn(0f, plotH - layout.size.height)))
                    }
                }

                if (eleScale != null) {
                    val area = Path().apply {
                        val first = points.first { it.ele != null }
                        moveTo(xOf(first.m), plotH)
                        points.forEach { p -> p.ele?.let { lineTo(xOf(p.m), yEle(it)) } }
                        lineTo(xOf(points.last().m), plotH)
                        close()
                    }
                    drawPath(area, ELEVATION.copy(alpha = 0.25f))
                }
                fun line(value: (CourseProfile.Point) -> Double?, y: (Double) -> Float): Path {
                    val path = Path()
                    var started = false
                    points.forEach { p ->
                        val v = value(p) ?: return@forEach
                        if (!started) path.moveTo(xOf(p.m), y(v)) else path.lineTo(xOf(p.m), y(v))
                        started = true
                    }
                    return path
                }
                if (hrScale != null) {
                    drawPath(line({ it.hr }, ::yHr), HR_LINE, style = Stroke(1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))))
                }
                if (series != null && color != null && tScale != null) {
                    drawPath(line({ it.target }, ::yT), color, style = Stroke(2.dp.toPx()))
                }

                // Distance labels at the start, middle and finish.
                listOf(0.0, total / 2, total).forEach { m ->
                    // Whole units on a long race; tenths on a short one, where
                    // the middle of a 3 km race is not "2 km".
                    val fmt = if (dist(total) < 10) "%.1f" else "%.0f"
                    val layout = measurer.measure("${fmt.format(dist(m))} $distUnit", labelStyle)
                    val x = (xOf(m) - layout.size.width / 2f).coerceIn(left, size.width - layout.size.width)
                    drawText(layout, color = muted, topLeft = Offset(x, size.height - layout.size.height))
                }

                val held = inspectX ?: return@Canvas
                val p = pointAt(points, held, leftGutter, rightGutter, size.width)
                // The ground there, as the lap table used to name it.
                val ground = p.lap?.label ?: p.lap?.gradient?.takeIf { it != 0.0 }
                    ?.let { "%+.1f%%".format(it * 100) }
                drawInspection(
                    measurer, labelStyle,
                    x = xOf(p.m),
                    y = p.target?.takeIf { series != null && tScale != null }?.let(::yT)
                        ?: p.ele?.takeIf { eleScale != null }?.let(::yEle) ?: (plotH / 2),
                    plotHeight = plotH,
                    text = listOfNotNull(
                        "%.1f $distUnit".format(dist(p.m)) + (ground?.let { " · $it" } ?: ""),
                        listOfNotNull(
                            p.ele?.let { "${ele(it).toInt()} $eleUnit" },
                            if (series != null) target(p.target) else null,
                            p.hr?.let { "HR ≤ ${it.toInt()}" },
                        ).joinToString(" · ").ifEmpty { null },
                    ).joinToString("\n"),
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
private fun paceScale(low: Double, high: Double, per: Double = 1.0): com.tracks.app.ui.components.ChartScale {
    // [per] is how many of the scale's units make one displayed second: ten
    // for a swim, whose sec/km is drawn per 100 m.
    val span = (high - low).coerceAtLeast(per)
    val step = listOf(15.0, 30.0, 60.0, 120.0, 300.0).map { it * per }.firstOrNull { span / it <= 4 } ?: (600.0 * per)
    val min = kotlin.math.floor(low / step) * step
    val max = kotlin.math.ceil(high / step) * step
    val ticks = generateSequence(min) { it + step }.takeWhile { it <= max + 0.001 }.toList()
    return com.tracks.app.ui.components.ChartScale(min, max.coerceAtLeast(min + step), ticks)
}

/**
 * At least [minSpan] around the data: a flat course's even power or a
 * constant HR ceiling otherwise gives an axis of 255–256 with the line pinned
 * to an edge.
 */
internal fun padded(lo: Double, hi: Double, minSpan: Double): Pair<Double, Double> =
    if (hi - lo >= minSpan) lo to hi else ((lo + hi) / 2).let { (it - minSpan / 2) to (it + minSpan / 2) }

/** The narrowest an axis may be, per target. Paces have their own steps (paceScale). */
private val MIN_SPAN = mapOf(
    CourseProfile.Metric.POWER to 20.0, CourseProfile.Metric.HR to 10.0,
    CourseProfile.Metric.PACE to 30.0, CourseProfile.Metric.SWIM to 300.0,
)

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
/**
 * The dashed HR third line: orange, not the HR red, because red beside the
 * pink pace line failed the palette check (ΔE 11, too close even with full
 * colour vision); orange clears both pace and power. The web uses the same.
 */
private val HR_LINE = Color(0xFFF97316)
