// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.dashboard

import androidx.compose.ui.graphics.luminance
import com.tracks.app.ui.theme.complementOf
import com.tracks.app.ui.theme.Tokens
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisGuidelineComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.fixed
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberEnd
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.compose.common.shader.verticalGradient
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import com.patrykandpatrick.vico.core.cartesian.axis.Axis
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import com.patrykandpatrick.vico.core.cartesian.decoration.Decoration
import com.patrykandpatrick.vico.core.cartesian.decoration.HorizontalBox
import com.patrykandpatrick.vico.core.cartesian.decoration.HorizontalLine
import com.patrykandpatrick.vico.core.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.common.component.LineComponent
import com.patrykandpatrick.vico.core.common.component.ShapeComponent
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import com.patrykandpatrick.vico.core.common.shader.ShaderProvider
import com.tracks.app.ui.components.DateAxisFormatter
import com.tracks.app.ui.components.Explain
import com.tracks.app.ui.components.InfoTip
import com.tracks.app.ui.components.MetricInfo
import com.tracks.app.ui.components.axisDates
import com.tracks.app.ui.components.thinnedLabels
import com.tracks.app.ui.components.fittedScrollState
import com.tracks.app.ui.components.fittedZoomState
import com.tracks.core.api.TrainingLoadPoint
import com.tracks.core.api.WeeklyVolumePoint
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import com.tracks.core.spec.tsbBandFor
import com.tracks.core.spec.tsbBands
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The dashboard's charts.
 *
 * ## Vico for the plots, Canvas for the rest
 *
 * These were hand-drawn on `Canvas` first, and the result was charts that
 * plotted the numbers correctly and looked unfinished — no axes, no gridlines,
 * no labels, no touch. Each of those is a real amount of work, and building
 * them by hand would have been reimplementing a charting library one missing
 * feature at a time. Vico is Apache-2 and on Maven Central, so it costs nothing
 * against the F-Droid constraints that ruled out a heavier dependency.
 *
 * What stays hand-drawn is what Vico has no concept of: the contribution
 * calendar (see `CalendarAndSports.kt`) and the radial gauges below. The web
 * app draws its equivalents as hand-built SVG for the same reason.
 *
 * Colours come from the Material scheme via [rememberM3VicoTheme], except where
 * they carry meaning — the form line is the TSB band's own colour out of
 * `spec/zones.yaml`, so the phone and the browser cannot disagree about what
 * "optimal" looks like.
 */

/** Parses the `#rrggbb`/`#rrggbbaa` strings the generated spec tables carry. */
internal fun specColor(hex: String): Color {
    val cleaned = hex.removePrefix("#")
    val rgb = cleaned.take(6).toLong(16)
    val alpha = if (cleaned.length >= 8) cleaned.substring(6, 8).toInt(16) / 255f else 1f
    return Color(
        red = ((rgb shr 16) and 0xFF) / 255f,
        green = ((rgb shr 8) and 0xFF) / 255f,
        blue = (rgb and 0xFF) / 255f,
        alpha = alpha,
    )
}

// ── Fitness: CTL / ATL / TSB ─────────────────────────────────────────────────

/**
 * Fitness, fatigue, and form — as two charts, not three lines.
 *
 * All three series used to share one pane and one axis, on the reasoning that
 * CTL and ATL are the same unit and TSB swinging through zero was worth showing
 * honestly. In practice that reasoning produced a chart answering neither
 * question. CTL runs 0–85 while TSB runs −30–+25, so on a shared axis the form
 * line is pinned to the floor as a nearly flat trace, and the form *bands* —
 * the entire reason anyone reads TSB — cannot be drawn at all, because shading
 * −30..−5 on an axis that also carries fitness would paint a stripe straight
 * across the fitness curve.
 *
 * So this mirrors the web app: load on top, form underneath with its zones
 * behind it. They stack rather than sitting side by side, and they share one
 * date axis, which only the lower chart draws.
 *
 * Band colours come from `spec/zones.yaml` via the generated Kotlin, so
 * "Optimal" is the same green in both clients, and the form line is coloured by
 * the band it passes through rather than by its final value — see
 * [rememberZoneColoredLine]. Fitness and fatigue match the web app's palette
 * directly; see [FITNESS_COLOR].
 */
@Composable
fun FitnessChart(points: List<TrainingLoadPoint>, modifier: Modifier = Modifier) {
    // The same sanity filter the web app applies. One corrupt row would
    // otherwise set the y range for the whole chart and flatten everything
    // real into the baseline.
    val clean = remember(points) {
        points.filter {
            abs(it.ctl) < LOAD_SANITY_LIMIT &&
                abs(it.atl) < LOAD_SANITY_LIMIT &&
                abs(it.tsb) < LOAD_SANITY_LIMIT
        }
    }
    if (clean.size < 2) {
        ChartPlaceholder("Not enough training history yet.", modifier)
        return
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TrainingLoadChart(clean)
        FormChart(clean)
    }
}

/** Beyond this a value is a data error, not a very fit athlete. */
private const val LOAD_SANITY_LIMIT = 1000.0

/**
 * Fitness and fatigue, in the web app's own colours.
 *
 * These were the Material scheme's `primary` and `error`, on the reasoning that
 * neither carries meaning beyond being distinguishable. True in isolation and
 * wrong in practice: someone reading the same two lines in the browser and on
 * the phone is reading *one* chart, and green-and-purple there against
 * green-and-red here makes them look at two. Matching `FitnessChart.jsx`
 * exactly is the point.
 *
 * Hard-coded rather than themed for the same reason the TSB bands are: the
 * colour is part of what the chart means, so it must not move with the theme.
 */
