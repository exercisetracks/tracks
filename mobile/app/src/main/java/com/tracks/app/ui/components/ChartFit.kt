// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.core.cartesian.Zoom
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Charts that fit the screen instead of scrolling off it.
 *
 * Vico's default is a scrollable chart at a fixed point spacing: when there are
 * more entries than fit, the plot keeps its density and runs off the right edge,
 * and the user pans. That is a reasonable default for a chart someone explores,
 * and the wrong one for every chart in this app.
 *
 * Two reasons. These are *summaries* — a year of form, a quarter of resting
 * heart rate — and the shape of the whole series is the entire message; a
 * viewport showing eight days of it says nothing and, worse, looks like the
 * whole answer. And they live inside a vertically scrolling page, so a
 * horizontally scrollable child is a gesture conflict on every drag that is not
 * perfectly vertical — the same class of bug the dashboard map had.
 *
 * [Zoom.Content] is the fix rather than a smaller point spacing: it scales the
 * x axis so the full series occupies exactly the available width, whatever the
 * period selector is set to. Scroll and zoom are both disabled, because with
 * the content already fitted there is nowhere to scroll to and pinching would
 * only undo the fit.
 *
 * Capped at 1×, and that half matters as much as the fit. `Zoom.Content` scales
 * in *both* directions, so a 7-day window — one weekly column — was magnified
 * to fill the width, drawing a single bar two thirds of the screen across and
 * its rounded cap as a dome. Taking the smaller of "fit the content" and "no
 * magnification" shrinks a long series to fit and leaves a short one at its
 * natural size, with empty space to the right. That is what the web app does
 * with sparse data, and an honest gap reads better than one enormous bar.
 */
@Composable
fun fittedScrollState(): VicoScrollState = rememberVicoScrollState(scrollEnabled = false)

@Composable
fun fittedZoomState(): VicoZoomState =
    rememberVicoZoomState(
        zoomEnabled = false,
        initialZoom = Zoom.min(Zoom.Content, Zoom.fixed(1f)),
    )

// ── Date axes ────────────────────────────────────────────────────────────────

/**
 * The dates behind the x axis, carried **by the model** rather than beside it.
 *
 * The x value is a position in the series, so something has to turn an index
 * back into a date. Holding that list in composition state is the obvious way
 * and it crashes the app.
 *
 * Filtering to one sport shortens the series. The new label list reaches the
 * formatter on the next composition, but the model is updated from a
 * `LaunchedEffect`, so for one frame Vico draws the *old*, longer series
 * through the *new*, shorter list. Every index past the new end missed, the
 * formatter returned `""` to say "no label there", and Vico throws on an empty
 * string by design — it wants an `ItemPlacer` to decide what gets labelled, not
 * a formatter opting out. The result was a draw-phase `IllegalStateException`
 * on the first tap of a donut segment: `CartesianValueFormatter.format returned
 * an empty string`.
 *
 * Putting the dates in the transaction fixes the cause rather than the symptom.
 * They land in the same atomic model update as the series they label, so the
 * two cannot be out of step — there is no window in which to be wrong.
 *
 * Shared rather than private to the dashboard, because the health trends draw
 * a value per day as well and had no axis at all — a year of resting heart
 * rate with nothing saying where the year starts.
 */
val AxisDates = ExtraStore.Key<List<String>>()

/** Attaches [dates] to the model being built, for [DateAxisFormatter] to read. */
fun CartesianChartModelProducer.Transaction.axisDates(dates: List<String>) {
    extras { store -> store.set(AxisDates, dates) }
}

/**
 * Turns an entry index back into the date it came from.
 *
 * Stateless, so it is one shared instance rather than one per composition: the
 * only thing it needs travels with the model it is formatting.
 *
 * The index is clamped rather than allowed to miss. Vico measures slightly
 * beyond the data when laying an axis out, and an empty return is what it
 * explicitly forbids — so an out-of-range ask yields the nearest real date,
 * which at the edges is the right answer anyway.
 */
