// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tracks.app.ui.components.InfoTip
import com.tracks.app.ui.components.MetricInfo
import com.tracks.app.ui.dashboard.GaugeZone
import com.tracks.app.ui.dashboard.HistoryPoint
import com.tracks.app.ui.dashboard.MetricHistorySheet
import com.tracks.app.ui.dashboard.RadialGauge
import com.tracks.app.ui.dashboard.zoneFor
import kotlin.math.roundToInt

/**
 * Every reading on this page as a dial you can open.
 *
 * ## Why dials and not eleven line charts
 *
 * A line answers "which way is this going" and is silent on the question people
 * actually arrive with, which is "is this number all right". Resting heart rate
 * 58 means nothing to someone who has not memorised the ranges; 58 sitting in a
 * green band called Good means something immediately. So the *reading* is a
 * dial against its scale, and the shape over time — the thing a line is
 * genuinely better at — is one tap behind it, in the same sheet the dashboard's
 * readiness and VO₂max dials open. One instrument, two screens.
 *
 * ## Four kinds of scale, because there are four kinds of number
 *
 * Some readings mean the same thing for everybody. SpO₂ of 91 is low whoever
 * you are, and those get published bands — see [MetricScale.Bands].
 *
 * Some are targets: steps, active calories, water. There is nothing wrong with
 * 6,000 steps, it is simply not 10,000 yet, so those get a ring that fills
 * towards a goal ([MetricScale.Goal]).
 *
 * And some have no honest universal scale at all. Weight is the clearest case —
 * colouring somebody's body mass red would be both medically worthless and
 * unkind — and HRV is the same story for a different reason: the useful
 * comparison is against *your* baseline, which is exactly what Garmin's own HRV
 * status does. Those get [MetricScale.Personal], where the dial shows where
 * today sits inside your own range for the window and says so in words. It is
 * still colour-coded; it just declines to pass judgement it has no basis for.
 *
 * And some are a total made of parts — a day's calories, a night's sleep stages
 * — where the arc itself can carry the breakdown ([MetricScale.Stacked]).
 */
sealed interface MetricScale {

    /** Published ranges: the reading means the same thing for everyone. */
    data class Bands(val zones: List<GaugeZone>) : MetricScale

    /** Something to reach. Below is not wrong, it is just not there yet. */
    data class Goal(val target: Double, val color: Color) : MetricScale

    /** No universal scale — placed against the person's own window. */
    data class Personal(val color: Color) : MetricScale

    /**
     * One reading that is really several, each its own colour on the arc.
     *
     * Calories and sleep are both shaped like this and for the same reason: the
     * total is the number people want, and it is made of parts they also want.
     * A day's burn is a resting cost plus what was earned; a night is deep plus
     * REM plus light. Two dials each would show the total twice and the
     * relationship never, and a single-colour arc shows the total and throws
     * the composition away.
     *
     * The arc runs to at least [target], so a short night still reads as short
     * rather than as a full ring — the parts fill what they fill and the rest
     * stays dim.
     */
    data class Stacked(
        val parts: List<Part>,
        val target: Double,
        /**
         * The stretch between the parts and [target]. Null takes the theme's
         * outline, which reads as "not there yet"; calories give it the active
         * colour instead, because that gap *is* the active goal.
         */
        val remainderColor: Color? = null,
        /** What that stretch is. "To goal" is right for sleep and vague for calories. */
        val remainderLabel: String = "To goal",
    ) : MetricScale {
        data class Part(val label: String, val amount: Double, val color: Color)
    }
}

/**
 * A stacked scale's bands, laid end to end from zero.
 *
 * Empty parts are dropped rather than drawn as zero-width bands: a night with
 * no REM should not put a REM label on the arc, and [zoneFor] would hand back
 * a band nothing can fall inside. If nothing is left, the whole arc is one
 * empty band — an arc with no extent draws nothing at all, and a dial that
 * vanishes when a reading is zero looks like a bug.
 */
