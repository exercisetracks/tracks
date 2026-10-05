// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.health

import com.tracks.app.ui.theme.Tokens
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tracks.app.ui.components.Explain
import com.tracks.app.ui.components.InfoHeading
import com.tracks.app.ui.components.ChartScale
import com.tracks.app.ui.components.measureGutter
import com.tracks.app.ui.components.drawYAxis
import androidx.compose.ui.text.rememberTextMeasurer
import com.tracks.core.api.SleepNight
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * The score's colour — amber, as the web app draws it.
 *
 * The stage colours live in [com.tracks.app.ui.health.DEEP] and friends, shared
 * with the Today card: the colour *is* the stage's name once a reader has
 * learned it, so the two views must not disagree.
 */
private val SCORE_LINE = Color(0xFFF59E0B)

/** One night, reduced to what both sleep charts need. */
data class SleepNightSummary(
    val date: String,
    /** Time *asleep*. Waking is counted separately and never folded in. */
    val total: Double,
    val deep: Double,
    val rem: Double,
    val light: Double,
    /** Time in bed and awake, as the watch measured it. Null before it was kept. */
    val awake: Double?,
    val score: Double?,
    /**
     * When the night ran from and to — ISO-8601, as [DailyMetricFull] carries
     * it.
     *
     * Null for every night whose totals exist but whose stage timeline was
     * never kept, which is a normal answer and the reason the history chart
     * has to say how many nights it could not place rather than silently
     * dropping them.
     */
    val startAt: String? = null,
    val endAt: String? = null,
) {
    /**
     * The awake band on a chart: what the watch measured, or what is left over.
     *
     * The fallback matters for every night recorded before the parser began
     * keeping awake time — there the only evidence of waking is the gap between
     * the night's total and its stages, which is usually rounding and
     * occasionally an hour of staring at the ceiling. Once measured, the real
     * figure is used and the leftover is ignored.
     */
    val restless: Double
        get() = awake ?: (total - deep - rem - light).coerceAtLeast(0.0)

    /** Asleep plus awake — the span the night actually occupied. */
    val inBed: Double get() = total + restless
}

/**
 * The sleep history, behind the Sleep dial.
 *
 * The stages on a clock rather than a line of total hours: when a night
 * happened and what it was made of are both reasons to look at sleep history,
 * and the sheet is the one place with room to draw either.
 *
 * It keeps its tap. Choosing a night is the only way to change the hypnogram on
 * the page now that the chart lives here, so the bars stay live and the
 * selection outlives the sheet being dismissed.
 *
 * Nights the chart cannot place are counted here rather than swallowed. A
 * night imported before the server kept stage timelines has real totals and no
 * hours, and a chart that silently dropped it would show a gap indistinguishable
 * from a night nobody slept.
 */