private val FITNESS_COLOR = Color(0xFF10B981)
private val FATIGUE_COLOR = Color(0xFFA855F7)

/**
 * Fitness and fatigue, anchored at zero.
 *
 * Zero rather than the data's own floor: these are accumulations, and the
 * distance from nothing is meaningful in a way the distance from last month's
 * low is not. Their crossing is what the chart is read for, so both share an
 * axis — which is legitimate here precisely because they share a unit.
 */
@Composable
private fun TrainingLoadChart(points: List<TrainingLoadPoint>) {
    val ctlColor = FITNESS_COLOR
    val atlColor = FATIGUE_COLOR

    val producer = remember { CartesianChartModelProducer() }
    // Index on x, dates carried in the model's extras.
    //
    // Passing epoch days as x looked more principled and was wrong twice over:
    // the axis compressed a year into what read as eight days, and the y range
    // inflated to ~250 against data peaking near 85. Vico spaces entries by
    // index and derives ranges from the model, so a 20,000-ish x per point
    // fights both.
    LaunchedEffect(points) {
        producer.runTransaction {
            lineSeries {
                series(points.map { it.ctl })
                series(points.map { it.atl })
            }
            axisDates(points.map { it.date })
        }
    }

    val axis = remember(points) { niceAxis(0.0, points.maxOf { maxOf(it.ctl, it.atl) }) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChartLabel("Training load", Explain.TrainingLoad)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LegendDot("Fitness", ctlColor)
                LegendDot("Fatigue", atlColor)
            }
        }
        ProvideVicoTheme(tracksVicoTheme()) {
            CartesianChartHost(
                chart = rememberCartesianChart(
                    rememberLineCartesianLayer(
                        lineProvider = LineCartesianLayer.LineProvider.series(
                            lineSpec(ctlColor, filled = true),
                            lineSpec(atlColor, filled = true),
                        ),
                        rangeProvider = dataRange(axis),
                    ),
                    startAxis = sharedStartAxis(axis.ticks),
                    // No date axis here: the form chart below carries it for
                    // both, which is what keeps the two plot areas aligned —
                    // together with the pinned gutter, without which the two
                    // hosts size their own and the traces do not line up.
                ),
                modelProducer = producer,
                scrollState = fittedScrollState(),
                zoomState = fittedZoomState(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
            )
        }
    }
}

/**
 * Form, over its zones.
 *
 * The bands are the point. A TSB of −18 means nothing on its own and means
 * "training productively" the moment you can see it sitting inside Optimal,
 * which is exactly what a shared axis with fitness made impossible to draw.
 *
 * The range always contains −30..+25 even when the data does not reach them, so
 * the zones keep their familiar positions instead of the chart rescaling into
 * something that looks like a different metric during a quiet month.
 */
@Composable
private fun FormChart(points: List<TrainingLoadPoint>) {
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(points) {
        producer.runTransaction {
            lineSeries { series(points.map { it.tsb }) }
            axisDates(points.map { it.date })
        }
    }

    val low = minOf(points.minOf { it.tsb }, TSB_FLOOR)
    val high = maxOf(points.maxOf { it.tsb }, TSB_CEILING)
    // The axis extent, not the data extent — the gradient below has to map onto
    // exactly what the range provider pins, or the colour boundaries sit a few
    // pixels off the band edges they are supposed to mark.
    // Rounded outwards to whole ticks, like every other axis here — see
    // [niceAxis]. The padding constant is what it is rounded *from*, so a
    // series that just grazes a band edge still gets air above it.
    val axis = remember(low, high) { niceAxis(low - TSB_PADDING, high + TSB_PADDING) }
    val axisMin = axis.min
    val axisMax = axis.max
    val zones = rememberFormZones(low, high)
    val zeroLine = rememberZeroLine()
    val zoneLine = rememberZoneColoredLine(axisMin, axisMax)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Just the label. The current band was named here too, which put
        // "Transition" between the two charts and again in the zone legend
        // below this one — the same word twice on one screen, once as a reading
        // and once as a key, with nothing to say which was which.
        ChartLabel("Form", Explain.Form)
        ProvideVicoTheme(tracksVicoTheme()) {
            CartesianChartHost(
                chart = rememberCartesianChart(
                    rememberLineCartesianLayer(
                        lineProvider = LineCartesianLayer.LineProvider.series(zoneLine),
                        rangeProvider = remember(axisMin, axisMax) {
                            CartesianLayerRangeProvider.fixed(minY = axisMin, maxY = axisMax)
                        },
                    ),
                    startAxis = sharedStartAxis(axis.ticks),
                    bottomAxis = HorizontalAxis.rememberBottom(
                        valueFormatter = DateAxisFormatter,
                        itemPlacer = thinnedLabels(),
                        guideline = null,
                    ),
                    decorations = zones + zeroLine,
                ),
                modelProducer = producer,
                scrollState = fittedScrollState(),
                zoomState = fittedZoomState(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
            )
        }
        FormZoneLegend()
    }
}

// The landmarks the form axis always shows, matching the web app's chart.
private const val TSB_FLOOR = -30.0
private const val TSB_CEILING = 25.0
private const val TSB_PADDING = 4.0