internal fun stackedZones(scale: MetricScale.Stacked, fallback: Color): List<GaugeZone> {
    val parts = scale.parts.filter { it.amount > 0.0 }
    val target = scale.target.coerceAtLeast(0.0)
    if (parts.isEmpty()) return listOf(GaugeZone("None", 0.0, target.coerceAtLeast(1.0), fallback))

    var at = 0.0
    val bands = parts.map { part ->
        val from = at
        at += part.amount
        GaugeZone(part.label, from, at, part.color)
    }
    // Only when there is room left. Past the target the arc simply ends on the
    // last part, which is the honest picture of a goal beaten.
    return if (at < target) {
        bands + GaugeZone(scale.remainderLabel, at, target, scale.remainderColor ?: fallback)
    } else {
        bands
    }
}

/** One reading, its history, and how to place it on a scale. */
data class HealthMetric(
    val label: String,
    val trend: Trend,
    val unit: String,
    val scale: MetricScale,
    val info: MetricInfo? = null,
    val decimals: Int = 0,
    /** The name the history sheet uses, where there is room for the long form. */
    val longLabel: String = label,
    /**
     * A word under the figure, replacing the band's own name.
     *
     * For the calorie dial, where the bands are the two halves of the total and
     * "Active" alone would say less than "+220 active" does.
     */
    val caption: String? = null,
    /**
     * Extra figures for the history sheet.
     *
     * Body Battery is the case this exists for: the dial carries the level, and
     * the day's high, low, charge and drain are worth keeping but not worth a
     * card of their own on the page — which is what they had, and what made the
     * same number appear twice in two shapes.
     */
    val breakdown: List<Pair<String, Double>> = emptyList(),
    /** A chart of this metric's own for the history sheet — see [MetricHistorySheet]. */
    val chart: (@Composable () -> Unit)? = null,
    /**
     * Bands for the *word*, when the arc is a composition rather than a scale.
     *
     * A stacked dial spends its colours on the parts, which leaves it unable to
     * say whether the total is any good — and for sleep that verdict is the
     * whole point of glancing at it. So the arc shows the stages and these
     * bands supply the "Good" underneath. Calories have no verdict worth
     * printing and leave this null.
     */
    val verdict: List<GaugeZone>? = null,
    /**
     * How long this reading stays current, in days. Null never expires.
     *
     * ## Why a dial may not simply show the last thing it heard
     *
     * Because it was doing exactly that, and saying something false with it.
     * SpO₂ had not been recorded for the best part of a week — the setting off,
     * the watch off at night, it does not matter which — and the dial went on
     * showing the last figure it had, in colour, under a word like "Normal",
     * with a history chart whose line ran to the right-hand edge. Nothing was
     * broken and nothing was stale in any cache. The app was reporting history
     * as though it were current, which is the one thing a page of live readings
     * must never do.
     *
     * So a reading expires and the dial goes to an em dash — the same thing it
     * shows for a metric that has never recorded anything, because from the
     * point of view of "what is my blood oxygen right now" those two states are
     * the same state. The history is still there and the dial still opens it.
     *
     * A day for everything measured, and null for weight, which is a standing
     * fact about a body rather than an event — see [isFresh].
     */
    val freshDays: Long? = FRESH_DAYS,
    /**
     * Whether the sheet prints the big figure and the change line above the
     * chart.
     *
     * False for sleep, whose [chart] opens with the chosen night's own totals
     * and hypnogram — leaving the headline in would print the same duration
     * three times on one screen, counting the dial the sheet was opened from.
     */
    val headline: Boolean = true,
)

