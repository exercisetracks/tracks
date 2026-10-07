// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.positionChange
import com.tracks.app.ui.theme.Tokens
import kotlin.math.abs
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp

/**
 * Press and hold a hand-drawn chart to read it — the Canvas charts' half of
 * [rememberChartMarker]. [onInspect] gets the finger's x while it is held
 * (following it as it slides) and null when it lifts; the chart draws
 * [drawInspection] at the nearest point.
 *
 * A long press rather than a touch, because these charts sit in scrolling
 * pages: a plain touch is the start of a scroll, and taking it would make the
 * page impossible to scroll past a chart.
 */
fun Modifier.holdToInspect(onInspect: (Float?) -> Unit): Modifier = pointerInput(Unit) {
    detectDragGesturesAfterLongPress(
        onDragStart = { onInspect(it.x) },
        onDrag = { change, _ -> change.consume(); onInspect(change.position.x) },
        onDragEnd = { onInspect(null) },
        onDragCancel = { onInspect(null) },
    )
}

/**
 * Keeps a chart's sideways drag away from the navigation drawer.
 *
 * The drawer listens for a horizontal drag anywhere on the page, and Vico's
 * marker follows the finger without consuming anything — so pressing a chart
 * to read it and sliding right to read the next day opened the menu instead.
 *
 * Not everything is claimed, because the charts sit in scrolling pages. A
 * drag that sets off vertically is left alone and scrolls the page past the
 * chart. A drag that sets off sideways, or any drag after the finger has been
 * held still for a long press, is the reader inspecting: every move from then
 * until the finger lifts is consumed, which the drawer and the page's scroll
 * both respect. Vico sits inside this modifier and has already seen each move
 * by the time it is consumed here, so the marker still follows.
 */
fun Modifier.claimInspectDrags(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        val heldAt = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
        var travel = Offset.Zero
        var claimed = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            if (!claimed) {
                // Someone below took it first — a scrollable inside the chart.
                if (change.isConsumed) break
                // Held still past the long-press timeout: a hold, whichever
                // way the finger then goes. Checked against the travel before
                // this move, since a held finger sends nothing until it moves.
                if (change.uptimeMillis >= heldAt && travel.getDistance() < slop) {
                    claimed = true
                } else {
                    travel += change.positionChange()
                    when {
                        abs(travel.x) > slop && abs(travel.x) > abs(travel.y) -> claimed = true
                        // A vertical drag: the page's, not ours.
                        abs(travel.y) > slop -> break
                    }
                }
            }
            if (claimed) event.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
        }
    }
}

/**
 * The inspection itself: a guideline at [x] down the plot, a dot at the
 * point, and [text] in a pill kept inside the canvas — above the plot's top
 * edge where there is room, so the finger does not cover it.
 */
fun DrawScope.drawInspection(
    measurer: TextMeasurer,
    style: TextStyle,
    x: Float,
    y: Float,
    plotHeight: Float,
    text: String,
    dot: Color,
    colors: ChartPopupColors,
) {
    // Styled as the Vico charts' marker is — see [rememberChartMarker] and
    // [chartPopupColors] — so a press reads the same on every chart.
    drawLine(colors.guide, Offset(x, 0f), Offset(x, plotHeight), strokeWidth = 1.dp.toPx())
    drawCircle(colors.background, radius = 6.dp.toPx(), center = Offset(x, y))
    drawCircle(dot, radius = 4.dp.toPx(), center = Offset(x, y))
    val layout = measurer.measure(text, style.copy(color = colors.text))
    val padX = 10.dp.toPx()
    val padY = 6.dp.toPx()
    val gap = POPUP_GAP.toPx()
    val w = layout.size.width + padX * 2
    val h = layout.size.height + padY * 2
    val left = (x - w / 2).coerceIn(0f, (size.width - w).coerceAtLeast(0f))
    // Above the point if it fits, otherwise below it — clear of the finger
    // either way, by the same gap the Vico marker keeps.
    val top = if (y - h - gap >= 0f) y - h - gap else (y + gap).coerceAtMost(size.height - h)
    val radius = CornerRadius(Tokens.Radius.xl.toPx())
    drawRoundRect(Color.Black.copy(alpha = 0.10f), Offset(left, top + 2.dp.toPx()), Size(w, h), radius)
    drawRoundRect(colors.background, Offset(left, top), Size(w, h), radius)
    drawRoundRect(colors.border, Offset(left, top), Size(w, h), radius, style = Stroke(1.dp.toPx()))
    drawText(layout, topLeft = Offset(left + padX, top + padY))
}
