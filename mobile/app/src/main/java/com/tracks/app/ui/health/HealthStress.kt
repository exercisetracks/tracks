// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.health

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.tracks.app.ui.components.drawInspection
import com.tracks.app.ui.components.holdToInspect
import com.tracks.app.ui.theme.Tokens
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.tracks.app.ui.components.axisFormat
import com.tracks.app.ui.components.chartScale
import com.tracks.app.ui.components.drawYAxis
import com.tracks.app.ui.components.measureGutter
import com.tracks.core.api.StressDay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Stress over the window, at whatever resolution the window can carry.
 *
 * ## What was wrong with one point per day
 *
 * A day's stress is a curve and the chart was drawing its mean. Those are not
 * the same reading: a calm morning followed by a shattering afternoon averages
 * to exactly the same 40 as a flat, mediocre day, and the whole reason to look
 * at stress is to find out *which* — which hours, and whether they are the same
 * hours every day. The watch draws the curve on its own screen; the phone was
 * the only place the shape had been thrown away, and it was thrown away in the
 * parser, before the number ever reached a chart.
 *
 * ## Why a line over bands, and not a column per reading
 *
 * The first attempt at this drew a coloured column per pixel, each column the
 * colour of its own band. It is the shape Garmin Connect uses for *one* day and
 * it does not survive being stretched over a fortnight: at this density the
 * columns fuse into a solid block whose colour changes every few pixels, so the
 * eye gets a stripe of noise instead of a trend, and the one question the chart
 * exists to answer — is this getting better or worse — is the hardest to read
 * off it.
 *
 * So the reading is a line, and the colour is a pure function of *height*: the
 * line is stroked with a vertical gradient of the four band colours, with the
 * stops doubled at each boundary so the bands meet as hard edges rather than
 * blending. Crossing 50 is a change of state, not a shade. This is exactly how
 * the dashboard draws form over its own zones, by the same trick and for the
 * same reason — no per-segment logic is needed, because a point's colour
 * depends only on where it sits.
 *
 * The area under the line takes the same gradient, faded. That is the part
 * carrying the second question — not "where is it now" but "how much of the
 * day was spent up there" — and it answers it in the one way that needs no
 * legend at all: a day that spent the afternoon in the orange has an orange
 * afternoon on the chart.
 *
 * ## What was here before, and why it went
 *
 * A grey min–max box per column, drawn behind the line to show the spread
 * inside each averaged slice. It was information nobody could read: a lattice
 * of translucent rectangles that looked like a rendering fault, and the thing
 * it measured — the range within two hours of samples — is not a question
 * anybody brings to a stress chart. The mean already tracks the curve closely
 * at every window this draws (half an hour per column over a week), so almost
 * nothing was lost with it.
 *
 * The bands behind the plot stayed, at a much lower opacity than they had.
 * They are ground: they say what a height means in the stretches where there
 * is no line at all, which is exactly where a reader has nothing else to go on.
 *
 * A dashed line marks the window's average, because a level means little until
 * it is compared with something, and "higher than my usual" is the comparison
 * people are actually making.
 *
 * ## And why the long windows still get one point per day
 *
 * A year of three-minute samples is a fifth of a pixel per reading, which is
 * not legible at any density, and megabytes to fetch for the privilege. The
 * daily averages already sitting on the metric rows are the readable answer at
 * that scale and free. The switch is [HealthRange.intraday]'s, and the footer
 * says which of the two is on screen so the change of resolution is never
 * silent.
 *
 * ## Days with nothing in them
 *
 * Readings are placed by the moment they were taken, and the line breaks
 * wherever there are none. That is the point: a line drawn straight across a
 * charging day would say the watch had been worn through it.
 */
