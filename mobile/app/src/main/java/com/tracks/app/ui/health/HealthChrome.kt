// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.health

import com.tracks.app.ui.theme.Tokens
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The chrome the Health screen is built from: its card, its figure, and the
 * sleep-stage colours every view of a night shares.
 *
 * ## What used to be here
 *
 * A "today" band: a stacked sleep card, and a grid of tiles repeating resting
 * heart rate, HRV, stress, steps, SpO₂, respiration and weight as figures. Both
 * are gone, and the reason is the same for each — they were a second copy.
 * Every tile in that grid had a trend card fifty pixels further down showing the
 * same reading with its history attached, and the sleep card duplicated the
 * night the hypnogram now draws properly. Two views of one number that disagree
 * about nothing are not twice as informative; they are twice as long to scroll
 * past.
 *
 * Body Battery stayed because it is the one reading with no useful trend card:
 * it is a level on a scale, and where it stands right now against where the day
 * took it is the whole reading. It has moved into the Body Battery section, so
 * the level and its history are one block rather than two ends of the page.
 */

@Composable
internal fun Figure(value: String, label: String, color: Color? = null) {
    Column {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = color ?: MaterialTheme.colorScheme.onSurface,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The card every block on this screen sits in — one shape, one padding. */
@Composable
internal fun HealthCard(content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Tokens.Radius.xl2),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

// Sleep-stage colours, matching the web app's SLEEP_COLORS exactly — the same
// night should not be indigo on one screen and blue on another. Shared with the
// sleep charts, because the colour is the stage's name there too.
internal val DEEP = Color(0xFF6366F1)
internal val REM = Color(0xFFA855F7)
internal val LIGHT = Color(0xFF38BDF8)
internal val AWAKE = Color(0xFF94A3B8)

// ── Formatting ───────────────────────────────────────────────────────────────

/** "7h 24m". Decimal hours are a unit nobody sleeps in. */
internal fun hoursMinutes(hours: Double): String {
    val total = (hours * 60).roundToInt()
    val h = total / 60
    val m = total % 60
    return if (h == 0) "${m}m" else "${h}h ${m}m"
}

// ── A real-time x axis ───────────────────────────────────────────────────────

/**
 * Where a dated reading sits on a chart whose x axis is time.
 *
 * ## Why this exists
 *
 * The sleep and Body Battery charts drew one bar per *reading*, evenly spaced.
 * That is a lie whenever a reading is missing, and readings here are missing
 * constantly — a watch left on the charger, a night off the wrist, a sync that
 * has not happened. Six nights spread across three weeks were drawn as six
 * consecutive bars, which says "I slept every night" in the one place someone
 * is looking to find out whether they did.
 *
 * So position comes from the date. A missing night is blank space of exactly
 * the right width, and the eye reads the gap for what it is.
 *
 * The right edge is *today* rather than the last reading, so a week with no
 * sync shows as trailing space instead of quietly rescaling itself away. Same
 * choice the web app makes, for the same reason.
 */
internal class DayAxis(
    private val from: java.time.LocalDate,
    private val to: java.time.LocalDate,
    /**
     * Whether the right edge is the *end* of [to] rather than its start.
     *
     * One reading per day sits on its date, so the right edge of those charts
     * is today itself. The stress curve is different: its readings are spread
     * through each day, and on an axis ending at the start of today every one
     * of this morning's readings had a position past the edge — all of them
     * clamped onto the last pixel, so today was drawn as a vertical line at
     * the right of the chart. Spanning to the end of the day gives today a
     * slot as wide as every other.
     */
    private val throughEnd: Boolean = false,
) {

    /** Days from end to end, never zero — a single reading still needs a width. */
    val days: Int = (java.time.temporal.ChronoUnit.DAYS.between(from, to).toInt() + if (throughEnd) 1 else 0)
        .coerceAtLeast(1)

    /**
     * How wide a bar standing for one day may be drawn.
     *
     * The whole reason this is a method on the axis rather than arithmetic at
     * the call site: a chart that positions bars by date and sizes them by
     * *count* draws overlapping blobs the moment the readings are uneven. Eight
     * nights across ten weeks sized by count came out at twenty-two points each
     * while consecutive nights sat four points apart, and every run of nights
     * merged into one shape. A bar is a day wide, minus a gap, and never more.
     */
    fun barWidth(canvasWidth: Float, minWidth: Float, maxWidth: Float): Float =
        ((canvasWidth / days) * BAR_FRACTION).coerceIn(minWidth, maxWidth)

    /** Where a bar's centre lands, inset so the end bars sit inside the canvas. */
    fun centre(fraction: Float, canvasWidth: Float, barWidth: Float): Float =
        barWidth / 2 + fraction * (canvasWidth - barWidth)

    /** The window's first day — the chart's left edge. */
    val start: java.time.LocalDate get() = from

    /** The day at a position, 0f at the left edge and 1f at the right. */
    fun dateAt(fraction: Float): java.time.LocalDate {
        val at = days * fraction.coerceIn(0f, 1f)
        // Through the end of its last day, a position belongs to the day it
        // falls inside, and the far edge is the end of [to] — not the next day.
        return if (throughEnd) {
            from.plusDays(kotlin.math.floor(at).toLong().coerceAtMost(days - 1L))
        } else {
            from.plusDays(Math.round(at).toLong())
        }
    }

    /** 0f at the left edge, 1f at the right. Null for a date outside the span. */
    fun fraction(date: java.time.LocalDate): Float? = fraction(date, 0f)

    /**
     * Where a moment *inside* a day lands, [within] being 0f at local midnight
     * and 1f at the next.
     *
     * The sub-day form exists for the stress curve, which is several hundred
     * readings a day rather than one a night. Placing those by date alone
     * would stack a whole day of readings on one vertical line — the axis
     * would be right and the chart would be a picket fence.
     */
    fun fraction(date: java.time.LocalDate, within: Float): Float? {
        val offset = java.time.temporal.ChronoUnit.DAYS.between(from, date).toInt()
        if (offset < 0 || offset > days || (throughEnd && offset == days)) return null
        return ((offset + within.coerceIn(0f, 1f)) / days).coerceIn(0f, 1f)
    }

    companion object {
        /** Most of the day's slot, leaving a gap so neighbours stay separate. */
        const val BAR_FRACTION = 0.8f

        /**
         * The axis for a set of dated readings.
         *
         * [from] is the selected window's own start, and passing it is what
         * makes "30 days" mean thirty days. Without it the axis ran from the
         * earliest *reading*, so three nights recorded this week filled a
         * thirty-day chart end to end and looked like a month of solid sleep —
         * the gap the window is there to reveal was scaled straight out of the
         * picture. Given the window, the same three nights sit against the
         * right-hand edge with the empty month behind them, which is the
         * honest shape.
         *
         * Falls back to the earliest reading when there is no window — the
         * lifetime view, where the data *is* the window. Ignores a [from] later
         * than the earliest reading rather than clipping it off the chart.
         *
         * The right edge is today, or the last reading when that is somehow
         * later — which a phone whose clock is behind the server's can produce.
         */
        fun of(
            dates: List<String>,
            from: java.time.LocalDate? = null,
            /** For readings spread through each day — see the constructor's [throughEnd]. */
            throughEnd: Boolean = false,
        ): DayAxis? {
            val parsed = dates.mapNotNull(::parseDay)
            val first = parsed.minOrNull() ?: return null
            val last = parsed.maxOrNull() ?: return null
            val today = java.time.LocalDate.now()
            val start = from?.takeIf { it.isBefore(first) } ?: first
            return DayAxis(start, maxOf(today, last), throughEnd)
        }

        fun parseDay(iso: String): java.time.LocalDate? =
            runCatching { java.time.LocalDate.parse(iso) }.getOrNull()
    }
}

/**
 * Whether a window is short enough to mark its days, and how firmly.
 *
 * ## Why every history chart draws them
 *
 * They started on the stress curve, where they are indispensable: that chart
 * is several hundred readings a day, and a peak means nothing until you can
 * see which day it is in — the same 3pm spike four days out of seven is a
 * habit, and it is invisible without a day to sit in.
 *
 * On the one-point-per-day charts they do a quieter version of the same job.
 * Those position by date rather than by index, so a run of readings and a run
 * with a gap in it look alike at a glance — the gap is a stretch of empty
 * chart, and empty chart is exactly what an unremarkable Tuesday also looks
 * like. Marking the days turns the gap into a countable number of them.
 *
 * Past a month there are too many to count and the lines stop being a scale;
 * before that they thin as they crowd, because thirty at a week's weight is a
 * grid the data has to fight through.
 */
internal object DayMarks {

    /** Past a month, days are too many to count and the marks stop helping. */
    private const val MAX_DAYS = 31

    /** Past this there are enough of them to carry the eye on less ink each. */
    private const val CROWDED = 14

    fun wanted(days: Int): Boolean = days <= MAX_DAYS

    fun alpha(days: Int): Float = if (days > CROWDED) 0.35f else 0.5f
}


// ── The date axis ────────────────────────────────────────────────────────────

/**
 * Dates along the bottom of a plot, as many as the width will hold.
 *
 * ## Why this is not two labels in a Row
 *
 * Which is what every chart here used to do: the window's first day at the far
 * left, today at the far right, and nothing in between. On a thirty-day window
 * that is a chart whose entire horizontal scale is "somewhere between these" —
 * a dip two thirds along could be the 15th or the 20th, and the only way to
 * find out was to count day marks with a finger.
 *
 * Three to five labels is the band where a phone stays readable: fewer and the
 * middle of the chart is unlabelled, more and "23 Aug" starts colliding with
 * its neighbour. The count comes from measuring an actual label against the
 * actual plot rather than from a guess, because the answer differs between a
 * small phone and a foldable, and between a gutter of "58" and one of "11,402".
 *
 * The ends are aligned inwards rather than centred — a label centred on the
 * first day hangs half outside the plot — and the interior ones are centred on
 * the day they name.
 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDateAxis(
    axis: DayAxis,
    measurer: androidx.compose.ui.text.TextMeasurer,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    plotLeft: Float,
    plotWidth: Float,
    top: Float,
) {
    if (plotWidth <= 0f) return
    val labelStyle = style.copy(color = color)
    fun text(fraction: Float) = axis.dateAt(fraction).format(DATE_AXIS_FORMAT)

    // Measured on real labels rather than assumed: "3 Sep" and "23 Aug" are
    // different widths, and the widest of the two ends is the binding one.
    val widest = maxOf(
        measurer.measure(text(0f), labelStyle).size.width,
        measurer.measure(text(1f), labelStyle).size.width,
    ).coerceAtLeast(1)
    val count = (plotWidth / (widest * LABEL_BREATHING_ROOM)).toInt()
        .coerceIn(MIN_DATE_LABELS, MAX_DATE_LABELS)

    for (index in 0 until count) {
        val fraction = index.toFloat() / (count - 1)
        val laid = measurer.measure(text(fraction), labelStyle)
        val x = when (index) {
            0 -> plotLeft
            count - 1 -> plotLeft + plotWidth - laid.size.width
            else -> plotLeft + plotWidth * fraction - laid.size.width / 2f
        }.coerceIn(0f, (size.width - laid.size.width).coerceAtLeast(0f))
        drawText(
            textLayoutResult = laid,
            topLeft = androidx.compose.ui.geometry.Offset(x, top),
        )
    }
}

private val DATE_AXIS_FORMAT: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("d MMM")

/** A label and a gap of its own width again, so neighbours never touch. */
private const val LABEL_BREATHING_ROOM = 2.0f

private const val MIN_DATE_LABELS = 2
private const val MAX_DATE_LABELS = 5

/** The strip a date axis needs under a plot, label and gap together. */
internal val DATE_AXIS_HEIGHT = 18.dp
