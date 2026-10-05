// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A column in which one child — marked with [fillRemaining] — takes whatever
 * height the others leave, down to a floor, and which can still scroll.
 *
 * Column's own `weight` cannot do this inside `verticalScroll`: there the
 * height is unbounded, so a weighted child is given the leftover of the
 * column's *minimum* and nothing when the content is taller than the screen,
 * and no modifier on the child can raise it back to a usable size. Here the
 * stretched child gets `max(floor, min height − everything else)`: it fills a
 * short form to the bottom of the sheet, and in a long one it keeps its floor
 * and the column simply scrolls.
 *
 * Give the column a minimum height (`heightIn(min = …)` after the scroll) or
 * there is nothing to fill.
 */
@Composable
fun StretchColumn(
    modifier: Modifier = Modifier,
    spacing: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val stretchIndex = measurables.indexOfFirst { it.parentData is FillRemaining }
        val placeables = arrayOfNulls<androidx.compose.ui.layout.Placeable>(measurables.size)
        measurables.forEachIndexed { i, m ->
            if (i != stretchIndex) placeables[i] = m.measure(loose.copy(maxHeight = Constraints.Infinity))
        }
        val gaps = gap * (measurables.size - 1).coerceAtLeast(0)
        if (stretchIndex >= 0) {
            val floor = (measurables[stretchIndex].parentData as FillRemaining).minHeight.roundToPx()
            val others = placeables.sumOf { it?.height ?: 0 }
            val h = maxOf(floor, constraints.minHeight - others - gaps)
            placeables[stretchIndex] = measurables[stretchIndex].measure(loose.copy(minHeight = h, maxHeight = h))
        }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeables.maxOf { it!!.width }
        val height = maxOf(constraints.minHeight, placeables.sumOf { it!!.height } + gaps)
        layout(width, height) {
            var y = 0
            placeables.forEach { p ->
                p!!.placeRelative(0, y)
                y += p.height + gap
            }
        }
    }
}

/** Marks the one child of a [StretchColumn] that takes the leftover height. */
fun Modifier.fillRemaining(minHeight: Dp): Modifier = this.then(FillRemaining(minHeight))

private data class FillRemaining(val minHeight: Dp) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = this@FillRemaining
}