@Composable
fun StressHistoryPanel(
    /** The curve, per day. Empty on the windows that draw daily averages. */
    days: List<StressDay>,
    /** The daily averages, which are what a long window draws instead. */
    averages: Trend,
    windowStart: LocalDate?,
    modifier: Modifier = Modifier,
) {
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val averageColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.85f)

    // The curve when there is one, and the daily averages when there is not.
    // Not a fallback so much as the same picture at two resolutions: both are
    // stress readings placed at the moment they describe, and everything below
    // this point treats them identically.
    val intraday = days.any { it.points.isNotEmpty() }
    val dates = remember(days, averages, intraday) {
        if (intraday) days.map { it.date } else averages.dates
    }
    // Through the end of today: the curve's readings fill each day, and an
    // axis ending at today's midnight pressed all of this morning's onto the
    // right-hand edge. The daily averages hang at midday, so they want the
    // same slot rather than a point on the edge.
    val dayAxis = remember(dates, windowStart) { DayAxis.of(dates, windowStart, throughEnd = true) }

    val samples = remember(days, averages, dayAxis, intraday) {
        val axis = dayAxis ?: return@remember emptyList()
        if (intraday) {
            days.flatMap { day ->
                val on = DayAxis.parseDay(day.date) ?: return@flatMap emptyList()
                day.samples.mapNotNull { sample ->
                    val within = sample.minute.toFloat() / MINUTES_PER_DAY
                    axis.fraction(on, within)?.let { Sample(it, sample.level.toDouble()) }
                }
            }
        } else {
            // Midday, not midnight: a day's average describes the whole day,
            // and hanging it off the day's left edge would draw a year of
            // readings half a day earlier than they happened.
            averages.dates.zip(averages.values) { date, value -> date to value }
                .mapNotNull { (date, value) ->
                    val on = DayAxis.parseDay(date) ?: return@mapNotNull null
                    axis.fraction(on, 0.5f)?.let { Sample(it, value) }
                }
        }.sortedBy { it.at }
    }

    if (samples.isEmpty() || dayAxis == null) {
        Text(
            "No stress readings in this window.",
            style = MaterialTheme.typography.bodySmall,
            color = axisColor,
            modifier = modifier,
        )
        return
    }

    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall
    // The watch's own 0–100, always, rather than a scale fitted to the window.
    // The bands mean fixed numbers — 25 is where Low starts — and a chart that
    // rescaled itself to a calm fortnight would put the same 30 in a different
    // colour from one week to the next.
    val scale = remember { chartScale(0.0, STRESS_MAX, anchorZero = true) }
    val format = remember(scale) { axisFormat(scale) }
    val gutter = remember(scale, labelStyle) {
        measureGutter(scale, measurer, labelStyle, format)
    }
    // Midnights, but only while they are far enough apart to be read as
    // midnights. Past a fortnight they are a picket fence behind the data,
    // which is the opposite of the help they are there to give.
    // Stronger than the y gridlines, and stronger the fewer of them there are.
    // On this chart they are the reference that makes the whole thing readable
    // — a peak means nothing until you can see which day it is in. See
    // [DayMarks], the rule every history chart now shares.
    val markDays = DayMarks.wanted(dayAxis.days)
    val markColor = MaterialTheme.colorScheme.outline.copy(alpha = DayMarks.alpha(dayAxis.days))
    val average = remember(samples) { samples.map { it.level }.average() }

    // The bands as a vertical gradient, boundaries doubled so they meet as
    // hard edges. One brush, used twice: at full strength for the line and
    // faded for what is under it. See the header — this is the dashboard's
    // form line by the same route.
    val zoneStops = remember {
        STRESS_ZONES.reversed().flatMap { band ->
            listOf(
                (1.0 - band.max / STRESS_MAX).toFloat().coerceIn(0f, 1f) to band.color,
                (1.0 - band.min / STRESS_MAX).toFloat().coerceIn(0f, 1f) to band.color,
            )
        }
    }

    // Press and hold to read a moment (com.tracks.app.ui.components.holdToInspect).
    var inspectX by remember { mutableStateOf<Float?>(null) }
    val popup = com.tracks.app.ui.components.chartPopupColors()

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(CHART_HEIGHT + DATE_AXIS_HEIGHT)
                .holdToInspect { inspectX = it },
        ) {
            val plotHeight = size.height - DATE_AXIS_HEIGHT.toPx()
            fun y(level: Double) = plotHeight * (1f - scale.fraction(level))

            // The bands, faint, behind everything. Ground rather than figure:
            // they say what a height *means* in the stretches where there is
            // no line, and the line's own colour says it everywhere else.
            STRESS_ZONES.forEach { band ->
                val top = y(band.max)
                drawRect(
                    color = band.color.copy(alpha = BAND_ALPHA),
                    topLeft = Offset(gutter, top),
                    size = Size(size.width - gutter, y(band.min) - top),
                )
            }

            drawYAxis(
                scale = scale,
                measurer = measurer,
                style = labelStyle,
                labelColor = axisColor,
                gridColor = gridColor,
                plotHeight = plotHeight,
                gutter = gutter,
                format = format,
            )

            val plotWidth = (size.width - gutter).coerceAtLeast(1f)
            fun x(at: Float) = gutter + at * plotWidth

            if (markDays) {
                // Local midnights. On a week's window this is what turns a
                // trace into a habit: the same peak at the same hour on four
                // days out of seven is only visible against the day it is in.
                for (day in 1 until dayAxis.days) {
                    val at = day.toFloat() / dayAxis.days
                    drawLine(
                        color = markColor,
                        start = Offset(x(at), 0f),
                        end = Offset(x(at), plotHeight),
                        strokeWidth = 1f,
                    )
                }
            }

            // Averaged into columns a pixel or two wide rather than drawn one
            // point per reading. Fourteen thousand readings across three
            // hundred points of width is fifty of them fighting over each
            // column, and the last drawn would be the one seen — a chart whose
            // shape depended on the order of its input.
            val columnWidth = COLUMN_WIDTH.toPx()
            val columns = (plotWidth / columnWidth).toInt().coerceIn(1, MAX_COLUMNS)
            val total = DoubleArray(columns)
            val count = IntArray(columns)
            samples.forEach { sample ->
                val column = (sample.at * columns).toInt().coerceIn(0, columns - 1)
                total[column] += sample.level
                count[column]++
            }

            /** The centre of a column, in pixels. */
            fun columnX(column: Int) = x((column + 0.5f) / columns)

            // Laid over the plot, not over each run, so a point's colour is
            // the band it sits in wherever it is drawn.
            val stops = zoneStops.map { it.first to it.second }.toTypedArray()
            val lineBrush = Brush.verticalGradient(
                colorStops = stops,
                startY = 0f,
                endY = plotHeight,
            )
            val fillBrush = Brush.verticalGradient(
                colorStops = stops.map { it.first to it.second.copy(alpha = FILL_ALPHA) }
                    .toTypedArray(),
                startY = 0f,
                endY = plotHeight,
            )

            // The mean, as a line — broken wherever the watch was not worn.
            // Each run is drawn on its own so a single worn hour in a blank
            // week is still there, rather than an invisible endpoint of a line
            // that does not exist.
            var run = Path()
            var runStart = 0f
            var runEnd = 0f
            var runLastY = 0f
            var runLength = 0

            fun flush() {
                if (runLength > 1) {
                    val area = Path().apply {
                        addPath(run)
                        lineTo(runEnd, plotHeight)
                        lineTo(runStart, plotHeight)
                        close()
                    }
                    drawPath(path = area, brush = fillBrush)
                    drawPath(
                        path = run,
                        brush = lineBrush,
                        style = Stroke(
                            width = LINE_WIDTH.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                } else if (runLength == 1) {
                    drawCircle(
                        brush = lineBrush,
                        radius = LINE_WIDTH.toPx(),
                        center = Offset(runEnd, runLastY),
                    )
                }
                run = Path()
                runLength = 0
            }

            for (column in 0 until columns) {
                if (count[column] == 0) {
                    flush()
                    continue
                }
                val px = columnX(column)
                val py = y(total[column] / count[column])
                if (runLength == 0) {
                    run.moveTo(px, py)
                    runStart = px
                } else {
                    run.lineTo(px, py)
                }
                runEnd = px
                runLastY = py
                runLength++
            }
            flush()

            // The window's own average, so a level has something to be higher
            // than. Dashed, and in the theme's outline rather than a band
            // colour — it is a reference, not a reading.
            drawLine(
                color = averageColor,
                start = Offset(gutter, y(average)),
                end = Offset(size.width, y(average)),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
            )

            inspectX?.let { held ->
                // The nearest column with readings, and the band its level is in.
                val target = ((held - gutter) / plotWidth * columns).toInt().coerceIn(0, columns - 1)
                val column = (0 until columns).filter { count[it] > 0 }.minByOrNull { kotlin.math.abs(it - target) }
                if (column != null) {
                    val level = total[column] / count[column]
                    val at = (column + 0.5f) / columns
                    val day = dayAxis.dateAt(at)
                    val whenText = if (intraday) {
                        val hours = (at * dayAxis.days - java.time.temporal.ChronoUnit.DAYS.between(dayAxis.start, day)) * 24
                        "%s %02d:%02d".format(day.format(INSPECT_DAY), hours.toInt().coerceIn(0, 23), ((hours % 1) * 60).toInt())
                    } else {
                        day.format(INSPECT_DAY)
                    }
                    val band = STRESS_ZONES.firstOrNull { level >= it.min && level <= it.max } ?: STRESS_ZONES.last()
                    drawInspection(
                        measurer, labelStyle,
                        x = columnX(column), y = y(level), plotHeight = plotHeight,
                        text = "$whenText · stress ${level.roundToInt()}",
                        dot = band.color, colors = popup,
                    )
                }
            }

            drawDateAxis(
                axis = dayAxis,
                measurer = measurer,
                style = labelStyle,
                color = axisColor,
                plotLeft = gutter,
                plotWidth = plotWidth,
                top = plotHeight + DATE_LABEL_GAP.toPx(),
            )
        }

        Text(
            // Said out loud, because the same chart means two different things
            // at the two resolutions: a spike in the first is twenty minutes,
            // and in the second a whole day that averaged high.
            "avg ${average.roundToInt()} · " +
                if (intraday) "every reading" else "daily average",
            style = MaterialTheme.typography.labelSmall,
            color = axisColor,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )

        // The bands named, since behind a line they are too faint to carry
        // their own labels and the words are the verdict people came for.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            STRESS_ZONES.forEach { band ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(Modifier.size(8.dp), shape = RoundedCornerShape(Tokens.Radius.sm), color = band.color) {}
                    Text(
                        band.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = axisColor,
                    )
                }
            }
        }
    }
}

/** One reading: where it sits along the window, and how high. */
private data class Sample(val at: Float, val level: Double)

private val INSPECT_DAY = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")

private fun shortDay(iso: String): String =
    runCatching { LocalDate.parse(iso).format(DAY) }.getOrDefault(iso)

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

private const val MINUTES_PER_DAY = 1440f

/** Garmin's scale, and the one [STRESS_ZONES] is drawn against. */
private const val STRESS_MAX = 100.0

private val CHART_HEIGHT = 170.dp

/** Between the plot's floor and the dates under it. */
private val DATE_LABEL_GAP = 3.dp

/** One averaged column. Narrow, because the line through them is the subject. */
private val COLUMN_WIDTH = 2.dp

/** Bounds the per-frame work on a very wide screen. */
private const val MAX_COLUMNS = 600

private val LINE_WIDTH = 2.dp

/**
 * Ground, not figure.
 *
 * Low enough that the eye reads the *line* first and only notices the bands
 * when it goes looking for what a height means. They were three times this and
 * the chart looked like four stacked blocks with a wire through them.
 */
private const val BAND_ALPHA = 0.03f

/** Under the line: clearly the same colours, clearly not the reading. */
private const val FILL_ALPHA = 0.35f

