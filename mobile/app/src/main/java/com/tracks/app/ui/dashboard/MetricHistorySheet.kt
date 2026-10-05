// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One point of a metric's history: when, and what.
 *
 * A shape rather than either source type, because readiness and VO₂max arrive as
 * different models and the sheet does not care — it draws a number against a
 * date and colours it by the same bands the dial uses.
 */
data class HistoryPoint(val date: String, val value: Double)

/**
 * The history behind a dial.
 *
 * The dials answer "where am I now" and had no way to ask "and how did I get
 * here" — which is the question that actually changes a decision. A VO₂max of 47
 * is good; a VO₂max of 47 that was 44 six weeks ago is a training block working,
 * and a 47 that was 50 is a warning. The web app puts both on the page at once
 * because it has the room. A phone does not, so the number stays on the dial and
 * the shape lives one tap behind it.
 *
 * A sheet rather than a screen: this is a glance, not a destination, and coming
 * back from it should not cost the dashboard's scroll position.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetricHistorySheet(
    title: String,
    points: List<HistoryPoint>,
    zones: List<GaugeZone>,
    /**
     * Bands for the word beside the figure, when [zones] is a composition
     * rather than a scale.
     *
     * A stacked arc's bands are its parts, so reading the headline off them
     * produces things like "1,552 kcal — To goal", which is not a verdict about
     * anything. Null means [zones] is a real scale and can speak for itself.
     */
    verdict: List<GaugeZone>? = null,
    unit: String = "",
    decimals: Int = 0,
    /** Extra rows under the chart — readiness uses this for its components. */
    breakdown: List<Pair<String, Double>> = emptyList(),
    /** What the metric means. The sheet is where there is room to say it. */
    info: com.tracks.app.ui.components.MetricInfo? = null,
    /**
     * A chart of the metric's own, replacing the default line.
     *
     * Sleep is why this exists: its history is four stacked stages and a score
     * riding over them, and flattening that to a single line of total hours in
     * the one place with room to show it properly would be a waste of the tap.
     */
    chart: (@Composable () -> Unit)? = null,
    /**
     * The day the window opens on — the left edge of the chart, data or not.
     *
     * Null falls back to the earliest reading, which is what the dashboard's
     * own dials want: readiness and VO₂max are drawn over whatever history
     * exists rather than over a window a selector chose.
     */
    windowStart: java.time.LocalDate? = null,
    /**
     * How long a reading stays current — see [com.tracks.app.ui.health.isFresh].
     *
     * The sheet is a history and shows the last reading whatever its age, but
     * it stops calling it a verdict once it is stale: "94 %, Normal" is a claim
     * about now, and a figure last recorded six days ago is not one. It becomes
     * the reading it is, dated, in the axis colour.
     */
    freshDays: Long? = null,
    /**
     * Whether to print the big figure, its verdict and the change line.
     *
     * A custom [chart] that leads with its own summary — sleep does — would
     * otherwise be preceded by the same number in a larger font, under a dial
     * showing it a third time.
     */
    headline: Boolean = true,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // The title, and the explanation behind a `?` beside it.
            //
            // Spelled out in full, this was three or four paragraphs standing
            // between the reader and the chart — on a phone, most of a screen
            // of prose that is read exactly once and scrolled past every time
            // afterwards. The chart is what the tap was for. So the copy moves
            // behind the same `?` the dashboard and the section headings
            // already use: one shape for "what is this", everywhere.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                info?.let { com.tracks.app.ui.components.InfoTip(it) }
            }

            if (points.isEmpty()) {
                Text(
                    "No history recorded yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            val latest = points.last().value
            val zone = zoneFor(latest, verdict ?: zones)
            // Whether the headline is a statement about *now*. The dial above
            // has already blanked itself if it is not — see
            // [com.tracks.app.ui.health.HealthMetric.freshDays] — and the sheet
            // saying "94 %, Normal" underneath a dashed dial would put the two
            // back in disagreement.
            val current = com.tracks.app.ui.health.isFresh(points.last().date, freshDays)
            val headlineColor =
                if (current) zone.color else MaterialTheme.colorScheme.onSurfaceVariant

            if (headline) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        format(latest, decimals) + if (unit.isEmpty()) "" else " $unit",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = headlineColor,
                    )
                    Text(
                        // The date rather than the verdict, because the verdict
                        // is the part that has expired. The number itself was
                        // really measured and is worth reading; what it is no
                        // longer is an answer to "how am I doing".
                        if (current) zone.label else "last on ${shortDate(points.last().date)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = headlineColor,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }

                ChangeLine(points, decimals, unit)
            }

            if (chart != null) {
                chart()
            } else {
                // A labelled chart rather than the bare sparkline this used to
                // draw: that one had no scale at all and, worse, drew nothing
                // below two readings — so a metric on its first day opened a
                // sheet with a hole in it. See HistoryChart.
                com.tracks.app.ui.health.HistoryChart(
                    points = points,
                    color = zone.color,
                    // Anything counted rather than measured reads wrong on a
                    // truncated baseline: 9,000 steps against 10,000 is not
                    // "nearly nothing", which is what a floor at 8,900 shows.
                    anchorZero = zones.firstOrNull()?.min == 0.0,
                    windowStart = windowStart,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // The scale, in one strip. It used to be a row per band, which for
            // resting heart rate was five lines of text restating what the
            // colours already said and pushed everything below it off screen.
            // A chart of the metric's own brings its own legend, and a second
            // scale under it describes the wrong picture — sleep's duration
            // bands beneath a chart of stages — so it is dropped there.
            if (chart == null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                ScaleStrip(zones = zones, value = latest, decimals = decimals)
            }

            if (breakdown.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                Text(
                    "WHAT MADE IT",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                breakdown.forEach { (name, value) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${value.roundToInt()}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The whole scale as one bar.
 *
 * The bands were a list — a coloured dot, a name and a range on a line each,
 * five of them for resting heart rate — which spent a third of the sheet
 * restating in words what the dial above had already said in colour, and pushed
 * the history itself off the screen on a short phone.
 *
 * As a bar the same information is spatial: each band is as wide as it is wide,
 * so the reader can see that "Average" is a narrow slice and "High" is
 * everything above it without reading a single number. The band the value falls
 * in is lit and the rest are dimmed.
 *
 * Under it, two things text has to carry: each band's name centred on the band
 * it belongs to, in the band's own colour so the two need no legend between
 * them, and the boundaries as numbers — because "where does Good start" is the
 * question a colour cannot answer.
 *
 * Both are dropped where they will not fit rather than overlapping into mush.
 * A name too wide for its slice is left out and its colour still places it; a
 * boundary that collides with its neighbour goes the same way, except for the
 * two ends, which are what the whole strip is measured against.
 */
@Composable
private fun ScaleStrip(zones: List<GaugeZone>, value: Double?, decimals: Int) {
    if (zones.isEmpty()) return
    val min = zones.first().min
    val max = zones.last().max
    val span = (max - min).takeIf { it > 0.0 } ?: return

    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val here = zoneFor(value, zones)
    val markColor = MaterialTheme.colorScheme.onSurface

    // Every boundary once: the bands meet, so a naive per-band pair would draw
    // each interior edge twice on top of itself.
    val edges = remember(zones, decimals) {
        (listOf(min) + zones.map { it.max }).distinct()
    }
    val labels = remember(edges, decimals, labelStyle) {
        edges.map { it to measurer.measure(format(it, decimals), labelStyle) }
    }
    // Each band's name, in the band's own colour — dimmed to match its segment
    // where the value is somewhere else, so the lit one reads as "you are
    // here" without a second marker saying so.
    val names = remember(zones, here, labelStyle) {
        zones.map { band ->
            band to measurer.measure(
                band.label,
                labelStyle.copy(color = if (band === here) band.color else band.color.copy(alpha = 0.6f)),
            )
        }
    }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(STRIP_HEIGHT)
    ) {
        val barHeight = BAR.toPx()
        val gap = SEGMENT_GAP.toPx()
        val radius = CornerRadius(barHeight / 2f, barHeight / 2f)
        fun x(v: Double) = (((v - min) / span).toFloat() * size.width)

        zones.forEach { band ->
            val left = x(band.min)
            val right = x(band.max) - gap
            if (right <= left) return@forEach
            drawRoundRect(
                color = if (band === here) band.color else band.color.copy(alpha = 0.3f),
                topLeft = Offset(left, 0f),
                size = Size(right - left, barHeight),
                cornerRadius = radius,
            )
        }

        // Where the reading actually is. A notch rather than a dot, because on
        // a 10dp bar a dot large enough to see would hide the band under it.
        value?.let { v ->
            // Held a half-stroke inside the ends, or a reading at either
            // extreme draws half a notch and reads as a rendering slip.
            val half = MARK.toPx() / 2f
            val at = x(v.coerceIn(min, max)).coerceIn(half, size.width - half)
            drawLine(
                color = markColor,
                start = Offset(at, -1f),
                end = Offset(at, barHeight + 1f),
                strokeWidth = MARK.toPx(),
                cap = StrokeCap.Round,
            )
        }

        val pad = LABEL_GAP.toPx()

        // Names first, on their own line directly under the bar. Centred on
        // their band, and skipped when the band is too narrow to hold the word
        // — a name spilling across two segments points at the wrong colour,
        // which is worse than no name at all.
        val nameTop = barHeight + pad
        var nameHeight = 0f
        names.forEach { (band, layout) ->
            val left = x(band.min) + (x(band.max) - x(band.min) - layout.size.width) / 2f
            if (left < 0f || left + layout.size.width > size.width) return@forEach
            if (layout.size.width > x(band.max) - x(band.min) - gap) return@forEach
            drawText(textLayoutResult = layout, topLeft = Offset(left, nameTop))
            nameHeight = layout.size.height.toFloat()
        }

        // Then the boundaries. The ends go down unconditionally: they are what
        // the whole strip is measured against, so if anything has to be dropped
        // for room it is an interior one — losing the maximum would leave a bar
        // of colours with no idea what it spans.
        val labelTop = nameTop + nameHeight + (if (nameHeight > 0f) pad * 0.6f else 0f)
        val first = labels.first().second
        val last = labels.last().second
        drawText(first, topLeft = Offset(0f, labelTop))
        var usedTo = first.size.width + pad
        val rightEdge = size.width - last.size.width
        if (labels.size > 1) drawText(last, topLeft = Offset(rightEdge, labelTop))

        labels.drop(1).dropLast(1).forEach { (edge, layout) ->
            val left = x(edge) - layout.size.width / 2f
            if (left < usedTo || left + layout.size.width + pad > rightEdge) return@forEach
            drawText(textLayoutResult = layout, topLeft = Offset(left, labelTop))
            usedTo = left + layout.size.width + pad
        }
    }
}

private val BAR = 10.dp
private val MARK = 2.dp
private val SEGMENT_GAP = 2.dp
private val LABEL_GAP = 5.dp
/** Bar, then the band names, then the boundaries — measured, not guessed at. */
private val STRIP_HEIGHT = 46.dp

/**
 * How far it has moved, and over what.
 *
 * Against the oldest point in the window rather than against yesterday: a metric
 * like VO₂max moves by fractions a week, so a day-on-day delta is almost always
 * zero and says nothing. The window is the comparison worth making.
 */
@Composable
private fun ChangeLine(points: List<HistoryPoint>, decimals: Int, unit: String) {
    if (points.size < 2) return
    val delta = points.last().value - points.first().value
    val rounded = format(abs(delta), decimals)
    val flat = abs(delta) < FLAT_THRESHOLD

    Text(
        when {
            flat -> "Unchanged since ${shortDate(points.first().date)}"
            delta > 0 -> "Up $rounded${unitSuffix(unit)} since ${shortDate(points.first().date)}"
            else -> "Down $rounded${unitSuffix(unit)} since ${shortDate(points.first().date)}"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = when {
            flat -> MaterialTheme.colorScheme.onSurfaceVariant
            delta > 0 -> UP
            else -> DOWN
        },
    )
}

private fun unitSuffix(unit: String) = if (unit.isEmpty()) "" else " $unit"

private fun format(value: Double, decimals: Int): String =
    if (decimals == 0) "${value.roundToInt()}"
    else {
        val scale = 10.0
        val r = (value * scale).roundToInt() / scale
        if (r % 1.0 == 0.0) "${r.toInt()}" else "$r"
    }

/** "12 Aug" — the year is noise inside a window this short. */
private fun shortDate(iso: String): String =
    runCatching { LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("d MMM")) }
        .getOrDefault(iso)

/** Below this, a change is rounding rather than movement. */
private const val FLAT_THRESHOLD = 0.05

private val UP = Color(0xFF22C55E)
private val DOWN = Color(0xFFF97316)

