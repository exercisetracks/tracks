// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.components

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.patrykandpatrick.vico.compose.common.component.shadow
import com.tracks.app.ui.theme.Tokens
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisGuidelineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.compose.common.insets
import com.patrykandpatrick.vico.core.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.core.cartesian.marker.ColumnCartesianLayerMarkerTarget
import com.patrykandpatrick.vico.core.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.core.cartesian.marker.DefaultCartesianMarker.ValueFormatter
import com.patrykandpatrick.vico.core.common.component.Component
import com.patrykandpatrick.vico.core.common.component.LineComponent
import com.patrykandpatrick.vico.core.common.component.ShapeComponent
import com.patrykandpatrick.vico.core.common.component.TextComponent
import com.patrykandpatrick.vico.core.cartesian.marker.LineCartesianLayerMarkerTarget
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Press and hold on a chart to read the point under your finger — the phone's
 * answer to the web's hover tooltip, which every desktop chart has.
 *
 * One marker for every Vico chart in the app, so they all read alike: a pill
 * with the day (from [AxisDates], when the model carries dates) and each
 * series' value, a dot on each line, and a guideline down to the axis. Vico
 * shows it while the finger is down and moves it as the finger slides; the
 * fitted charts do not scroll ([fittedScrollState]), so the slide is free to
 * move the marker rather than pan.
 *
 * [names] labels the series in layer order ("Fitness", "Fatigue"); missing
 * names show the bare value. [format] turns a value into text — whole numbers
 * by default, since most of these are bpm, CTL points or minutes.
 */
@Composable
fun rememberChartMarker(
    names: List<String> = emptyList(),
    format: (Double) -> String = ::wholeOrOneDecimal,
): CartesianMarker {
    val colors = chartPopupColors()
    val label = rememberTextComponent(
        color = colors.text,
        textSize = 12.sp,
        lineCount = 4,
        padding = insets(horizontal = 10.dp, vertical = 6.dp),
        background = rememberShapeComponent(
            fill = fill(colors.background),
            shape = CorneredShape.rounded(Tokens.Radius.xl.value),
            strokeFill = fill(colors.border),
            strokeThickness = 1.dp,
            shadow = shadow(radius = 6.dp, y = 2.dp, color = Color.Black.copy(alpha = 0.16f)),
        ),
    )
    val formatter = remember(names, format, colors) { MarkerText(names, format, colors.muted.toArgb()) }
    val ring = colors.background
    // Solid and in the accent: Vico's default guideline is dashed grey, and
    // dashes in this app mean a reference line, not a cursor.
    val guideline = rememberAxisGuidelineComponent(
        fill = fill(colors.guide),
        thickness = 1.dp,
        shape = com.patrykandpatrick.vico.core.common.shape.Shape.Rectangle,
    )
    return remember(label, formatter, ring, guideline) {
        FingerClearMarker(
            popup = label,
            formatter = formatter,
            // The series' own colour, ringed in the card's, so the dot stands
            // off the line it sits on instead of merging into it.
            indicator = { color ->
                ShapeComponent(
                    fill = com.patrykandpatrick.vico.core.common.Fill(color),
                    shape = CorneredShape.Pill,
                    strokeFill = com.patrykandpatrick.vico.core.common.Fill(ring.toArgb()),
                    strokeThicknessDp = 2f,
                )
            },
            indicatorSizeDp = 10f,
            guideline = guideline,
            gapDp = POPUP_GAP.value,
        )
    }
}

/**
 * Vico's marker, with the popup placed where the finger is not.
 *
 * Vico's own placements all fail a finger one way or another. `Top` reserves
 * a strip above the plot for the label whether or not anyone is touching the
 * chart — about 75dp of nothing over every chart in the app, which on the
 * fitness card pushed the plot into the bottom half of its box. `AroundPoint`
 * reserves nothing, but sits the label right on the point and, near the top
 * of the chart, flips it *below* — under the fingertip that is holding it.
 *
 * So: above the point, a [gapDp] clear of it, whenever it fits in the chart;
 * otherwise beside the finger, on whichever side has room, level with the
 * point. Never below. Constructed as `AroundPoint` only because that is the
 * position that reserves no space; the label itself is drawn here.
 */
