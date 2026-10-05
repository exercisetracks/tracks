// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.tracks.app.ui.components.axisFormat
import com.tracks.app.ui.components.chartScale
import com.tracks.app.ui.components.measureGutter
import com.tracks.app.ui.components.drawYAxis
import com.tracks.app.ui.dashboard.HistoryPoint
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * A metric's history, with a scale on it and holes where the holes are.
 *
 * ## What this replaces
 *
 * A bare [com.tracks.app.ui.dashboard.Sparkline]: a line with no axis, no
 * labels, and — the part that made it a bug rather than a simplification — an
 * early return below two points. A dial that had recorded exactly one day
 * opened a sheet with a blank space where the history should be, which reads as
 * a broken screen rather than as a young one. Steps had that on the day the
 * metric started working.
 *
 * So one point draws as a point, with the value beside it, and everything gets
 * a labelled y axis — see [com.tracks.app.ui.components.chartScale] for why the
 * ticks are round numbers rather than the data's own.
 *
 * ## Silence is drawn as silence
 *
 * Two rules, and both exist because this chart used to claim data it did not
 * have. Days are placed by *date*, so a fortnight's gap between readings is a
 * fortnight of empty chart. And the axis runs to **today** rather than to the
 * last reading, so a metric the watch stopped recording six days ago ends six
 * days short of the right-hand edge instead of finishing flush against it,
 * looking for all the world like this morning's number.
 *
 * That second one is what the SpO₂ dial was doing. Blood oxygen had not been
 * recorded for most of a week, and the sheet drew a line ending at the right
 * edge of the chart under a dial showing the last reading as though it were
 * current. Nothing was stale in the cache; the chart was simply drawing the
 * data's own extent and calling it the window.
 *
 * The line breaks for the same reason. A stroke joining Monday to Friday draws
 * three days of readings that were never taken, so segments are drawn only
 * across consecutive days and a reading with no neighbour is a dot — visible
 * on its own rather than an invisible endpoint of a line that is not there.
 */