/**
 * The form bands as chart decorations.
 *
 * Drawn by Vico rather than as a Canvas behind the chart, because a hand-drawn
 * band has to reproduce Vico's y scale and its axis insets to line up — and
 * would drift silently the moment either changed.
 *
 * Clipped to the visible range: the outer bands are unbounded in the spec
 * (`High Risk` has no floor), and shading to negative infinity is not a thing
 * a renderer can be asked for.
 */
@Composable
private fun rememberFormZones(low: Double, high: Double): List<Decoration> =
    remember(low, high) {
        tsbBands.mapNotNull { band ->
            val from = (band.min?.toDouble() ?: low).coerceAtLeast(low)
            val to = (band.max?.toDouble() ?: high).coerceAtMost(high)
            if (from >= to) return@mapNotNull null
            HorizontalBox(
                y = { from..to },
                box = ShapeComponent(fill = fill(specColor(band.bg))),
            )
        }
    }

/**
 * The form line, coloured by the zone it is passing through.
 *
 * The web app does this with a `Customized` component that splits the path and
 * strokes each segment in its zone's colour. Vico has no equivalent hook, and
 * hand-drawing the line would mean reimplementing its scaling — but the effect
 * does not actually need per-segment logic, because the colour is a pure
 * function of *height*: a vertical gradient over the plot area gives every
 * point of the line the colour of the band it sits in, which is the same
 * picture by a much shorter route.
 *
 * The stops are doubled at each boundary — `[…, x, x, …]` with the outgoing and
 * incoming colours — so the bands meet as hard edges rather than blending. A
 * smooth ramp would be a prettier gradient and a worse chart: the whole point
 * is that crossing −5 is a change of state, not a shade.
 *
 * [axisMin] and [axisMax] must be the pinned axis extent rather than the data's
 * own range, since the shader is laid out over the plot area.
 */
@Composable
private fun rememberZoneColoredLine(
    axisMin: Double,
    axisMax: Double,
): LineCartesianLayer.Line {
    val span = (axisMax - axisMin).takeIf { it > 0.0 } ?: 1.0
    val colors = mutableListOf<Color>()
    val stops = mutableListOf<Float>()

    // Bands run high to low in the spec, and a vertical gradient runs top to
    // bottom, so the spec's own order is already the drawing order.
    tsbBands.forEach { band ->
        val top = (band.max?.toDouble() ?: axisMax).coerceAtMost(axisMax)
        val bottom = (band.min?.toDouble() ?: axisMin).coerceAtLeast(axisMin)
        if (bottom >= top) return@forEach
        val color = specColor(band.color)
        colors += color
        stops += ((axisMax - top) / span).toFloat().coerceIn(0f, 1f)
        colors += color
        stops += ((axisMax - bottom) / span).toFloat().coerceIn(0f, 1f)
    }

    // A gradient needs at least two stops; a range so narrow that only one band
    // survives falls back to that band's flat colour.
    if (colors.size < 2) {
        return lineSpec(colors.firstOrNull() ?: MaterialTheme.colorScheme.primary)
    }

    return LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(
            fill(
                ShaderProvider.verticalGradient(
                    colors.toTypedArray(),
                    stops.toFloatArray(),
                ),
            ),
        ),
        // The same stroke every other line on both tabs uses — see [lineSpec].
        // This one cannot go through it because its fill is a gradient rather
        // than a colour, which is the whole point of it.
        stroke = LineCartesianLayer.LineStroke.Continuous(
            thicknessDp = LINE_THICKNESS_DP,
            cap = android.graphics.Paint.Cap.ROUND,
        ),
    )
}

/** Zero is what form is read against — above it rested, below it loaded. */
@Composable
private fun rememberZeroLine(): List<Decoration> {
    val color = MaterialTheme.colorScheme.outline
    return remember(color) {
        listOf(
            HorizontalLine(
                y = { 0.0 },
                line = LineComponent(fill = fill(color), thicknessDp = 1f),
            ),
        )
    }
}

/**
 * The five bands, named.
 *
 * Wrapped rather than scrolled: five short labels fit two lines on every phone
 * width this app targets, and a legend you have to scroll to read is not doing
 * its job.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormZoneLegend() {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tsbBands.forEach { band ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Surface(Modifier.size(7.dp), shape = CircleShape, color = specColor(band.color)) {}
                Text(
                    band.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChartLabel(text: String, info: MetricInfo? = null) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // On the chart's own label rather than the card's, because this card
        // holds two charts. "Fitness" explains the section; form and training
        // load are different metrics with different scales, and a reader
        // looking at the one that goes negative needs the answer next to it.
        info?.let { InfoTip(it) }
    }
}


/**
 * A y range that follows the data, with a little headroom.
 *
 * Vico's automatic provider rounds to round numbers and can overshoot a narrow
 * series badly — see the note at the fitness layer. A measured range with 8%
 * padding keeps the curve filling the pane at every period and every filter.
 *
 * The equal-values guard matters: a single week, or a flat series, has zero
 * span, and an axis with no extent renders nothing at all.
 */
