// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.dashboard

import com.tracks.app.ui.theme.Tokens
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import com.tracks.core.api.ActivityCalendarPoint
import com.tracks.core.api.SportBreakdown
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The activity calendar, in whichever of its two shapes fits the window.
 *
 * [days] null means lifetime, and lifetime is a different chart rather than a
 * longer one — see [YearMatrix]. Everything else is the familiar week grid.
 *
 * This used to take a non-null `days` and the dashboard passed `period.days ?:
 * 365`, which quietly turned "Lifetime" into "This year": the two options drew
 * an identical chart and the difference looked like a bug in the data.
 */
@Composable
fun ActivityCalendar(
    points: List<ActivityCalendarPoint>,
    modifier: Modifier = Modifier,
    days: Int? = 365,
) {
    if (points.isEmpty()) {
        ChartPlaceholder("No activities to chart yet.", modifier)
        return
    }
    when {
        days == null -> YearMatrix(points, modifier)
        days <= STRIP_MAX_DAYS -> DayStrip(points, days, modifier)
        else -> WeekGrid(points, days, modifier)
    }
}

/**
 * A short window, as one row of days.
 *
 * ## Why a strip rather than a small grid
 *
 * The week grid is columns of weeks, seven days tall. That is the right shape
 * for a year — 52 columns across, seven down — and exactly the wrong one for a
 * week: one column, still seven cells tall, occupying a third of the screen's
 * height and an eighth of its width. Thirty days was barely better at six
 * columns. Both drew a tall sliver against a wide empty space.
 *
 * A window you can see all of does not need to be folded into weeks at all.
 * Laid out as a single row, seven days fill the width in one cell of height and
 * thirty fill it in one, which is both less space and more legible: the days
 * run left to right in the order they happened, the way a week is read.
 *
 * The cells stretch to fill whatever width there is and are capped in height
 * only. A 7-day row therefore draws wide blocks rather than magnified squares —
 * the same decision, and the same reason, as the chart zoom cap in
 * [com.tracks.app.ui.components.fittedZoomState].
 */
