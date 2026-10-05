// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tracks.app.ui.theme.Tokens

/*
 * ## The layout rule for groups of buttons and options
 *
 * A group of peers — the choices of one question (sport, days per week, a
 * weekday set), or the actions that close a form or card (Save / Cancel /
 * Delete, Regenerate / Sync) — fills the width it is given in equal cells:
 *
 * - as many columns as the widest member fits at its natural width,
 * - then rebalanced so the rows are as even as they can be (seven options at
 *   four a row become 4 + 3, never 6 + 1),
 * - options keep their columns aligned on a short last row; actions stretch
 *   the short row across the width instead, since a row of buttons with a
 *   hole in it reads as a missing button.
 *
 * A lone action at the foot of a card or an empty state spans the width too
 * (a one-cell [ButtonRow], or `Modifier.fillMaxWidth()`).
 *
 * Why: a FlowRow sets chips like words in a paragraph, so the right edge is
 * ragged and moves with every label and every display width; the same form
 * looked different on each phone and never looked finished on any. Equal
 * cells line up with the text fields above and below them.
 *
 * Not everything is a group of peers, and these stay as they are:
 * - headings and section titles, which stay left-aligned;
 * - a button trailing a heading or a list row ("New goal", "Activate", "Edit"),
 *   which belongs to that row, not to the width;
 * - chips whose set is the user's own data (muscles picked, saved meals,
 *   medications, trail names) — their number and length are content, and
 *   equal cells would crop them;
 * - colour and icon palettes, which are fixed-size swatches already on a grid;
 * - the confirm/dismiss slots of an AlertDialog, which Material lays out at
 *   the end as every dialog on the platform does.
 */

/**
 * Lays its children out in rows of equal-width cells across the full width —
 * the rule above. Children are measured at the cell width, so a child should
 * fill what it is given (a pill button or an [OptionCell] does).
 *
 * @param maxColumns a cap, for a set that should break earlier than it has to
 * (a 2 × 3 grid reads better than 5 + 1 at a width where six nearly fit).
 * @param minColumns a floor, for options whose labels may wrap to a second
 * line: one long label ("Bodybuilding / Hypertrophy") should not put every
 * other choice in a column of one.
 * @param stretchLastRow give a short last row's cells the whole width — for
 * actions. Options leave it false so their columns stay aligned.
 */
@Composable
fun EvenGrid(
    modifier: Modifier = Modifier,
    spacing: Dp = Tokens.Space.s2,
    maxColumns: Int = Int.MAX_VALUE,
    minColumns: Int = 1,
    stretchLastRow: Boolean = false,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier) { measurables, constraints ->
        val n = measurables.size
        if (n == 0) return@Layout layout(constraints.minWidth, 0) {}
        val gap = spacing.roundToPx()
        val widest = measurables.maxOf { it.maxIntrinsicWidth(Constraints.Infinity) }
        // Unbounded (inside a horizontal scroll): natural widths, one row.
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else n * widest + (n - 1) * gap

        var cols = ((width + gap) / (widest + gap).coerceAtLeast(1))
            .coerceAtLeast(minColumns)
            .coerceIn(1, minOf(n, maxColumns))
        val rows = (n + cols - 1) / cols
        cols = (n + rows - 1) / rows

        fun cellWidths(count: Int): List<Int> {
            val total = width - gap * (count - 1)
            val base = total / count
            // The odd pixels go to the first cells rather than being lost, so
            // the last cell's right edge lands exactly on the text fields'.
            return List(count) { if (it < total % count) base + 1 else base }
        }

        val placed = measurables.chunked(cols).mapIndexed { r, row ->
            val count = if (stretchLastRow && r == rows - 1) row.size else cols
            val widths = cellWidths(count)
            // Every cell in a row as tall as the tallest, so a label that wraps
            // to two lines does not leave its neighbours' borders short.
            val rowHeight = row.withIndex().maxOf { (i, m) -> m.minIntrinsicHeight(widths[i]) }
            row.mapIndexed { i, m ->
                m.measure(Constraints(minWidth = widths[i], maxWidth = widths[i], minHeight = rowHeight)) to widths
            }
        }
        val heights = placed.map { row -> row.maxOf { it.first.height } }
        val height = heights.sum() + gap * (heights.size - 1)
        layout(width, height) {
            var y = 0
            placed.forEachIndexed { r, row ->
                var x = 0
                row.forEachIndexed { i, (p, widths) ->
                    p.placeRelative(x, y + (heights[r] - p.height) / 2)
                    x += widths[i] + gap
                }
                y += heights[r] + gap
            }
        }
    }
}

/** Peer actions under the rule above: equal cells, a short last row stretched. */
@Composable
fun ButtonRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    EvenGrid(modifier, stretchLastRow = true, content = content)

/**
 * One choice of a set — the app's selectable cell for [OptionGrid], in place
 * of Material's FilterChip, whose label sits left in a fixed chip and so
 * cannot fill a cell. Selected is the accent tint the web's selected states
 * use (not Material's secondary container, which is lavender under every
 * accent); unselected is a hairline so the cells still read as a grid.
 */
@Composable
fun OptionCell(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** A toggle among several (a checkbox for accessibility) rather than one-of. */
    multi: Boolean = false,
) {
    val shape = RoundedCornerShape(Tokens.Radius.lg)
    val accent = MaterialTheme.colorScheme.primary
    val interaction = if (multi) {
        Modifier.toggleable(value = selected, enabled = enabled, role = Role.Checkbox) { onClick() }
    } else {
        Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
    }
    Box(
        modifier
            .heightIn(min = 36.dp)
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.16f) else Color.Transparent)
            .border(1.dp, if (selected) accent.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), shape)
            .then(interaction)
            .padding(horizontal = Tokens.Space.s2, vertical = Tokens.Space.s1_5),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                selected -> accent
                else -> MaterialTheme.colorScheme.onSurface
            },
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A set of choices under the rule above, as [OptionCell]s.
 *
 * @param options value to label, in display order.
 * @param multi each cell toggles on its own (a checkbox set) rather than
 * picking one of the set.
 */
@Composable
fun <T> OptionGrid(
    options: List<Pair<T, String>>,
    isSelected: (T) -> Boolean,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
    maxColumns: Int = Int.MAX_VALUE,
    multi: Boolean = false,
    enabled: Boolean = true,
) = EvenGrid(modifier, spacing = Tokens.Space.s1_5, maxColumns = maxColumns, minColumns = 2) {
    options.forEach { (value, label) ->
        OptionCell(label, isSelected(value), { onPick(value) }, enabled = enabled, multi = multi)
    }
}