private class FingerClearMarker(
    private val popup: TextComponent,
    formatter: ValueFormatter,
    indicator: (Int) -> Component,
    indicatorSizeDp: Float,
    guideline: LineComponent,
    private val gapDp: Float,
) : DefaultCartesianMarker(popup, formatter, LabelPosition.AroundPoint, indicator, indicatorSizeDp, guideline) {

    override fun drawOverLayers(context: CartesianDrawingContext, targets: List<CartesianMarker.Target>) =
        with(context) { draw(targets) }

    private fun CartesianDrawingContext.draw(targets: List<CartesianMarker.Target>) {
        drawGuideline(targets)
        val half = (indicatorSizeDp / 2).pixels
        var highest = Float.MAX_VALUE
        targets.forEach { t ->
            val points = when (t) {
                is LineCartesianLayerMarkerTarget -> t.points.map { it.canvasY to it.color }
                is ColumnCartesianLayerMarkerTarget -> t.columns.map { it.canvasY to it.color }
                else -> emptyList()
            }
            points.forEach { (y, color) ->
                drawIndicator(t.canvasX, y, color, half)
                highest = minOf(highest, y)
            }
        }
        if (highest == Float.MAX_VALUE) return

        val text = valueFormatter.format(this, targets)
        val canvas = canvasBounds
        val w = popup.getWidth(this, text)
        val h = popup.getHeight(this, text)
        val gap = gapDp.pixels + half
        val x = targets.map { it.canvasX }.average().toFloat()

        val left: Float
        val top: Float
        if (highest - gap - h >= canvas.top) {
            left = (x - w / 2).coerceIn(canvas.left, (canvas.right - w).coerceAtLeast(canvas.left))
            top = highest - gap - h
        } else {
            val right = x + gap + w <= canvas.right
            left = if (right) x + gap else (x - gap - w).coerceAtLeast(canvas.left)
            top = (highest - h / 2).coerceIn(canvas.top, (canvas.bottom - h).coerceAtLeast(canvas.top))
        }
        // Drawn by its centre, which is the one anchor whose meaning does not
        // depend on which way Vico reads Start and End.
        popup.draw(
            this, text, left + w / 2, top + h / 2,
            com.patrykandpatrick.vico.core.common.Position.Horizontal.Center,
            com.patrykandpatrick.vico.core.common.Position.Vertical.Center,
        )
    }
}

/**
 * The press-and-hold popup's colours — shared by the Vico charts' marker and
 * the hand-drawn charts' [drawInspection], so the two read as one control.
 *
 * Built only from roles the app's theme sets. The popup used to be
 * `inverseSurface`, which the theme leaves to Material's stock scheme: a
 * purple-grey that matched neither the light nor the dark theme and ignored
 * the accent entirely. A card-coloured pill with an accent edge is how the
 * rest of the app draws a raised thing.
 */
data class ChartPopupColors(
    val background: Color,
    val border: Color,
    val text: Color,
    val muted: Color,
    val guide: Color,
)

@Composable
fun chartPopupColors(): ChartPopupColors {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme.surface, scheme.primary, scheme.onSurface, scheme.onSurfaceVariant) {
        ChartPopupColors(
            background = scheme.surface,
            border = scheme.primary.copy(alpha = 0.45f),
            text = scheme.onSurface,
            muted = scheme.onSurfaceVariant,
            guide = scheme.primary.copy(alpha = 0.6f),
        )
    }
}

/** How far the popup sits from the point it reads. See [rememberChartMarker]. */
val POPUP_GAP = 14.dp

/**
 * The popup's text: the day, muted, then one line per series — a dot in the
 * series' own colour, its name, and the value in bold.
 *
 * One line per series rather than all on one with dots between: two values
 * side by side read as a sentence, and the colour dot is what says which line
 * on the chart each number belongs to.
 */
private class MarkerText(
    private val names: List<String>,
    private val format: (Double) -> String,
    private val muted: Int,
) : DefaultCartesianMarker.ValueFormatter {
    override fun format(context: CartesianDrawingContext, targets: List<CartesianMarker.Target>): CharSequence {
        val values = targets.flatMap { t ->
            when (t) {
                is LineCartesianLayerMarkerTarget -> t.points.map { it.entry.y to it.color }
                is ColumnCartesianLayerMarkerTarget -> t.columns.map { it.entry.y to it.color }
                else -> emptyList()
            }
        }
        val day = targets.firstOrNull()?.x?.let { x ->
            val dates = context.model.extraStore.getOrNull(AxisDates).orEmpty()
            dates.getOrNull(x.toInt())?.let { iso ->
                runCatching { LocalDate.parse(iso).format(MARKER_DATE) }.getOrNull()
            }
        }
        val out = SpannableStringBuilder()
        day?.let { out.append(it, ForegroundColorSpan(muted), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        values.forEachIndexed { i, (v, color) ->
            if (out.isNotEmpty()) out.append("\n")
            out.append("\u25CF ", ForegroundColorSpan(color), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            names.getOrNull(i)?.let { out.append("$it  ") }
            out.append(format(v), StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }
}

private val MARKER_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy")

/** 54 for 54.0, 3.2 for 3.24 — the values these charts carry. */
fun wholeOrOneDecimal(v: Double): String =
    if (abs(v - v.roundToLong()) < 0.05) v.roundToLong().toString() else "%.1f".format(v)