@Composable
fun SleepHistoryPanel(
    nights: List<SleepNightSummary>,
    windowStart: LocalDate?,
    selected: String? = null,
    /** The chosen night's stage timeline, when the server has one for it. */
    night: SleepNight? = null,
    onSelect: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (nights.isEmpty()) return
    val chosen = nights.firstOrNull { it.date == selected } ?: nights.last()
    // Nights that parse and carry a clock, each with its place on the axis.
    // Anything with an unreadable date is dropped rather than drawn at
    // position zero.
    val dayAxis = remember(nights, windowStart) {
        DayAxis.of(nights.map { it.date }, windowStart)
    }
    val placed = remember(nights, dayAxis) {
        val axis = dayAxis ?: return@remember emptyList()
        nights.mapNotNull { entry ->
            val day = DayAxis.parseDay(entry.date) ?: return@mapNotNull null
            val fraction = axis.fraction(day) ?: return@mapNotNull null
            val clock = nightClock(entry) ?: return@mapNotNull null
            Placed(entry, fraction, day, clock)
        }
    }
    val untimed = nights.size - placed.size

    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // The chosen night first, and the run of nights under it.
        //
        // This used to live on the page below the dials, which meant the chart
        // that *chooses* a night was in a sheet and the thing it chose was
        // behind the sheet — so tapping a bar looked like it did nothing at
        // all. Question and answer belong in the same place, and the answer
        // goes on top because it is the one somebody opened the sheet for.
        SleepNightCard(
            summary = chosen,
            night = night?.takeIf { it.date == chosen.date && it.stages.isNotEmpty() },
        )

        if (placed.isEmpty() || dayAxis == null) {
            Text(
                "No night in this window has recorded times yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            SleepHistoryChart(
                placed = placed,
                dayAxis = dayAxis,
                selected = selected,
                windowStart = windowStart,
                onSelect = onSelect,
            )
            StageLegend()
        }
        if (untimed > 0) {
            Text(
                if (untimed == 1) {
                    "1 night has totals but no recorded times, so it is not on the chart."
                } else {
                    "$untimed nights have totals but no recorded times, " +
                        "so they are not on the chart."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A run of nights on a clock, over the whole window.
 *
 * ## Why the y axis is a time of day and not a number of hours
 *
 * Because the chart used to answer half its question. Stacked bars measured
 * from zero say how *much* somebody slept, which is one of the two things
 * anybody looks at a fortnight of sleep to find out. The other is *when* — a
 * run of seven-hour nights that started at eleven and a run of seven-hour
 * nights that wandered between ten and three are the same chart under a
 * duration axis and completely different weeks to live through, and shift
 * work, jet lag and a creeping bedtime are all invisible in the first reading.
 *
 * So the axis is the clock. It fits itself to the hours actually slept across
 * the window — ten at night to eight in the morning, say, rather than a fixed
 * midnight-to-midnight that would spend two thirds of its height on daytime —
 * and each night is drawn as the block of time it occupied, top at lights out
 * and bottom at waking. The height is still the duration, so nothing is lost:
 * a short night is a short bar exactly as before, and now it is also a bar
 * that starts where it started.
 *
 * Two dashed lines mark the average bedtime and the average waking. They are
 * what turns "when" into a verdict — consistency is the thing being read for,
 * and the eye judges it far better against a reference than in the abstract.
 *
 * The average is a plain arithmetic mean and can be taken as one because the
 * hours are signed against each night's *own* midnight: a bedtime before it is
 * negative and one after is positive, so 23:30 and 00:30 average to midnight
 * rather than to noon, which is what a wrapped 0–24 clock would have made of
 * them.
 *
 * ## The stages inside the block
 *
 * Deep, then REM, then light, then waking, top to bottom — the same fixed
 * order the dial's arc and the legend use, so a colour means a stage
 * everywhere on this screen. The order is not a claim about *when* each stage
 * happened: only the hypnogram knows that, and it is one tap away on the page
 * below. What the fixed order buys is comparability — the depth of the indigo
 * band is the same measurement from night to night.
 *
 * The parts are scaled to fill the block exactly. A night's stage totals and
 * the span between its first and last record differ by whatever the watch
 * could not classify, and leaving that as a gap at the bottom of every bar
 * would read as waking rather than as arithmetic.
 *
 * ## Nights sit where they happened
 *
 * Bars are placed by date, not by position in the list — see [DayAxis]. A
 * night the watch did not record is blank space of the right width, because
 * the alternative is drawing a fortnight of three recorded nights as three
 * neighbours and claiming a run that never happened.
 *
 * A night whose totals survived but whose timeline did not cannot be placed on
 * a clock at all. Those are counted and reported under the chart rather than
 * dropped in silence — see [SleepHistoryPanel].
 */
@Composable
private fun SleepHistoryChart(
    placed: List<Placed>,
    /**
     * The axis the [Placed] fractions were measured against.
     *
     * Passed in rather than rebuilt here, and not merely to save the work: an
     * axis built from the *placed* nights is a different axis whenever a night
     * was dropped for having no clock, and its day width would then size bars
     * whose positions came from the other one.
     */
    dayAxis: DayAxis,
    selected: String?,
    windowStart: LocalDate?,
    onSelect: (String) -> Unit,
) {
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    // Both a shade stronger than the app's usual chart furniture, and for the
    // same reason: on a clock axis the gridline *is* the reading — a bar's top
    // means nothing except against the hour beside it — where on a chart of
    // quantities the gridline is only a convenience.
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val habitColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.85f)
    if (placed.isEmpty()) return

    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall

    val scale = remember(placed) {
        clockScale(placed.minOf { it.clock.from }, placed.maxOf { it.clock.to })
    }
    val format: (Double) -> String = remember { { hours -> clockLabel(-hours) } }
    // In composition, because the tap handler needs it too and the draw phase
    // is no place to produce a number the layout depends on.
    val gutterPx = remember(scale, labelStyle) {
        measureGutter(scale, measurer, labelStyle, format)
    }
    val habit = remember(placed) {
        NightClock(
            from = placed.map { it.clock.from }.average(),
            to = placed.map { it.clock.to }.average(),
        )
    }
    val scored = remember(placed) { placed.filter { it.night.score != null } }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(CHART_HEIGHT + DATE_AXIS_HEIGHT)
                .pointerInput(placed) {
                    detectTaps { x ->
                        // Nearest by position, not by slot: the bars are no
                        // longer evenly spaced, so dividing the width into
                        // equal buckets would select the wrong night on any
                        // chart with a gap in it.
                        val fraction = ((x - gutterPx) / (size.width - gutterPx))
                            .coerceIn(0f, 1f)
                        placed.minByOrNull { kotlin.math.abs(it.fraction - fraction) }
                            ?.let { onSelect(it.night.date) }
                    }
                },
        ) {
            val gutter = gutterPx
            val plotHeight = size.height - DATE_AXIS_HEIGHT.toPx()

            drawYAxis(
                scale = scale,
                measurer = measurer,
                style = labelStyle,
                labelColor = axis,
                gridColor = gridColor,
                plotHeight = plotHeight,
                gutter = gutter,
                format = format,
            )
            // Everything after the axis belongs to the plot, which starts where
            // the labels end. Measuring the bars against the whole canvas put
            // the oldest night on top of the y axis and squeezed a day's slot
            // by the width of the widest label.
            val plotWidth = (size.width - gutter).coerceAtLeast(1f)
            // From the day slot, never from the number of nights — see
            // DayAxis.barWidth for what that mistake looked like.
            val barWidth =
                dayAxis.barWidth(plotWidth, MIN_BAR_PX.toPx(), MAX_BAR_PX.toPx())
            val radius = CornerRadius(2.dp.toPx(), 2.dp.toPx())

            /** A time of day, as a height. Earliest at the top — see [scale]. */
            fun y(hours: Double) = plotHeight * (1f - scale.fraction(-hours))
            // Inset so the first and last bars sit fully inside the plot.
            fun x(fraction: Float) = gutter + dayAxis.centre(fraction, plotWidth, barWidth)

            // Average bedtime and average waking, behind the nights. A
            // reference rather than a reading, and the thing a run of bars is
            // actually being compared against — consistency is what somebody
            // opens a fortnight of sleep to judge, and the eye judges it far
            // better against a line than in the abstract. Drawn firmly enough
            // to be found without hunting, dashed so it cannot be mistaken for
            // a night.
            listOf(habit.from, habit.to).forEach { hours ->
                drawLine(
                    color = habitColor,
                    start = Offset(gutter, y(hours)),
                    end = Offset(size.width, y(hours)),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                )
            }

            placed.forEach { spot ->
                val entry = spot.night
                val left = x(spot.fraction) - barWidth / 2
                val top = y(spot.clock.from)
                val bottom = y(spot.clock.to)
                val span = (bottom - top).coerceAtLeast(MIN_BAR_HEIGHT.toPx())

                val parts = listOf(
                    entry.deep to DEEP,
                    entry.rem to REM,
                    entry.light to LIGHT,
                    entry.restless to AWAKE,
                ).filter { it.first > 0 }
                val measured = parts.sumOf { it.first }

                if (parts.isEmpty() || measured <= 0.0) {
                    // Timed but never staged. The block is still the truth
                    // about when the night happened, and drawing nothing for
                    // it would be a gap that means "no sleep".
                    drawRoundRect(
                        color = LIGHT.copy(alpha = 0.45f),
                        topLeft = Offset(left, top),
                        size = Size(barWidth, span),
                        cornerRadius = radius,
                    )
                } else {
                    // Scaled to the block rather than drawn at their own
                    // height: whatever the watch could not classify is the
                    // difference between the two, and left as a gap at the
                    // foot of every bar it would read as an hour awake.
                    val perHour = span / measured
                    var at = top
                    parts.forEach { (hours, colour) ->
                        val height = (hours * perHour).toFloat()
                        drawRoundRect(
                            // Every night at full strength. Dimming the
                            // unselected ones sounded right and read badly: a
                            // night is always selected — the newest one, by
                            // default — so the chart spent its whole life with
                            // every bar but one at half opacity, and on a long
                            // window where the bars are already hairlines that
                            // left most of the history barely visible. The
                            // marker below is what says which night is chosen.
                            color = colour,
                            topLeft = Offset(left, at),
                            size = Size(barWidth, height),
                            cornerRadius = radius,
                        )
                        at += height
                    }
                }

                // The chosen night is marked under the bar rather than by
                // recolouring it: the colours already mean the stages.
                if (entry.date == selected) {
                    // Given its own minimum width: on a long window the bar
                    // itself is a hairline, and a hairline underline is not
                    // a selection anyone can see.
                    val markWidth = barWidth.coerceAtLeast(MARK_WIDTH.toPx())
                    drawRoundRect(
                        color = axis,
                        topLeft = Offset(
                            x(spot.fraction) - markWidth / 2,
                            plotHeight - 2.dp.toPx(),
                        ),
                        size = Size(markWidth, 2.dp.toPx()),
                        cornerRadius = radius,
                    )
                }
            }

            // The score, riding over the nights on its own 0–100 scale: full
            // height is 100 and the floor is nothing. It has no business on an
            // axis measured in hours and it is drawn anyway, because the
            // question it answers — "and were they any good" — is asked in the
            // same glance as "when were they", and a chart is the only place
            // to answer both at once. Amber and thin so it reads as an overlay
            // rather than as a fifth stage, and the legend names it.
            scored.forEachIndexed { index, spot ->
                val score = spot.night.score ?: return@forEachIndexed
                val here = Offset(x(spot.fraction), scoreY(score, plotHeight))
                val next = scored.getOrNull(index + 1)
                // Joined only to the following night, never across a gap: a
                // straight segment from Monday to Friday draws three nights of
                // data that were never recorded.
                if (next?.night?.score != null &&
                    java.time.temporal.ChronoUnit.DAYS.between(spot.day, next.day) == 1L
                ) {
                    drawLine(
                        color = SCORE_LINE,
                        start = here,
                        end = Offset(x(next.fraction), scoreY(next.night.score, plotHeight)),
                        strokeWidth = 1.5.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
                // A dot on every scored night, so a night with no neighbour is
                // still on the chart rather than an invisible endpoint.
                drawCircle(color = SCORE_LINE, radius = 2.dp.toPx(), center = here)
            }

            drawDateAxis(
                axis = dayAxis,
                measurer = measurer,
                style = labelStyle,
                color = axis,
                plotLeft = gutter,
                plotWidth = plotWidth,
                top = plotHeight + DATE_LABEL_GAP.toPx(),
            )
        }

        Text(
            // Named, because two unexplained dashed lines are furniture and two
            // lines that say "this is your usual night" are the point of the
            // chart.
            "avg ${clockLabel(habit.from)}–${clockLabel(habit.to)} · tap a night",
            style = MaterialTheme.typography.labelSmall,
            color = axis,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/**
 * Where a 0–100 score sits on a plot whose axis means something else.
 *
 * Full height is 100. Deliberately not tied to [ChartScale]: the score does
 * not share the clock's units and pretending otherwise — by giving it ticks,
 * say — would invite the reading that a score of 80 means eight in the
 * evening.
 */
private fun scoreY(score: Double, plotHeight: Float): Float =
    (plotHeight * (1 - score.coerceIn(0.0, 100.0) / 100.0)).toFloat()

/** A night and where it lands on both axes. */
private data class Placed(
    val night: SleepNightSummary,
    val fraction: Float,
    val day: LocalDate,
    val clock: NightClock,
)

// ── The clock ────────────────────────────────────────────────────────────────

/**
 * A night's hours, measured from local midnight of the day it is filed under.
 *
 * Garmin names a night by the morning it ended, so the reference is the *end*
 * of the night and going to bed is a negative number: `-1.5` is half past ten
 * the previous evening, `6.75` is a quarter to seven. That is deliberately not
 * a wrapped 0–24 clock — a bedtime of 23:00 and one of 01:00 are two hours
 * apart and a wrapped axis would draw them at opposite ends of the chart,
 * which is the one shape this whole view exists to make legible.
 */
internal data class NightClock(val from: Double, val to: Double)

/**
 * When a night ran, in the zone the phone is standing in.
 *
 * The phone's zone rather than the watch's: the stamps are UTC and carry no
 * memory of where they were recorded, and "eleven at night" is a fact about
 * where somebody was, not about where the server runs. A night on the far side
 * of a flight is therefore drawn in the reader's current hours, which is the
 * same convention the hypnogram has always used.
 *
 * Null when the night has no timeline, or when its stamps do not parse, or
 * when they run backwards — all of which are "cannot place this night" rather
 * than "no night", and the chart says so.
 */
internal fun nightClock(
    night: SleepNightSummary,
    zone: ZoneId = ZoneId.systemDefault(),
): NightClock? {
    val date = DayAxis.parseDay(night.date) ?: return null
    // Read in [zone], not the system's: the night is plotted against midnight
    // in [zone], and a stamp with no offset read in a different zone lands
    // hours off its own axis.
    val from = night.startAt?.let { epochOf(it, zone) } ?: return null
    val to = night.endAt?.let { epochOf(it, zone) } ?: return null
    if (to <= from) return null
    val midnight = date.atStartOfDay(zone).toEpochSecond()
    return NightClock(
        from = (from - midnight) / 3600.0,
        to = (to - midnight) / 3600.0,
    )
}

/**
 * A vertical axis for a clock, with its ticks on hours people count in.
 *
 * ## Why not [chartScale]
 *
 * Because its steps are 1, 2, 2.5, 5 and 10 times a power of ten, which are
 * the numbers people count *quantities* in and not the numbers they count
 * hours in. A night spanning nine hours got a 2.5-hour step, so the axis was
 * labelled at 21:30, 00:00, 02:30, 05:00 — every other label at half past,
 * which reads as an axis that has slipped. Twelve and a half hours got a step
 * of five, which is worse: an axis two thirds empty, labelled at hours with no
 * relationship to anything.
 *
 * Clocks divide by 1, 2, 3, 4, 6 and 12, so those are the steps, and the
 * bounds are rounded outwards to them — which also means the axis hugs the
 * nights rather than padding them out to the next power of ten.
 *
 * Returned already negated, because [ChartScale] puts its maximum at the top
 * of a plot and a night reads downwards from lights out to waking. Only the
 * label formatter negates back.
 */
internal fun clockScale(earliest: Double, latest: Double): ChartScale {
    val span = (latest - earliest).coerceAtLeast(1.0)
    val step = CLOCK_STEPS.firstOrNull { span / it <= CLOCK_TICKS } ?: CLOCK_STEPS.last()
    val low = kotlin.math.floor(earliest / step) * step
    // At least one whole step tall. A window whose nights all began and ended
    // on the same hour rounds to a single tick, and an axis with no extent
    // draws every bar at the same height — or, given the chance, divides by
    // zero on the way there.
    val high = kotlin.math.ceil(latest / step).let {
        if (it * step <= low) low + step else it * step
    }
    val ticks = generateSequence(low) { it + step }
        .takeWhile { it <= high + step * 0.001 }
        .map { -it }
        .toList()
        .sorted()
    return ChartScale(min = -high, max = -low, ticks = ticks)
}

/** The divisions of a day, in the order an axis should try them. */
private val CLOCK_STEPS = listOf(1.0, 2.0, 3.0, 4.0, 6.0, 12.0)

/** Above this the labels crowd; below it the axis is coarser than the data. */
private const val CLOCK_TICKS = 6

/**
 * Hours from midnight as a time of day — `-1.5` is `22:30`.
 *
 * Wrapped only here, at the point of writing a label, and never in the
 * geometry: see [NightClock] for why an axis that wrapped would put a bedtime
 * of 23:00 and one of 01:00 at opposite ends of the same chart.
 */
internal fun clockLabel(hours: Double): String {
    val minute = Math.floorMod((hours * 60).roundToInt(), MINUTES_PER_DAY)
    val time = java.time.LocalTime.ofSecondOfDay(minute * 60L)
    return time.format(if (minute % 60 == 0) CLOCK_HOUR else CLOCK)
}

private const val MINUTES_PER_DAY = 24 * 60


/**
 * One night, minute by minute — or its totals when the timeline is missing.
 *
 * Every night recorded before the server began keeping stage timelines has
 * totals and nothing else, which is not an error and must not read as one. So
 * the card falls back to the same stacked bar the history uses, and says why.
 */
@Composable
private fun SleepNightCard(summary: SleepNightSummary, night: SleepNight?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                longDate(summary.date),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    hoursMinutes(summary.total),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // Only when there is waking to report. "7h 20m asleep · 0m
                // awake" is noise; "6h 30m asleep · 1h 10m awake" is the whole
                // story of the night.
                if (summary.restless > 0.02) {
                    Text(
                        "${hoursMinutes(summary.restless)} awake",
                        style = MaterialTheme.typography.labelMedium,
                        color = AWAKE,
                    )
                }
                summary.score?.let {
                    Text(
                        "score ${it.roundToInt()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = SCORE_LINE,
                    )
                }
            }
        }

        // Deliberately not in a [HealthCard]. On the page a card separated this
        // from its neighbours; in the sheet there are no neighbours, and a
        // surface-variant panel on a sheet background is a rectangle a shade
        // off the surface it sits on — which reads as a rendering fault rather
        // than as grouping, and reads as a different fault in each theme.
        Column {
            val spans = night?.stages.orEmpty()
            if (spans.isEmpty()) {
                // Every night imported before the server began keeping stage
                // timelines, and every night whose file has not arrived yet.
                // The totals above are real; only the shape is missing, and a
                // blank card would read as a broken one.
                Text(
                    "No stage timeline recorded for this night.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Hypnogram(spans)
            }
        }
    }
}

/**
 * The night as the watch saw it, stage by stage.
 *
 * Four lanes, deepest at the bottom, so the trace falls as you go under and
 * rises towards waking — the arrangement Garmin Connect uses and, more to the
 * point, the one that makes the *shape* legible: cycles read as a sawtooth,
 * a broken night as a row of gaps along the top lane.
 *
 * Time is the x axis and every span is drawn at its true width, so a
 * twenty-minute REM block is a fifth the width of a hundred-minute deep one.
 * Bucketing into equal steps would be easier and would flatten exactly the
 * thing the chart exists to show.
 */
@Composable
private fun Hypnogram(spans: List<com.tracks.core.api.SleepStage>) {
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    val parsed = remember(spans) { spans.mapNotNull(::toSpan).sortedBy { it.startEpoch } }
    if (parsed.isEmpty()) return

    val from = parsed.first().startEpoch
    val to = parsed.last().endEpoch
    val span = (to - from).coerceAtLeast(1)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(
                Modifier.padding(top = 2.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                LANES.forEach { lane ->
                    Text(
                        lane.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = axis,
                        modifier = Modifier.height(LANE_HEIGHT),
                    )
                }
            }

            Canvas(
                Modifier
                    .weight(1f)
                    .height(LANE_HEIGHT * LANES.size),
            ) {
                val laneHeight = size.height / LANES.size
                val radius = CornerRadius(2.dp.toPx(), 2.dp.toPx())

                // Faint lane bands, so a lane with no time in it is still a
                // lane rather than empty space.
                LANES.forEachIndexed { index, _ ->
                    drawRect(
                        color = axis.copy(alpha = 0.06f),
                        topLeft = Offset(0f, laneHeight * index),
                        size = Size(size.width, laneHeight - 1f),
                    )
                }

                parsed.forEach { entry ->
                    val laneIndex = LANES.indexOfFirst { it.matches(entry.level) }
                        .takeIf { it >= 0 } ?: LANES.lastIndex
                    val lane = LANES[laneIndex]
                    val x0 = size.width * ((entry.startEpoch - from).toFloat() / span)
                    val x1 = size.width * ((entry.endEpoch - from).toFloat() / span)
                    drawRoundRect(
                        color = lane.color,
                        topLeft = Offset(x0, laneHeight * laneIndex + laneHeight * 0.18f),
                        // A minute-long span would otherwise be sub-pixel and
                        // vanish — and a one-minute waking is worth seeing.
                        size = Size((x1 - x0).coerceAtLeast(1.5.dp.toPx()), laneHeight * 0.64f),
                        cornerRadius = radius,
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(start = LANE_LABEL_WIDTH),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(clockTime(from), style = MaterialTheme.typography.labelSmall, color = axis)
            Text(
                "${hoursMinutes((to - from) / 3600.0)} in bed",
                style = MaterialTheme.typography.labelSmall,
                color = axis,
            )
            Text(clockTime(to), style = MaterialTheme.typography.labelSmall, color = axis)
        }
    }
}

@Composable
private fun StageLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LegendSwatch("Deep", DEEP)
        LegendSwatch("REM", REM)
        LegendSwatch("Light", LIGHT)
        LegendSwatch("Awake", AWAKE)
        LegendSwatch("Score", SCORE_LINE)
    }
}

@Composable
private fun LegendSwatch(label: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(8.dp), shape = RoundedCornerShape(Tokens.Radius.sm), color = color) {}
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Lanes ────────────────────────────────────────────────────────────────────

private class Lane(val label: String, val color: Color, val keys: List<String>) {
    fun matches(level: String): Boolean = keys.any { it in level }
}

/**
 * Top to bottom: awake, REM, light, deep.
 *
 * Matched on substrings because the watch's vocabulary is not fixed — files
 * carry `light`, `sleep_light`, `unmeasurable` and others across firmware
 * versions, and an unrecognised level lands in the awake lane rather than being
 * dropped, so a gap in the middle of a night is never silently invented.
 */
private val LANES = listOf(
    Lane("Awake", AWAKE, listOf("awake", "unmeasur", "wake")),
    Lane("REM", REM, listOf("rem")),
    Lane("Light", LIGHT, listOf("light", "sleep")),
    Lane("Deep", DEEP, listOf("deep")),
)

private data class Span(val startEpoch: Long, val endEpoch: Long, val level: String)

/**
 * A span's two timestamps, as seconds.
 *
 * The server writes whatever the FIT file carried, which is offset-aware on
 * some firmware and naive on others. Both are accepted: an offset is honoured,
 * and a naive stamp is read in the phone's own zone — which is where the person
 * was sleeping.
 */
private fun toSpan(stage: com.tracks.core.api.SleepStage): Span? {
    val start = epochOf(stage.start) ?: return null
    val end = epochOf(stage.end) ?: return null
    if (end <= start) return null
    return Span(start, end, stage.level.lowercase())
}

private fun epochOf(text: String, zone: ZoneId = ZoneId.systemDefault()): Long? =
    runCatching { OffsetDateTime.parse(text).toEpochSecond() }
        .recoverCatching {
            LocalDateTime.parse(text).atZone(zone).toEpochSecond()
        }
        .getOrNull()

private fun clockTime(epochSeconds: Long): String =
    CLOCK.format(Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()))

private fun shortDate(iso: String): String =
    runCatching { LocalDate.parse(iso).format(SHORT_DATE) }.getOrDefault(iso)

private fun longDate(iso: String): String =
    runCatching { LocalDate.parse(iso).format(LONG_DATE) }.getOrDefault(iso)

/** Tap x-position only — the charts here are one-dimensional selectors. */
private suspend fun PointerInputScope.detectTaps(onTap: (Float) -> Unit) {
    detectTapGestures { offset -> onTap(offset.x) }
}

/**
 * `10:30 PM`, and `10 PM` on the hour.
 *
 * Twelve-hour because that is what the wearer's own watch face says, and a
 * sleep chart is read against a memory of what time it felt like rather than
 * against a 24-hour clock. Two formats because the axis is mostly whole hours
 * and `:00` on every label is four characters of nothing, repeated — and the
 * gutter those characters buy comes straight out of the plot.
 */
private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
private val CLOCK_HOUR: DateTimeFormatter = DateTimeFormatter.ofPattern("h a")
private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
private val LONG_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")

/**
 * Tall, and deliberately taller than any other chart in the app.
 *
 * This one carries two dimensions at once — when each night ran, and what it
 * was made of — and at the old 150dp a night's four stages shared about thirty
 * points of height between them, which is not enough to see that a night was
 * mostly light. The room comes from the explanatory copy that used to sit
 * above it, now behind the `?` in the sheet's title.
 */
private val CHART_HEIGHT = 260.dp

/** Between the plot's floor and the dates under it. */
private val DATE_LABEL_GAP = 3.dp

/** So a twenty-minute nap is a mark rather than nothing. */
private val MIN_BAR_HEIGHT = 2.dp

private val LANE_HEIGHT = 26.dp
private val LANE_LABEL_WIDTH = 44.dp
/** Visible on a year-long window; never wider than a week's slot looks sane. */
private val MIN_BAR_PX = 2.dp
private val MAX_BAR_PX = 24.dp

/** The selected-night underline's floor, independent of the bar's own width. */
private val MARK_WIDTH = 7.dp