@Composable
fun HistoryChart(
    points: List<HistoryPoint>,
    color: Color,
    modifier: Modifier = Modifier,
    /** Counted things start at zero; readings that live in a band do not. */
    anchorZero: Boolean = false,
    /**
     * The day the window opens on — the chart's left edge, data or not.
     *
     * Null falls back to the earliest reading, which is right for the lifetime
     * view where the data *is* the window, and wrong for every other one: three
     * readings taken this week would otherwise fill a thirty-day chart end to
     * end and look like a month of daily measurement.
     */
    windowStart: LocalDate? = null,
) {
    if (points.isEmpty()) return

    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val outline = MaterialTheme.colorScheme.outline
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall

    val values = points.map { it.value }
    val scale = remember(points, anchorZero) {
        chartScale(values.min(), values.max(), anchorZero = anchorZero)
    }
    val format = remember(scale) { axisFormat(scale) }
    val gutter = remember(scale, labelStyle) { measureGutter(scale, measurer, labelStyle, format) }

    // Position by date, so an irregular series is drawn irregularly — and
    // against the window, so it is drawn against the silence too.
    val dayAxis = remember(points, windowStart) {
        DayAxis.of(points.map { it.date }, windowStart)
    }
    val placed = remember(points, dayAxis) {
        val axis = dayAxis ?: return@remember emptyList()
        points.mapNotNull { point ->
            val day = DayAxis.parseDay(point.date) ?: return@mapNotNull null
            val fraction = axis.fraction(day) ?: return@mapNotNull null
            Reading(day, fraction, point.value)
        }.sortedBy { it.day }
    }
    // Runs of consecutive days. Each is drawn as its own line and its own
    // fill; a run of one is a dot. See the header for why they are not joined.
    val runs = remember(placed) { consecutiveRuns(placed) }
    if (placed.isEmpty() || dayAxis == null) return

    // One mark per day, on the windows short enough to count them. Without
    // them a gap in a date-positioned series is a stretch of empty chart, and
    // a stretch of empty chart is also what an unremarkable Tuesday looks
    // like — see [DayMarks].
    val markDays = DayMarks.wanted(dayAxis.days)
    val markColor = outline.copy(alpha = DayMarks.alpha(dayAxis.days))

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(
            modifier
                .fillMaxWidth()
                .height(CHART_HEIGHT + DATE_AXIS_HEIGHT),
        ) {
            val plotHeight = size.height - DATE_AXIS_HEIGHT.toPx()
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
            // Held a dot's radius clear of both ends. Points are drawn as
            // circles centred on their date, so a series starting on the first
            // day of the window put half its marker on top of the axis labels
            // — and the last one half outside the canvas.
            val dot = POINT_RADIUS.toPx()
            val plotLeft = gutter + dot
            val plotWidth = (size.width - plotLeft - dot).coerceAtLeast(1f)

            fun x(reading: Reading) = plotLeft + plotWidth * reading.fraction
            fun y(value: Double) = plotHeight * (1f - scale.fraction(value))

            if (markDays) {
                // On the day itself rather than between two, because that is
                // where the readings are: this chart puts a point *on* its
                // date, so a mark passing through a point is that point's day
                // rather than a boundary beside it. Drawn first, so the trace
                // sits on top of the scale rather than under it.
                for (day in 0..dayAxis.days) {
                    val at = plotLeft + plotWidth * (day.toFloat() / dayAxis.days)
                    drawLine(
                        color = markColor,
                        start = Offset(at, 0f),
                        end = Offset(at, plotHeight),
                        strokeWidth = 1f,
                    )
                }
            }

            runs.forEach { run ->
                if (run.size == 1) {
                    // One reading with no neighbour is a point, not a line.
                    // Drawn rather than skipped: "here is the one thing
                    // recorded that week" is a real answer, and the only one.
                    drawCircle(
                        color = color,
                        radius = POINT_RADIUS.toPx(),
                        center = Offset(x(run[0]), y(run[0].value)),
                    )
                    return@forEach
                }

                val line = Path().apply {
                    run.forEachIndexed { index, reading ->
                        val px = x(reading)
                        val py = y(reading.value)
                        if (index == 0) moveTo(px, py) else lineTo(px, py)
                    }
                }
                val area = Path().apply {
                    addPath(line)
                    lineTo(x(run.last()), plotHeight)
                    lineTo(x(run.first()), plotHeight)
                    close()
                }
                drawPath(
                    path = area,
                    brush = Brush.verticalGradient(
                        listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0.02f)),
                    ),
                )
                drawPath(
                    path = line,
                    color = color,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }

            // The latest reading marked, since it is the one the dial shows —
            // when the dial is showing one at all. Where it sits short of the
            // right edge is exactly how long ago that was.
            val latest = placed.last()
            drawCircle(
                color = color,
                radius = LATEST_RADIUS.toPx(),
                center = Offset(x(latest), y(latest.value)),
            )

            // The dates, in the strip held back for them. Several of them, so
            // the middle of the chart has a scale rather than an interpolation
            // between two ends — see [drawDateAxis].
            drawDateAxis(
                axis = dayAxis,
                measurer = measurer,
                style = labelStyle,
                color = axisColor,
                plotLeft = plotLeft,
                plotWidth = plotWidth,
                top = plotHeight + DATE_LABEL_GAP.toPx(),
            )
        }
    }
}

/** A reading, its day, and where that day lands on the axis. */
private data class Reading(val day: LocalDate, val fraction: Float, val value: Double)

/**
 * The series split into runs of consecutive days.
 *
 * A run is what may be joined by a line. Anything longer than a day's step
 * between two readings starts a new one, because the stroke between them would
 * be drawing readings nobody took.
 */
internal fun <T> splitOnGaps(items: List<T>, dayOf: (T) -> LocalDate): List<List<T>> {
    if (items.isEmpty()) return emptyList()
    val runs = mutableListOf<MutableList<T>>()
    var current = mutableListOf(items.first())
    items.zipWithNext { previous, next ->
        if (java.time.temporal.ChronoUnit.DAYS.between(dayOf(previous), dayOf(next)) == 1L) {
            current += next
        } else {
            runs += current
            current = mutableListOf(next)
        }
    }
    runs += current
    return runs
}

private fun consecutiveRuns(readings: List<Reading>): List<List<Reading>> =
    splitOnGaps(readings) { it.day }

private fun shortDay(iso: String): String =
    runCatching { LocalDate.parse(iso).format(DAY) }.getOrDefault(iso)

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
/**
 * Taller than it was, on room the explanatory copy used to occupy — that has
 * moved behind the `?` in the sheet's title. A trend read at 140dp was mostly
 * axis.
 */
private val CHART_HEIGHT = 170.dp

/** Between the plot's floor and the dates under it. */
private val DATE_LABEL_GAP = 3.dp

/** The larger of the two markers, and so the inset the plot needs at each end. */
private val POINT_RADIUS = 4.dp
private val LATEST_RADIUS = 3.dp