@Composable
private fun DayStrip(points: List<ActivityCalendarPoint>, days: Int, modifier: Modifier) {
    val base = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
    val strip = remember(points, days) { buildDayStrip(points, days) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gaps = STRIP_GAP * (strip.days.size - 1).coerceAtLeast(0)
            val cell = ((maxWidth - gaps) / strip.days.size).coerceAtMost(MAX_CELL)

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(STRIP_GAP),
            ) {
                strip.days.forEach { (_, count) ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(cell)
                            .clip(RoundedCornerShape(Tokens.Radius.base))
                            .background(
                                if (count == 0) empty else base.copy(alpha = intensity(count)),
                            ),
                    )
                }
            }
        }

        // A label under every cell for a week, and one a week for a month —
        // thirty dates across a phone is a grey smear, and the point of the
        // labels is only to anchor the row in time.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(STRIP_GAP),
        ) {
            strip.days.forEachIndexed { index, (date, _) ->
                // Counted back from today rather than forward from the start,
                // so the labelled cells are today and the same weekday before
                // it — a month that ends on an unlabelled cell reads as though
                // the row stops somewhere other than now.
                val show = strip.days.size <= DAYS_PER_WEEK ||
                    (strip.days.lastIndex - index) % DAYS_PER_WEEK == 0
                Text(
                    if (!show) "" else if (strip.days.size <= DAYS_PER_WEEK) {
                        WEEKDAY_INITIALS[date.dayOfWeek.value - 1]
                    } else {
                        date.format(STRIP_DATE)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    // Centred on its own cell but allowed to draw outside it.
                    // A month's cells are about 10dp wide, so a label confined
                    // to one was clipped to a single character — "18 Aug" came
                    // out as "1", which reads as data rather than as a date.
                    // Its neighbours are blank by construction, so there is
                    // nothing to overlap.
                    modifier = Modifier.weight(1f).wrapContentWidth(unbounded = true),
                )
            }
        }

        Text(
            "${strip.activeDays} active days · ${strip.total} activities",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One cell per day, weeks as columns.
 *
 * Two details carry the whole thing. Columns are *weeks*, so the grid has to
 * start on the week boundary containing the first day rather than on the day
 * itself — otherwise every row shifts and the weekday bands, which are the
 * pattern people actually read, dissolve. And intensity is bucketed rather than
 * scaled: a single 4-hour ride would otherwise wash out an entire year of
 * ordinary training to the palest shade.
 *
 * ## Sizing
 *
 * Cells stretch to fill the width, up to [MAX_CELL]. Both halves of that matter
 * and each was a bug on its own. Fixed 9dp cells made a year 583dp wide, so the
 * grid sat in a horizontal scroller and showed about four months of the one
 * chart whose entire point is the whole year. Removing the cap then made a
 * 7-day window — two columns — draw cells a third of the screen across, with
 * the card swallowing the display.
 *
 * A minimum of [MIN_WEEKS] columns comes from the same place: a single week is
 * one column, which is not a calendar. The web app shows four weeks minimum for
 * the same reason.
 */
@Composable
private fun WeekGrid(points: List<ActivityCalendarPoint>, days: Int, modifier: Modifier) {
    val base = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)

    val weeksToShow = remember(days) {
        max(MIN_WEEKS, ceil(days / 7.0).toInt() + 1)
    }
    val grid = remember(points, weeksToShow) { buildCalendarGrid(points, weeksToShow) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val weeks = grid.weeks.coerceAtLeast(1)
            // Fit the width, but never grow past a sensible cell size.
            val step = minOf(maxWidth / weeks, MAX_CELL)

            Canvas(
                Modifier
                    // Sized to the content rather than the parent: with the cap
                    // applied the grid can be narrower than the card, and
                    // filling the width would stretch the last column's gap.
                    .width(step * weeks)
                    .height(step * DAYS_PER_WEEK),
            ) {
                val stepPx = size.height / DAYS_PER_WEEK
                // The gap stays proportional so the grid reads as separate days
                // at every width rather than merging into bands when narrow.
                val cellPx = stepPx * CELL_FRACTION
                grid.cells.forEach { (position, count) ->
                    val (week, weekday) = position
                    drawRect(
                        color = if (count == 0) empty else base.copy(alpha = intensity(count)),
                        topLeft = Offset(week * stepPx, weekday * stepPx),
                        size = Size(cellPx, cellPx),
                    )
                }
            }
        }
        Text(
            "${grid.activeDays} active days · ${grid.total} activities",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Lifetime: one row per year, one cell per month.
 *
 * A day grid cannot show a lifetime — ten years is 520 columns, which on a
 * phone is under a pixel each. The web app switches to this matrix at exactly
 * the same point, and it answers the question lifetime is actually asked:
 * which years were heavy, and which months within them.
 *
 * Intensity is scaled against the busiest month rather than bucketed by count,
 * because monthly totals span a far wider range than daily ones and fixed
 * thresholds would saturate every row.
 */
@Composable
private fun YearMatrix(points: List<ActivityCalendarPoint>, modifier: Modifier) {
    val base = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)

    val matrix = remember(points) { buildYearMatrix(points) }
    if (matrix.years.isEmpty()) {
        ChartPlaceholder("No activities to chart yet.", modifier)
        return
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Spacer(Modifier.width(YEAR_LABEL_WIDTH))
            MONTH_INITIALS.forEach { initial ->
                Text(
                    initial,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        matrix.years.forEach { year ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${year.year}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(YEAR_LABEL_WIDTH),
                )
                year.months.forEach { count ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(MONTH_CELL_HEIGHT)
                            .clip(RoundedCornerShape(Tokens.Radius.base))
                            .background(
                                if (count == 0) {
                                    empty
                                } else {
                                    base.copy(
                                        alpha = monthIntensity(count, matrix.busiestMonth),
                                    )
                                },
                            ),
                    )
                }
            }
        }
        Text(
            "${matrix.years.size} years · ${matrix.total} activities",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The fraction of each step a cell fills; the rest is the gap.
 *
 * 9 of 11 — the proportion the old fixed 9dp cell and 2dp gap had, kept so the
 * grid looks the same as it did, just sized to the screen.
 */
private const val CELL_FRACTION = 9f / 11f
private const val DAYS_PER_WEEK = 7

/** Matches the web app's `MAX_CELL`, so a short window looks the same in both. */
private val MAX_CELL = 28.dp

/** Below this a "calendar" is a column of squares. The web app agrees. */
private const val MIN_WEEKS = 4

/**
 * Up to a month is drawn as a strip; past that it folds into weeks.
 *
 * 31 rather than 30 so a "30 days" window is not one day short of its own
 * boundary, and comfortably below the point where a day per column stops being
 * legible — thirty-one cells across a phone is about 10dp each.
 */
private const val STRIP_MAX_DAYS = 31
private val STRIP_GAP = 2.dp
private val WEEKDAY_INITIALS = listOf("M", "T", "W", "T", "F", "S", "S")
private val STRIP_DATE: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("d MMM")

private val YEAR_LABEL_WIDTH = 34.dp
private val MONTH_CELL_HEIGHT = 18.dp
private val MONTH_INITIALS = listOf("J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D")

/**
 * Four buckets, because a linear scale is dominated by one outlier.
 *
 * The thresholds are activity *counts* per day, not volume — the web app's
 * calendar counts the same way, and two easy runs is a busier day than one long
 * one in the only sense a day-cell can convey.
 */
private fun intensity(count: Int): Float = when {
    count >= 4 -> 1.0f
    count == 3 -> 0.8f
    count == 2 -> 0.6f
    else -> 0.4f
}

/** Monthly totals are scaled, not bucketed — see [YearMatrix]. */
private fun monthIntensity(count: Int, busiest: Int): Float {
    val ratio = count.toFloat() / busiest.coerceAtLeast(1)
    return (0.25f + ratio * 0.75f).coerceIn(0.25f, 1f)
}

private data class CalendarGrid(
    val cells: List<Pair<Pair<Int, Int>, Int>>,
    val weeks: Int,
    val activeDays: Int,
    val total: Int,
)

private fun buildCalendarGrid(points: List<ActivityCalendarPoint>, weeks: Int): CalendarGrid {
    val counts = points.associate { it.date to it.count }
    val end = LocalDate.now()
    // Back up whole weeks from the Monday of this week, so the last column is
    // the current week and every column is a real Monday-to-Sunday span.
    val thisMonday = end.minusDays((end.dayOfWeek.value - 1).toLong())
    val start = thisMonday.minusWeeks((weeks - 1).toLong())

    val cells = mutableListOf<Pair<Pair<Int, Int>, Int>>()
    var day = start
    var maxWeek = 0
    while (!day.isAfter(end)) {
        val week = ChronoUnit.DAYS.between(start, day).toInt() / 7
        val weekday = day.dayOfWeek.value - 1
        cells += (week to weekday) to (counts[day.toString()] ?: 0)
        maxWeek = week
        day = day.plusDays(1)
    }

    return CalendarGrid(
        cells = cells,
        weeks = maxWeek + 1,
        activeDays = cells.count { it.second > 0 },
        total = cells.sumOf { it.second },
    )
}

private data class DayStripData(
    val days: List<Pair<LocalDate, Int>>,
    val activeDays: Int,
    val total: Int,
)

/**
 * The last [days] days, ending today, every one of them present.
 *
 * Every day, including the empty ones: the gaps are half of what a training
 * calendar says. Built forward from the start so the row reads left to right in
 * time order, like the grid's columns do.
 */
private fun buildDayStrip(points: List<ActivityCalendarPoint>, days: Int): DayStripData {
    val counts = points.associate { it.date to it.count }
    val end = LocalDate.now()
    val start = end.minusDays((days - 1).toLong())

    val cells = mutableListOf<Pair<LocalDate, Int>>()
    var day = start
    while (!day.isAfter(end)) {
        cells += day to (counts[day.toString()] ?: 0)
        day = day.plusDays(1)
    }
    return DayStripData(
        days = cells,
        activeDays = cells.count { it.second > 0 },
        total = cells.sumOf { it.second },
    )
}

private data class YearRow(val year: Int, val months: List<Int>)

private data class YearMatrixData(
    val years: List<YearRow>,
    val busiestMonth: Int,
    val total: Int,
)

private fun buildYearMatrix(points: List<ActivityCalendarPoint>): YearMatrixData {
    val byMonth = mutableMapOf<String, Int>()
    points.forEach { point ->
        // "2026-08-15" → "2026-08". Substring rather than parsing: these are
        // ISO dates by contract, and a malformed one should drop out rather
        // than throw on a chart.
        val key = point.date.take(7)
        if (key.length == 7) byMonth[key] = (byMonth[key] ?: 0) + point.count
    }
    if (byMonth.isEmpty()) return YearMatrixData(emptyList(), 1, 0)

    val years = byMonth.keys.mapNotNull { it.take(4).toIntOrNull() }
    val first = years.min()
    val last = years.max()

    val rows = (first..last).map { year ->
        YearRow(
            year = year,
            months = (1..12).map { month ->
                byMonth["$year-${month.toString().padStart(2, '0')}"] ?: 0
            },
        )
    }

    return YearMatrixData(
        years = rows,
        busiestMonth = byMonth.values.max(),
        total = byMonth.values.sum(),
    )
}

// ── Sport breakdown ──────────────────────────────────────────────────────────

/**
 * The web app's palette, so a sport is the same colour in both clients.
 *
 * Mirrors `PALETTE` in `frontend/src/components/Charts/SportBreakdown.jsx`.
 * Assigned by rank, not by name — both clients order by activity count
 * descending and take the colour at that index, so "your biggest sport is
 * cyan" holds on the phone and in the browser without a shared mapping.
 */
private val SPORT_PALETTE = listOf(
    "#06b6d4", "#22c55e", "#f97316", "#f59e0b",
    "#ef4444", "#a855f7", "#3b82f6", "#ec4899",
    "#14b8a6", "#84cc16", "#8b5cf6", "#f43f5e",
).map(::specColor)

/**
 * Per-sport totals as a donut with a legend, matching the web dashboard.
 *
 * This was a list of bars first. Bars are arguably the better chart — they
 * compare lengths rather than angles — but the two clients showing the same
 * data in different forms is its own cost, and the pie is what the dashboard
 * this screen is a port of shows.
 *
 * A donut rather than a full pie: the hole carries the total, which a pie has
 * nowhere to put, and the segments stay legible at phone width.
 *
 * Tapping a segment or a legend row cross-filters the charts above, which is
 * what clicking the web pie does.
 */
@Composable
fun SportBreakdownList(
    sports: List<SportBreakdown>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sports.isEmpty()) {
        ChartPlaceholder("No sports in this window.", modifier)
        return
    }

    val shown = sports.take(MAX_SPORTS)
    val total = shown.sumOf { it.activityCount }.coerceAtLeast(1)
    val slices = shown.mapIndexed { index, sport ->
        DonutSlice(
            sport = sport.sport,
            fraction = sport.activityCount / total.toFloat(),
            color = SPORT_PALETTE[index % SPORT_PALETTE.size],
            selected = selected == sport.sport,
        )
    }

    // Side by side, not stacked. Stacked, this one card ran to most of a screen
    // — a 168dp donut above eight full-width rows — for what is a single glance
    // at proportions. The legend needs a name and a swatch; the counts were
    // duplicating what the donut already shows.
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val chosen = shown.firstOrNull { it.sport == selected }
        SportDonut(
            slices = slices,
            // The centre answers whatever the donut is currently showing. With
            // a sport picked, "412 activities" is the answer to a question
            // nobody asked — the ring, the charts above and every other figure
            // on the screen have all narrowed, and the one number in the middle
            // of it had not.
            count = chosen?.activityCount ?: total,
            // "1 run", not "1 Running" — the count's noun, singular for one.
            label = chosen?.let { activityNoun(it.sport, it.activityCount) } ?: activityNoun(null, total),
            dimUnselected = selected != null,
            onSelect = onSelect,
        )
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            slices.forEach { slice ->
                LegendRow(
                    color = slice.color,
                    label = sportLabel(slice.sport),
                    selected = slice.selected,
                    dimmed = selected != null && !slice.selected,
                    onClick = { onSelect(slice.sport) },
                )
            }
        }
    }
}

private const val MAX_SPORTS = 8

private data class DonutSlice(
    val sport: String,
    val fraction: Float,
    val color: Color,
    val selected: Boolean,
)

/**
 * The donut, with tappable segments.
 *
 * Hit-testing is done by angle rather than by attaching a clickable to each
 * arc, because arcs are drawn onto one Canvas and are not separate composables.
 * A tap is converted to a bearing from the centre and matched against the same
 * running angle the draw pass uses — so what the user hits is exactly what they
 * see, with no second layout to keep in step.
 *
 * Taps inside the hole and outside the ring are ignored: the hole holds the
 * total and belongs to no slice, and swallowing those would make the card feel
 * like it had mis-selected something.
 */
@Composable
private fun SportDonut(
    slices: List<DonutSlice>,
    count: Int,
    label: String,
    dimUnselected: Boolean,
    onSelect: (String) -> Unit,
) {
    val strokeDp = 22.dp
    val sizeDp = 132.dp

    Box(Modifier.size(sizeDp), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(slices) {
                    detectTapGestures { offset ->
                        val centre = Offset(size.width / 2f, size.height / 2f)
                        val dx = offset.x - centre.x
                        val dy = offset.y - centre.y
                        val radius = hypot(dx, dy)
                        val outer = size.width / 2f
                        val inner = outer - strokeDp.toPx()
                        if (radius < inner || radius > outer) return@detectTapGestures

                        // atan2 measures from +x counter-clockwise; the arcs are
                        // drawn from -90° clockwise, so shift into that frame.
                        val degrees = (Math.toDegrees(atan2(dy, dx).toDouble()) + 450.0) % 360.0
                        var sweepStart = 0.0
                        slices.forEach { slice ->
                            val sweep = slice.fraction * 360.0
                            if (degrees >= sweepStart && degrees < sweepStart + sweep) {
                                onSelect(slice.sport)
                                return@detectTapGestures
                            }
                            sweepStart += sweep
                        }
                    }
                },
        ) {
            val stroke = strokeDp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val gap = 1.5f
            var angle = -90f
            slices.forEach { slice ->
                val sweep = (slice.fraction * 360f - gap).coerceAtLeast(0.5f)
                drawArc(
                    color = if (dimUnselected && !slice.selected) {
                        slice.color.copy(alpha = 0.25f)
                    } else {
                        slice.color
                    },
                    startAngle = angle,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = if (slice.selected) stroke * 1.2f else stroke),
                )
                angle += slice.fraction * 360f
            }
        }
        Column(
            Modifier.padding(horizontal = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "$count",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LegendRow(
    color: Color,
    label: String,
    selected: Boolean,
    dimmed: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(if (dimmed) color.copy(alpha = 0.35f) else color),
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = when {
                selected -> MaterialTheme.colorScheme.primary
                dimmed -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

/**
 * A sport's wire name, made readable.
 *
 * Sports arrive as FIT enum names — `rock_climbing`, `fitness_equipment` — and
 * printing them raw put "Rock_climbing" on the home screen. Word-per-underscore
 * rather than a lookup table: the taxonomy in `spec/sport_taxonomy.yaml` already
 * covers ~120 sports and a display-name table for all of them would be another
 * thing to keep in step for no gain, since the underscore form is already
 * exactly the words in the right order.
 */
internal fun sportLabel(sport: String): String =
    sport.split('_')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