val DateAxisFormatter = CartesianValueFormatter { context, value, _ ->
    val dates = context.model.extraStore.getOrNull(AxisDates).orEmpty()
    if (dates.isEmpty()) return@CartesianValueFormatter BLANK_LABEL
    val iso = dates[value.toInt().coerceIn(0, dates.lastIndex)]
    runCatching { LocalDate.parse(iso).format(AXIS_DATE_FORMAT) }.getOrDefault(BLANK_LABEL)
}

private val AXIS_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

/**
 * A space, not `""` — see [AxisDates]. Reached only before any data exists, when
 * there is no axis to label, so a blank is honest and an exception is not.
 */
private const val BLANK_LABEL = " "

/**
 * Roughly [TARGET_LABELS] labels, whatever the window — and none of them at an
 * end of the series.
 *
 * A fixed spacing cannot serve both a 7-day window and a lifetime: eight was
 * either two labels or a hundred and fifty. Deriving it from the point count
 * keeps the axis readable at every period.
 *
 * The count comes from the model's own extras for the same reason the labels
 * do — spacing read from composition state would be one frame ahead of the
 * series it is spacing.
 *
 * ## Why the labels start half a step in
 *
 * The offset is what keeps the *data* full-width, and it is not cosmetic.
 *
 * A label centred on the first entry would hang half outside the chart, so Vico
 * reserves half a label's width at each end of the plot when the extreme
 * entries are labelled. In a scrollable chart that reservation is free — it
 * comes out of the scrollable extent. In a fitted one (see [fittedZoomState])
 * it comes out of the plot: the series is scaled to whatever is left, so the
 * line starts a half-label inside its own frame and stops a half-label short of
 * it. On the dashboard's form chart, whose zone bands *do* span the full plot,
 * the result was a lifetime of training with visibly empty band at both ends —
 * a chart that reads as though its beginning and end had been cut off — and a
 * trace that no longer lined up with the training-load chart stacked above it,
 * which has no date axis and so no reservation.
 *
 * Starting the labels half a spacing in means no label sits on an extreme entry
 * in the first place, nothing needs reserving, and the series spans the whole
 * plot. What it costs is the first and last dates by name: the landmarks now
 * sit inside the window. For summaries read as shapes that is the right way
 * round, and the period selector already says which window is on screen.
 *
 * The offset alone very nearly does it, since Vico deducts the distance from
 * the first entry to the first label from what it reserves. Very nearly, and
 * not quite: whenever the label grid happens to land exactly on the last entry
 * — a 7-day window is one such case — the reservation comes back at that end
 * only, and the trace stops short of a frame it fills at the other. So the
 * reservation is switched off outright, and the offset is what makes that
 * safe: Vico drops any label it cannot fit rather than clipping it, and with
 * the grid already inside the series there is normally nothing at an extreme
 * to drop. Where there is — the 7-day window's last day, a series of two or
 * three points whose every entry is an extreme — those dates go unnamed, which
 * is the smaller loss.
 */
@Composable
fun thinnedLabels(): HorizontalAxis.ItemPlacer =
    remember {
        HorizontalAxis.ItemPlacer.aligned(
            spacing = { extras -> labelSpacing(extras) },
            offset = { extras -> labelSpacing(extras) / 2 },
            addExtremeLabelPadding = false,
        )
    }

/**
 * The gap between labelled entries, rounded *up* so the count never exceeds
 * [TARGET_LABELS].
 *
 * Rounding down put five labels on a 7-day window, spaced barely wider than a
 * date is long.
 */
private fun labelSpacing(extras: ExtraStore): Int {
    val count = extras.getOrNull(AxisDates)?.size ?: 0
    return ((count + TARGET_LABELS - 1) / TARGET_LABELS).coerceAtLeast(1)
}

private const val TARGET_LABELS = 4