/**
 * An axis extent whose gridlines land on numbers people count in.
 *
 * ## Why the count and the rounding cannot be chosen separately
 *
 * Pinning the tick *count* is what makes a dual axis honest — one grid read
 * two ways — and on its own it produces axes labelled 0, 37.5, 75, 112.5, 150,
 * because five evenly spaced ticks across a rounded range are only round if
 * the range happens to divide by four. Vico's own placer takes the opposite
 * trade: round values, and whatever count falls out, which leaves the
 * weekly-volume chart with four kilometre lines and five hour lines and no way
 * to tell which reading any given line belongs to.
 *
 * So the step is chosen first — [com.tracks.app.ui.components.niceStep], the
 * same one the Health charts use — the bounds are pushed out to whole
 * multiples of it, and the tick count is whatever that produces. Round numbers
 * and a tight fit, with the count as the free variable.
 *
 * [atLeastTicks] is how the dual axis then gets its match: the two scales are
 * measured independently, and whichever wants fewer gridlines is grown by
 * whole steps at the top until it wants as many as the other. Growing rather
 * than shrinking, because shrinking would mean a coarser step and a top of
 * the chart even further above the data.
 */
private data class NiceAxis(val min: Double, val max: Double, val ticks: Int)

private fun niceAxis(min: Double, max: Double, atLeastTicks: Int = 0): NiceAxis {
    val from = minOf(min, 0.0)
    val to = if (max > from) max else from + 1.0
    val step = com.tracks.app.ui.components.niceStep((to - from) / (AXIS_TICKS - 1))
    val low = kotlin.math.floor(from / step) * step
    var high = kotlin.math.ceil(to / step) * step
    var intervals = Math.round((high - low) / step).toInt().coerceAtLeast(1)
    while (intervals + 1 < atLeastTicks) {
        high += step
        intervals++
    }
    return NiceAxis(low, high, intervals + 1)
}

@Composable
private fun dataRange(axis: NiceAxis): CartesianLayerRangeProvider =
    remember(axis) { CartesianLayerRangeProvider.fixed(minY = axis.min, maxY = axis.max) }

@Composable
private fun lineSpec(color: Color, filled: Boolean = false): LineCartesianLayer.Line =
    LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(fill(color)),
        stroke = LineCartesianLayer.LineStroke.Continuous(
            thicknessDp = LINE_THICKNESS_DP,
            cap = android.graphics.Paint.Cap.ROUND,
        ),
        areaFill = if (filled) {
            LineCartesianLayer.AreaFill.single(
                fill(
                    ShaderProvider.verticalGradient(
                        arrayOf(
                            color.copy(alpha = AREA_TOP_ALPHA),
                            color.copy(alpha = AREA_BOTTOM_ALPHA),
                        ),
                    ),
                ),
            )
        } else {
            null
        },
    )

/**
 * The Vico theme every chart on this tab uses.
 *
 * Its whole job is to make the axis furniture match the Health page: the same
 * `outline` at a quarter opacity for guidelines, the same `onSurfaceVariant`
 * for the labels. Left to `rememberM3VicoTheme`'s own defaults the dashboard
 * drew heavier guidelines in a different grey, which is most of why the two
 * tabs felt unrelated even where the charts were doing the same thing.
 */