/**
 * A titled group of dials.
 *
 * A metric with no readings keeps its dial and shows an em dash. That is the
 * whole reason the grid is built from a fixed list rather than from whatever
 * happens to have data: a tile that disappears when the watch has not reported
 * makes a gap in the data indistinguishable from a bug in the app, and this
 * page had exactly that complaint against it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MetricMeterGroup(
    title: String,
    metrics: List<HealthMetric>,
    /**
     * The day the selected window opens on — the left edge of every history
     * chart these dials open, data or not. See [HistoryChart].
     */
    windowStart: java.time.LocalDate? = null,
    missingHint: String? = null,
    /**
     * Anything that belongs *with* these dials, inside their card.
     *
     * Body uses it for the button that logs a weight or a meal. Floating loose
     * under the card, that button read as belonging to the page rather than to
     * the three dials it fills in, which is the opposite of what it does.
     */
    footer: (@Composable () -> Unit)? = null,
) {
    var open by remember { mutableStateOf<HealthMetric?>(null) }
    val anyData = metrics.any { it.trend.values.isNotEmpty() }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        HealthCard {
            // Measured here rather than inside each tile: FlowRow asks its
            // children for an intrinsic width, and a BoxWithConstraints cannot
            // answer that — it is a SubcomposeLayout, and asking crashed the
            // page outright. One measurement above the row, a fixed size below
            // it, and the dials still fit whatever phone they land on.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // Three across unless the card is too narrow to hold three
                // legible dials, in which case two large ones beat three
                // squinting ones.
                val columns = if (maxWidth < THREE_UP_MIN) 2 else COLUMNS
                // SLACK, and it is not cosmetic: filling the column exactly
                // left the row a fraction of a pixel over budget once the dp
                // maths was rounded, and FlowRow wrapped the third dial onto a
                // line of its own.
                val gauge = ((maxWidth - TILE_GAP * (columns - 1)) / columns - SLACK)
                    .coerceAtMost(GAUGE_MAX)
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    maxItemsInEachRow = columns,
                ) {
                    metrics.forEach { metric ->
                        MeterTile(
                            metric = metric,
                            size = gauge,
                            modifier = Modifier.weight(1f),
                            onOpen = { open = metric },
                        )
                    }
                }
            }
            footer?.invoke()
            if (!anyData && missingHint != null) {
                Text(
                    missingHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    open?.let { metric ->
        MetricHistorySheet(
            title = metric.longLabel,
            points = metric.trend.dates.zip(metric.trend.values) { date, value ->
                HistoryPoint(date, value)
            },
            zones = metric.zonesFor(),
            verdict = metric.verdict,
            unit = metric.unit,
            decimals = metric.decimals,
            breakdown = metric.breakdown,
            info = metric.info,
            chart = metric.chart,
            windowStart = windowStart,
            freshDays = metric.freshDays,
            headline = metric.headline,
            onDismiss = { open = null },
        )
    }
}

/**
 * One dial, as large as its column allows.
 *
 * Sized from the tile rather than fixed, so the same grid gives a small phone
 * dials that fit and a large one dials worth looking at — capped, because past
 * a point a bigger ring is just a bigger ring and three of them stop reading as
 * a row.
 */
@Composable
private fun MeterTile(
    metric: HealthMetric,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
) {
    // The current reading, which past its freshness window is no reading at
    // all — see [HealthMetric.freshDays]. Everything below follows from it:
    // a null blanks the figure, drops the verdict word, and leaves the arc
    // showing its scale and nothing filled.
    val latest = metric.trend.current(metric.freshDays)
    val verdict = metric.verdict?.let { zoneFor(latest, it) }
    Column(modifier) {
        RadialGauge(
            value = latest,
            zones = metric.zonesFor(),
            label = metric.label,
            unit = metric.unit,
            decimals = metric.decimals,
            size = size,
            stroke = STROKE,
            caption = metric.caption ?: verdict?.label,
            // Without this the word would take the colour of whichever band the
            // total happens to land in, which on a stacked arc is the last
            // stage or the dim remainder — "Good" printed in grey.
            captionColor = verdict?.color,
            // Any reading *ever* opens the history, including a reading too
            // old to show on the dial: a blanked dial is precisely the case
            // where somebody wants to know when the watch last managed one,
            // and the history is where that is written. The bar used to be two
            // points, on the theory that one point is not a chart — but a
            // steps dial showing today's count and refusing to open reads as a
            // broken control, and "one reading so far" is a perfectly good
            // thing for the sheet to say.
            onClick = if (metric.trend.values.isNotEmpty()) onOpen else null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The bands this metric's dial is drawn against.
 *
 * [MetricScale.Personal] is the interesting case: the band is built from the
 * window's own readings, so the needle shows where today sits among the last
 * thirty days rather than against a threshold nobody agreed on. A window with
 * one reading, or a flat one, gets a nominal spread so the arc still draws —
 * an axis with no extent renders nothing at all.
 */
@Composable
private fun HealthMetric.zonesFor(): List<GaugeZone> = when (val s = scale) {
    is MetricScale.Bands -> s.zones

    is MetricScale.Stacked -> stackedZones(s, MaterialTheme.colorScheme.outline)

    is MetricScale.Goal -> listOf(
        GaugeZone("Building", 0.0, s.target * 0.7, MaterialTheme.colorScheme.outline),
        GaugeZone("Nearly", s.target * 0.7, s.target, AMBER),
        GaugeZone("Goal met", s.target, s.target * 1.5, s.color),
    )

    is MetricScale.Personal -> {
        val values = trend.values
        val low = values.minOrNull() ?: 0.0
        val high = values.maxOrNull() ?: 1.0
        val span = (high - low).takeIf { it > 0.001 } ?: (high.coerceAtLeast(1.0) * 0.1)
        if (values.size < 2) {
            // One reading is not a range, and "above average" said of a single
            // measurement compared with itself is nonsense dressed as insight.
            listOf(GaugeZone("Logged", low - span, high + span, s.color))
        } else {
            val average = values.average()
            listOf(
                // Short enough to sit inside an 82dp dial without clipping.
                GaugeZone("Below avg", low - span * 0.05, average, s.color.copy(alpha = 0.55f)),
                GaugeZone("Above avg", average, high + span * 0.05, s.color),
            )
        }
    }
}

// ── The scales ───────────────────────────────────────────────────────────────
//
// Published ranges for adults at rest, and nothing more clinical than that.
// These say whether a number is typical, which is the question somebody
// glancing at a watch app is asking; they are not a diagnosis and the tooltips
// behind each dial say so. The stress bands are Garmin's own, so the phone and
// the watch cannot disagree about what "medium" means.

private val TEAL = Color(0xFF14B8A6)
private val GREEN = Color(0xFF22C55E)
private val AMBER = Color(0xFFEAB308)
private val ORANGE = Color(0xFFF97316)
private val RED = Color(0xFFEF4444)
private val BLUE = Color(0xFF38BDF8)
private val VIOLET = Color(0xFFA855F7)
private val SKY = Color(0xFF0EA5E9)
private val SLATE = Color(0xFF94A3B8)

/** Lower is better, so the good colours sit at the bottom of the scale. */
val RESTING_HR_ZONES = listOf(
    GaugeZone("Excellent", 35.0, 50.0, TEAL),
    GaugeZone("Good", 50.0, 58.0, GREEN),
    GaugeZone("Average", 58.0, 66.0, AMBER),
    GaugeZone("Fair", 66.0, 75.0, ORANGE),
    GaugeZone("High", 75.0, 100.0, RED),
)

/** Pulse oximetry. Below 90 is the number worth noticing. */
val SPO2_ZONES = listOf(
    GaugeZone("Very low", 80.0, 90.0, RED),
    GaugeZone("Low", 90.0, 95.0, ORANGE),
    GaugeZone("Normal", 95.0, 100.0, GREEN),
)

/** Breaths per minute, adult at rest. */
val RESPIRATION_ZONES = listOf(
    GaugeZone("Low", 5.0, 12.0, AMBER),
    GaugeZone("Normal", 12.0, 20.0, GREEN),
    GaugeZone("Elevated", 20.0, 30.0, ORANGE),
)

/** Garmin's own bands, so the watch and the phone agree on the words. */
val STRESS_ZONES = listOf(
    GaugeZone("Rest", 0.0, 25.0, TEAL),
    GaugeZone("Low", 25.0, 50.0, GREEN),
    GaugeZone("Medium", 50.0, 75.0, AMBER),
    GaugeZone("High", 75.0, 100.0, ORANGE),
)

/**
 * Body Battery, on the watch's own 0-100 scale.
 *
 * The bands are the words Garmin uses for the same numbers, so the dial and the
 * watch face cannot disagree about whether 54 is "Good".
 */
val BODY_BATTERY_ZONES = listOf(
    GaugeZone("Low", 0.0, 25.0, RED),
    GaugeZone("Moderate", 25.0, 50.0, AMBER),
    GaugeZone("Good", 50.0, 75.0, TEAL),
    GaugeZone("Charged", 75.0, 100.0, GREEN),
)

/**
 * Hours slept, banded by duration.
 *
 * The adult recommendation is seven to nine, and the bands say that without
 * pretending to more precision than a wrist can measure. Under five is the one
 * worth a red arc; over nine is not a failure, which is why it is amber rather
 * than the same colour as three.
 */
val SLEEP_ZONES = listOf(
    GaugeZone("Short", 0.0, 5.0, RED),
    GaugeZone("Light", 5.0, 6.5, ORANGE),
    GaugeZone("Good", 6.5, 7.5, GREEN),
    GaugeZone("Ideal", 7.5, 9.0, TEAL),
    GaugeZone("Long", 9.0, 12.0, AMBER),
)

/**
 * What kind of day this has been, by what was burned above resting.
 *
 * The calorie dial spends its colours on the split between resting and active,
 * which leaves it unable to say whether the total is a lot — and "1,552 kcal"
 * means nothing without knowing that roughly 1,530 of it was the body idling.
 * These bands supply the word, anchored on [base] so they measure the earned
 * part while still being expressed on the scale the dial actually shows.
 *
 * Tied to [ACTIVE_CALORIE_GOAL] rather than to invented thresholds of their
 * own, so the arc's goal and the word underneath cannot disagree about what
 * counts as a good day.
 */
fun activityZones(base: Double): List<GaugeZone> = listOf(
    GaugeZone("Sedentary", base, base + 150.0, SLATE),
    GaugeZone("Light", base + 150.0, base + 350.0, AMBER),
    GaugeZone("Active", base + 350.0, base + ACTIVE_CALORIE_GOAL, ORANGE),
    GaugeZone("Goal met", base + ACTIVE_CALORIE_GOAL, base + ACTIVE_CALORIE_GOAL * 3, GREEN),
)

/** Everyday defaults, and the only invented numbers here. */
const val STEP_GOAL = 10_000.0

/**
 * Eight hours, and the same eight everywhere.
 *
 * The sleep dial's arc runs to it and the history chart draws its goal line at
 * it. Two constants would eventually disagree, and a dial reading "full" under
 * a chart whose goal line sits above the bar is the kind of contradiction
 * nobody reports because they assume they misread it.
 */
const val SLEEP_GOAL_HOURS = 8.0
const val ACTIVE_CALORIE_GOAL = 500.0
const val HYDRATION_GOAL_ML = 2_000.0

internal val HRV_COLOR = VIOLET
internal val WEIGHT_COLOR = BLUE
internal val HYDRATION_COLOR = SKY
internal val CALORIE_COLOR = ORANGE
internal val STEP_COLOR = GREEN
internal val ACTIVE_COLOR = ORANGE
/** Distinct from the active half and from the empty track, so the two
 *  halves of the day's burn read as two colours rather than one and a gap. */
internal val RESTING_COLOR = Color(0xFF3B82F6)
internal val SLEEP_COLOR = Color(0xFF6366F1)

/** Three across fits a phone with the long labels still on one line. */
private const val COLUMNS = 3

/**
 * The largest a dial's box will get, which is wider than the ring it draws.
 *
 * [RadialGauge] holds a lap's worth of room outside the main arc for the ring
 * that appears when a goal is beaten, so this counts that reserve on top of the
 * ring rather than taking it out of one — a dial that shrank to make room for a
 * level nobody has earned yet would be paying for the feature on every screen.
 *
 * A cap, not a size: the dial takes the width of its column, and this only
 * catches the case where the column is enormous — a tablet, or a foldable
 * opened out — where three dials the width of a hand would look absurd. On a
 * phone the column is what binds, so the grid fills the space it has.
 */
private val GAUGE_MAX = 124.dp

/** Thick enough to read at this diameter, and to hold a band's colour. */
private val STROKE = 9.dp

/**
 * Between tiles, and subtracted from the row before the dials are sized.
 *
 * Tight, because every dp here is a dp the dials do not get, and they are the
 * content — the gap only has to say that these are three things rather than
 * one.
 */
private val TILE_GAP = 4.dp

/** Rounding headroom, so a full row never wraps by a fraction of a pixel. */
private val SLACK = 2.dp

/** Below this the card cannot hold three dials worth reading. */
private val THREE_UP_MIN = 270.dp

internal fun Double.asInt(): Int = roundToInt()
