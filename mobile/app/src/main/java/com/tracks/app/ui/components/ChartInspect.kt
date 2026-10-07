// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
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
    pill: Color,
    onPill: Color,
) {
    drawLine(dot.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, plotHeight), strokeWidth = 1.dp.toPx())
    drawCircle(dot, radius = 4.5.dp.toPx(), center = Offset(x, y))
    val layout = measurer.measure(text, style.copy(color = onPill))
    val padX = 8.dp.toPx()
    val padY = 4.dp.toPx()
    val w = layout.size.width + padX * 2
    val h = layout.size.height + padY * 2
    val left = (x - w / 2).coerceIn(0f, (size.width - w).coerceAtLeast(0f))
    // Above the point if it fits, otherwise below it.
    val top = if (y - h - 8.dp.toPx() >= 0f) y - h - 8.dp.toPx() else (y + 8.dp.toPx()).coerceAtMost(size.height - h)
    drawRoundRect(pill, Offset(left, top), Size(w, h), CornerRadius(8.dp.toPx()))
    drawText(layout, topLeft = Offset(left + padX, top + padY))
}