@Composable
private fun tracksVicoTheme() = rememberM3VicoTheme(
    lineColor = MaterialTheme.colorScheme.outline.copy(alpha = GUIDELINE_ALPHA),
    textColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

/**
 * A gridline: solid, hairline, and the same grey the Health page uses.
 *
 * Vico's default guideline is **dashed**, which is the single biggest reason
 * this tab read as a different app. Dashes are a way of saying "this line is
 * not data" — a goal, a threshold, a projection — and this app already uses
 * them for exactly that: the sleep chart's average bedtime, the stress
 * chart's window average. Spending them on the background grid meant the two
 * kinds of line were indistinguishable, and the grid shouted while the
 * reference lines had nothing left to say.
 */
@Composable
private fun solidGuideline() = rememberAxisGuidelineComponent(
    fill = fill(MaterialTheme.colorScheme.outline.copy(alpha = GUIDELINE_ALPHA)),
    thickness = 1.dp,
    shape = com.patrykandpatrick.vico.core.common.shape.Shape.Rectangle,
)

/**
 * A vertical axis with one gutter width for every chart that uses it.
 *
 * ## Why the width is pinned rather than measured
 *
 * The training-load chart and the form chart are drawn as two hosts stacked
 * into one figure, sharing a single date axis at the bottom — which only works
 * if their plots start at the same x. Left to size themselves, they do not:
 * training load labels `0`–`85` and form labels `-30`–`20`, so the lower chart
 * reserved a wider gutter and its trace sat several points to the right of the
 * one it is meant to be read against. The zone bands underneath made it
 * obvious, since they stop where the plot does.
 *
 * A fixed width is the only thing that makes two independent hosts agree, and
 * it is wide enough for four characters and a minus sign at this type size.
 */
@Composable
private fun sharedStartAxis(ticks: Int, formatter: CartesianValueFormatter? = null) =
    VerticalAxis.rememberStart(
        guideline = solidGuideline(),
        size = axisGutter(),
        valueFormatter = formatter ?: RoundFormatter,
        itemPlacer = fixedTicks(ticks),
    )

/**
 * Whole numbers, and one decimal only where the step genuinely needs it.
 *
 * Vico's decimal formatter prints `78.46` for a tick that is only at 78.46
 * because the *count* was pinned; with [dataRange] rounding the bounds those
 * ticks are whole, and printing `78.0` for 78 is the same noise one place
 * further left.
 */
private val RoundFormatter = CartesianValueFormatter { _, value, _ ->
    if (value == kotlin.math.floor(value)) value.toLong().toString()
    else ((value * 10).toLong() / 10.0).toString()
}

/** The gridline count an axis asked for — see [niceAxis]. */
@Composable
private fun fixedTicks(count: Int) = remember(count) { VerticalAxis.ItemPlacer.count({ count }) }

/** The one gutter width, so charts stacked into a figure line up. */
@Composable
internal fun axisGutter() =
    com.patrykandpatrick.vico.core.cartesian.axis.BaseAxis.Size.fixed(AXIS_GUTTER)

// Copied from the Health page's charts rather than chosen here. See [lineSpec].
private const val LINE_THICKNESS_DP = 2f
private const val AREA_TOP_ALPHA = 0.28f
private const val AREA_BOTTOM_ALPHA = 0.02f
private const val GUIDELINE_ALPHA = 0.25f

/** Four digits and a minus sign at `labelSmall`. See [sharedStartAxis]. */
private val AXIS_GUTTER = 40.dp

/** Gridlines per vertical axis, everywhere. See [fixedTicks]. */
private const val AXIS_TICKS = 5

// ── Weekly volume ────────────────────────────────────────────────────────────

/**
 * One column per ISO week, with the hours behind them.
 *
 * ## Why two series and two axes
 *
 * Distance alone answers half the question. Two weeks of 60 km are not the same
 * week if one took four hours and the other took nine — that gap is the
 * difference between a flat road block and a mountain one, and it is invisible
 * in a bar chart of kilometres. The browser has drawn both since the beginning;
 * the phone plotted whichever of the two it felt like and never said which.
 *
 * They cannot share a scale. Kilometres run to three figures in a heavy week
 * and hours rarely reach fifteen, so one axis pins the duration line flat along
 * the floor — the same failure the fitness and form charts were split apart to
 * avoid. Hence a second axis on the right, and each layer bound to its own so
 * neither is scaled by the other's range.
 *
 * ## Except when there is no distance
 *
 * A month of strength work has none, and columns of zero behind a duration line
 * would be a chart mostly about nothing. In that case the hours become the
 * columns and the right axis is dropped, which is what this did before both
 * series were drawn.
 */
@Composable
fun WeeklyVolumeChart(points: List<WeeklyVolumePoint>, modifier: Modifier = Modifier) {
    if (points.isEmpty()) {
        ChartPlaceholder("No activities in this window.", modifier)
        return
    }

    val series = remember(points) { volumeSeries(points) }
    val columns = series.columns
    val hours = series.hours
    val byDistance = series.byDistance
    val withDuration = series.withDuration

    // Measured separately, then matched: the two scales are different units
    // and must round independently, but they have to end up wanting the same
    // number of gridlines or the chart has two grids laid over each other and
    // no line belongs to either reading. See [niceAxis].
    val leftAxis: NiceAxis
    val rightAxis: NiceAxis
    if (withDuration) {
        val distance = niceAxis(0.0, columns.max())
        val duration = niceAxis(0.0, hours.max())
        val ticks = maxOf(distance.ticks, duration.ticks)
        leftAxis = niceAxis(0.0, columns.max(), atLeastTicks = ticks)
        rightAxis = niceAxis(0.0, hours.max(), atLeastTicks = ticks)
    } else {
        leftAxis = niceAxis(0.0, columns.max())
        rightAxis = leftAxis
    }
    val barColor = MaterialTheme.colorScheme.primary
    // The accent's complement, not another accent slot: tertiary follows the
    // accent too, so the duration line was drawn in the bars' own colour.
    val lineColor = complementOf(barColor, dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f)

    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(points, withDuration) {
        producer.runTransaction {
            columnSeries { series(columns) }
            // The line is a second layer rather than a second column series:
            // grouped columns would halve the width of both and say the two
            // measure the same thing, which is exactly what they do not.
            if (withDuration) lineSeries { series(hours) }
            axisDates(points.map { it.weekStart })
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (withDuration) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LegendDot("Distance (km)", barColor)
                LegendDot("Duration (h)", lineColor)
            }
        }
        ProvideVicoTheme(tracksVicoTheme()) {
            CartesianChartHost(
                chart = rememberCartesianChart(
                    rememberColumnCartesianLayer(
                        // Anchored at zero and topped by the tallest column, so
                        // filtering to one sport rescales instead of leaving
                        // every bar as a stub under a fixed 120.
                        rangeProvider = dataRange(leftAxis),
                        columnProvider = ColumnCartesianLayer.ColumnProvider.series(
                            rememberLineComponent(
                                fill = fill(barColor),
                                // Scaled to the window rather than fixed. A
                                // 7-day window is one column and a lifetime is
                                // hundreds; a single width made the first look
                                // like a stray tick and the last a solid block.
                                thickness = columnThickness(columns.size),
                                shape = CorneredShape.rounded(topLeftPercent = 30, topRightPercent = 30),
                            ),
                        ),
                        verticalAxisPosition = Axis.Position.Vertical.Start,
                    ),
                    *if (withDuration) {
                        arrayOf(
                            rememberLineCartesianLayer(
                                lineProvider = LineCartesianLayer.LineProvider.series(
                                    // Unfilled, unlike the load chart's lines: an
                                    // area under this one would wash over the
                                    // columns it is drawn across.
                                    lineSpec(lineColor),
                                ),
                                rangeProvider = dataRange(rightAxis),
                                verticalAxisPosition = Axis.Position.Vertical.End,
                            )
                        )
                    } else {
                        emptyArray()
                    },
                    startAxis = sharedStartAxis(
                        ticks = leftAxis.ticks,
                        formatter = if (byDistance) KilometreFormatter else HourFormatter,
                    ),
                    // Present only when something is bound to it. An empty axis
                    // is a scale for nothing, and it would still eat the width.
                    //
                    // No guideline of its own: it would draw a second set of
                    // horizontal lines at the *duration* scale's ticks, laid
                    // over the distance scale's, and two grids at once is a
                    // grid nobody can read either of.
                    endAxis = if (withDuration) {
                        VerticalAxis.rememberEnd(
                            valueFormatter = HourFormatter,
                            guideline = null,
                            size = axisGutter(),
                            // The same count as the left, so the one grid
                            // serves both scales — see [fixedTicks].
                            itemPlacer = fixedTicks(rightAxis.ticks),
                        )
                    } else {
                        null
                    },
                    bottomAxis = HorizontalAxis.rememberBottom(
                        valueFormatter = DateAxisFormatter,
                        itemPlacer = thinnedLabels(),
                        // The vertical grid the columns already stand on. Bars
                        // have their own edges; a line behind every one of them
                        // is ink that says what the bar said.
                        guideline = null,
                    ),
                ),
                modelProducer = producer,
                scrollState = fittedScrollState(),
                zoomState = fittedZoomState(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
            )
        }
        Text(
            "${points.size} ${if (points.size == 1) "week" else "weeks"} " +
                "\u00b7 ${if (byDistance) "km" else "hours"} per week",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Which numbers this chart draws, and on which axes. */
internal data class VolumeSeries(
    /** What the columns plot — kilometres, or hours when there is no distance. */
    val columns: List<Double>,
    val hours: List<Double>,
    val byDistance: Boolean,
    /** Whether the duration line and its right-hand axis are drawn at all. */
    val withDuration: Boolean,
)

/**
 * Decide what to plot.
 *
 * Pulled out of the chart because the rule is not obvious and is easy to break
 * without noticing: a window with no distance in it — a month of strength work
 * — plots its hours as the columns instead, and must not then draw the same
 * hours a second time as a line against a second axis.
 */
internal fun volumeSeries(points: List<WeeklyVolumePoint>): VolumeSeries {
    val distances = points.map { it.distanceKm ?: 0.0 }
    val hours = points.map { it.durationHours ?: 0.0 }
    val byDistance = distances.any { it > 0 }
    return VolumeSeries(
        columns = if (byDistance) distances else hours,
        hours = hours,
        byDistance = byDistance,
        // Only worth its own axis when the columns are something else and
        // there is something to draw on it.
        withDuration = byDistance && hours.any { it > 0 },
    )
}

/**
 * Axis labels that carry their unit.
 *
 * Two vertical axes with bare numbers on them is a puzzle: 40 on the left and 8
 * on the right look like the same kind of quantity at different scales. The
 * unit is what says they are not.
 */
private val KilometreFormatter = CartesianValueFormatter { _, value, _ ->
    "${value.roundToInt()}km"
}

private val HourFormatter = CartesianValueFormatter { _, value, _ ->
    "${value.roundToInt()}h"
}

/** Wide columns when there are few weeks, thin ones when there are many. */
private fun columnThickness(count: Int): androidx.compose.ui.unit.Dp = when {
    count <= 8 -> 20.dp
    count <= 20 -> 14.dp
    // A year is 52 weeks, which is the case this chart is most often read in
    // and the one the old 6dp served worst: at 6dp the columns were narrower
    // than the line drawn across them, so the *duration* read as the subject
    // and the distance as speckle behind it. A column should look like a
    // column at every window; the widths below fill roughly two thirds of each
    // week's slot at every phone size this app targets.
    count <= 60 -> 9.dp
    count <= 120 -> 5.dp
    else -> 3.dp
}

// ── Radial gauge (hand-drawn — Vico has no such thing) ───────────────────────

/**
 * One classification band on a [RadialGauge].
 *
 * The scales are the web app's, not the spec's, and deliberately so: readiness
 * and VO₂max classifications are presentation — a way of saying whether 47 is
 * good — and `spec/` governs the numbers both clients *calculate*, not the
 * adjectives they put next to them. They are duplicated from
 * `ReadinessWidget.jsx` and `Vo2MaxWidget.jsx` so the two clients agree about
 * what "Excellent" means.
 */
data class GaugeZone(val label: String, val min: Double, val max: Double, val color: Color)

/** Readiness, 0–100. Edges at 60 and 80 mirror the backend's own thresholds. */
val READINESS_ZONES = listOf(
    GaugeZone("Low", 0.0, 40.0, Color(0xFFEF4444)),
    GaugeZone("Fair", 40.0, 60.0, Color(0xFFF97316)),
    GaugeZone("Moderate", 60.0, 80.0, Color(0xFFEAB308)),
    GaugeZone("Good", 80.0, 92.0, Color(0xFF22C55E)),
    GaugeZone("Prime", 92.0, 100.0, Color(0xFF14B8A6)),
)

/** VO₂max, 20–80. A scale with real units, so the gauge does not start at zero. */
val VO2MAX_ZONES = listOf(
    GaugeZone("Poor", 20.0, 34.0, Color(0xFFEF4444)),
    GaugeZone("Fair", 34.0, 42.0, Color(0xFFF97316)),
    GaugeZone("Good", 42.0, 50.0, Color(0xFFEAB308)),
    GaugeZone("Excellent", 50.0, 57.0, Color(0xFF22C55E)),
    GaugeZone("Superior", 57.0, 65.0, Color(0xFF06B6D4)),
    GaugeZone("Elite", 65.0, 80.0, Color(0xFFA855F7)),
)

/**
 * How far past the top of the scale a value sits, as an amount to draw on the
 * second ring — or null when it is still on the first.
 *
 * Saturated at one full span rather than counted in laps. Three rings would
 * mean reserving room for a level almost nobody reaches on every dial in the
 * app, and the figure in the middle is the real number regardless: the shape's
 * job past two laps is only to say "well over", which a full outer ring says.
 */
internal fun overflowOf(value: Double?, min: Double, max: Double): Double? {
    val span = max - min
    if (value == null || span <= 0.0) return null
    return (value - max).coerceIn(0.0, span).takeIf { it > 0.0 }
}

/** The band a value falls in, or a neutral one when there is no value. */
fun zoneFor(value: Double?, zones: List<GaugeZone>): GaugeZone {
    if (value == null) return GaugeZone("—", 0.0, 0.0, Color(0xFF94A3B8))
    return zones.firstOrNull { value < it.max } ?: zones.last()
}

/**
 * A value on a 270° arc, over its classification bands.
 *
 * A port of the web app's `RadialGauge.jsx`, and it replaces a plain one-colour
 * arc that showed a number against nothing. The bands are the point: 47 means
 * nothing on its own and means "Good, near the top of it" once the scale is
 * drawn underneath. A single-colour arc could show the same 47 and say nothing.
 *
 * Not a full ring: an open arc has a visible start and end, so a value near the
 * minimum cannot be mistaken for one near the maximum the way it can on a ring
 * that closes on itself.
 *
 * Three layers, as on the web — a dim track, the bands at low opacity, then the
 * bands again at full opacity but clipped to the current value, so the fill
 * *is* the scale rather than a separate colour laid over it.
 *
 * ## Going past the end
 *
 * Every dial here has a top, and some of them are passed regularly: a long day
 * beats a step goal, a hard session beats a calorie one. Clamping is what this
 * used to do, and it turns 24,000 steps and 15,000 steps into the same picture
 * on the page whose whole job is telling them apart.
 *
 * So the arc laps. Past the maximum, a second ring appears outside the first
 * and fills through the same bands again, while the first stays full underneath
 * — the stamina wheel from Breath of the Wild, and for the same reason: it
 * reads instantly as "that, and then some more" rather than as a different
 * quantity. The room for it is always reserved and it draws nothing until it is
 * needed, so a dial does not resize the moment somebody crosses their goal.
 *
 * Two rings is where it stops. A third lap keeps the outer one full rather than
 * nesting further — the figure in the middle is still the real number, and by
 * then the shape has said all it usefully can.
 */
@Composable
fun RadialGauge(
    value: Double?,
    zones: List<GaugeZone>,
    label: String,
    modifier: Modifier = Modifier,
    decimals: Int = 0,
    /** The unit, small beside the figure. Empty for counts. */
    unit: String = "",
    /**
     * How big to draw it. The dashboard shows two of these side by side and
     * wants them large; the Health page shows a dozen in a grid and wants them
     * small. Same instrument, same reading, different room.
     */
    size: androidx.compose.ui.unit.Dp = GAUGE_SIZE,
    stroke: androidx.compose.ui.unit.Dp = GAUGE_STROKE,
    /** Replaces the band's name under the figure, where that says more. */
    caption: String? = null,
    /**
     * The caption's colour, when the band under the value is the wrong one to
     * borrow — a stacked arc's bands are its parts, so the word beside the
     * total has to be coloured by the scale that produced it.
     */
    captionColor: Color? = null,
    /**
     * Opens this metric's history. Null leaves the dial inert, which is what the
     * dials on screens with nothing to open want.
     */
    onClick: (() -> Unit)? = null,
    /**
     * The figure in the middle, already formatted, in place of [value] with
     * [decimals]. For readings that are not a plain number — a pace, a clock,
     * a distance in the account's units — on the run screen, where the arc
     * still needs [value] to know how far to fill.
     */
    figure: String? = null,
    /** The figure's size; the run screen's two headline dials want it larger. */
    figureStyle: androidx.compose.ui.text.TextStyle? = null,
) {
    val scaleMin = zones.first().min
    val scaleMax = zones.last().max
    val zone = zoneFor(value, zones)
    val trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val indicatorColor = MaterialTheme.colorScheme.onSurface

    // Degrees, in Compose's frame: 0 is 3 o'clock and angles run clockwise, so
    // 135 is 7.5 o'clock — the same start the web app's 225 resolves to once
    // its own -90 offset is applied. The 90° gap sits at the bottom.
    fun degreeFor(v: Double): Float {
        val clamped = v.coerceIn(scaleMin, scaleMax)
        return GAUGE_START + ((clamped - scaleMin) / (scaleMax - scaleMin) * GAUGE_SPAN).toFloat()
    }

    val filledTo = value?.let(::degreeFor)

    val overflowTo = overflowOf(value, scaleMin, scaleMax)?.let { degreeFor(scaleMin + it) }

    Column(
        // The whole dial is the target, label included — a 100dp arc is a
        // generous one, and a small hit area on a number this deliberate would
        // be the thing that made people think it was not tappable.
        modifier.then(
            if (onClick != null) {
                Modifier.clip(RoundedCornerShape(Tokens.Radius.xl)).clickable(onClick = onClick)
            } else {
                Modifier
            }
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(size)) {
                val strokeStyle = Stroke(width = stroke.toPx(), cap = StrokeCap.Butt)
                val lapStroke = Stroke(width = stroke.toPx() * LAP_STROKE, cap = StrokeCap.Butt)
                // The outer lap's room, held back whether or not it is used.
                val reserved = lapStroke.width + LAP_GAP.toPx()

                /** One ring's geometry, [out] rings out from the main one. */
                fun ring(out: Int, width: Float): Pair<Offset, androidx.compose.ui.geometry.Size> {
                    val inset = if (out > 0) width / 2f else reserved + width / 2f
                    return Offset(inset, inset) to androidx.compose.ui.geometry.Size(
                        this.size.width - inset * 2f,
                        this.size.height - inset * 2f,
                    )
                }

                val (topLeft, arcSize) = ring(0, strokeStyle.width)

                drawArc(
                    color = trackColor,
                    startAngle = GAUGE_START,
                    sweepAngle = GAUGE_SPAN,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = strokeStyle,
                )

                zones.forEach { band ->
                    val start = degreeFor(band.min) + GAUGE_GAP
                    val end = degreeFor(band.max) - GAUGE_GAP
                    if (end <= start) return@forEach

                    // The scale, dimmed.
                    drawArc(
                        color = band.color.copy(alpha = 0.35f),
                        startAngle = start,
                        sweepAngle = end - start,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = strokeStyle,
                    )

                    // The same band at full strength, clipped to the value —
                    // or filled outright once the value has lapped past it.
                    val filledEnd = if (overflowTo != null) end else filledTo?.coerceAtMost(end)
                    if (filledEnd == null || filledEnd <= start) return@forEach
                    drawArc(
                        color = band.color,
                        startAngle = start,
                        sweepAngle = filledEnd - start,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = strokeStyle,
                    )
                }

                // The second lap, outside the first. No track behind it: an
                // empty outer ring drawn on every dial would be a permanent
                // promise of a level nobody has reached.
                if (overflowTo != null) {
                    val (lapTopLeft, lapSize) = ring(1, lapStroke.width)
                    zones.forEach { band ->
                        val start = degreeFor(band.min) + GAUGE_GAP
                        val end = (degreeFor(band.max) - GAUGE_GAP).coerceAtMost(overflowTo)
                        if (end <= start) return@forEach
                        drawArc(
                            color = band.color,
                            startAngle = start,
                            sweepAngle = end - start,
                            useCenter = false,
                            topLeft = lapTopLeft,
                            size = lapSize,
                            style = lapStroke,
                        )
                    }
                }

                // A dot at the value, so the exact position is readable even
                // where two bands meet and the colours are similar. On the ring
                // the value is actually on, which past the top is the outer one.
                val marker = overflowTo ?: filledTo
                marker?.let { degrees ->
                    val onLap = overflowTo != null
                    val width = if (onLap) lapStroke.width else strokeStyle.width
                    val radius = if (onLap) {
                        (this.size.width - width) / 2f
                    } else {
                        (this.size.width - width) / 2f - reserved
                    }
                    val radians = Math.toRadians(degrees.toDouble())
                    drawCircle(
                        color = indicatorColor,
                        radius = width / 2f + 1.5f,
                        center = Offset(
                            x = this.size.width / 2f + (radius * kotlin.math.cos(radians)).toFloat(),
                            y = this.size.height / 2f + (radius * kotlin.math.sin(radians)).toFloat(),
                        ),
                    )
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        figure ?: value?.let { gaugeFigure(it, decimals) } ?: "—",
                        style = figureStyle ?: MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (unit.isNotEmpty() && value != null) {
                        Text(
                            unit,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 2.dp, bottom = 3.dp),
                        )
                    }
                }
                // Only when there is a reading to classify. With none, the
                // figure is already an em dash and a second one under it reads
                // as a broken label rather than as absence.
                if (value != null) {
                    Text(
                        caption ?: zone.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = captionColor ?: zone.color,
                        maxLines = 1,
                    )
                }
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * The box, not the arc. It carries the reserved lap outside the main ring, so
 * these are a little larger than the ring they draw — see [RadialGauge].
 */
private val GAUGE_SIZE = 122.dp
private val GAUGE_STROKE = 9.dp

/** The lap ring, as a fraction of the main stroke: present, clearly secondary. */
private const val LAP_STROKE = 0.45f
private val LAP_GAP = 2.dp

/** 7.5 o'clock, sweeping 270° clockwise and leaving the gap at the bottom. */
private const val GAUGE_START = 135f
private const val GAUGE_SPAN = 270f

/** A degree and a half between bands, so adjacent colours stay distinct. */
private const val GAUGE_GAP = 1.5f

private fun gaugeFigure(value: Double, decimals: Int): String =
    if (decimals == 0) {
        "${value.roundToInt()}"
    } else {
        val rounded = kotlin.math.round(value * 10) / 10
        if (rounded % 1.0 == 0.0) "${rounded.toInt()}" else "$rounded"
    }

// ── Shared bits ──────────────────────────────────────────────────────────────

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(Modifier.size(7.dp), shape = CircleShape, color = color) {}
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun ChartPlaceholder(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(100.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
