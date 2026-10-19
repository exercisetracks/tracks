// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.components

import com.tracks.app.ui.theme.Tokens
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tracks.app.ui.theme.statLabel
import com.tracks.app.ui.theme.statValue

/** One figure with its label under it — the unit every summary row is built from. */
@Composable
fun StatValue(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    /**
     * Centred in its cell rather than left-aligned — for a grid of equal
     * columns (the dashboard overview), where left-aligned figures of
     * different widths read as ragged and sit off the column's middle.
     */
    centered: Boolean = false,
) {
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(1.dp),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        val align = if (centered) androidx.compose.ui.text.style.TextAlign.Center else null
        FittedStat(value, align)
        Text(
            label, style = statLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = align,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/**
 * A figure on one line, its unit small, shrunk until it fits its cell.
 *
 * Long values ("42.7 km/h", "1:02:33") used to wrap inside a third of the
 * screen, the unit landing alone on a second line. The unit now reads as a
 * unit — about half the figure's size — and the whole thing scales down in
 * steps until it fits, so it looks right on any phone and any field.
 * (Compose's own `TextAutoSize` arrives in foundation 1.8; this app is on 1.7.)
 */
@Composable
private fun FittedStat(value: String, align: androidx.compose.ui.text.style.TextAlign?) {
    val (figure, unit) = splitUnit(value)
    val text = androidx.compose.ui.text.buildAnnotatedString {
        append(figure)
        if (unit != null) {
            withStyle(androidx.compose.ui.text.SpanStyle(fontSize = statValue.fontSize * UNIT_SCALE)) {
                append(" ")
                append(unit)
            }
        }
    }
    var scale by androidx.compose.runtime.remember(value) { androidx.compose.runtime.mutableFloatStateOf(1f) }
    var fitted by androidx.compose.runtime.remember(value) { androidx.compose.runtime.mutableStateOf(false) }
    Text(
        text,
        style = statValue.copy(fontSize = statValue.fontSize * scale),
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = align,
        maxLines = 1,
        softWrap = false,
        onTextLayout = { layout ->
            if (layout.hasVisualOverflow && scale > MIN_SCALE) scale *= SHRINK_STEP else fitted = true
        },
        // Not drawn until it fits, so a long value never flashes at full size.
        modifier = Modifier.drawWithContent { if (fitted) drawContent() },
    )
}

/**
 * "42.7 km/h" → ("42.7", "km/h"); "5:23 /km" → ("5:23", "/km"). A value
 * that is not a figure and a short unit ("Strong", "1h 02m") stays whole.
 */
internal fun splitUnit(value: String): Pair<String, String?> {
    val m = UNIT_PATTERN.matchEntire(value.trim()) ?: return value to null
    return m.groupValues[1] to m.groupValues[2]
}

private val UNIT_PATTERN = Regex("""^([−+-]?\d[\d.,:]*)\s*([\p{L}%/°][^\s]{0,7})$""")
private const val UNIT_SCALE = 0.55f
private const val SHRINK_STEP = 0.92f
private const val MIN_SCALE = 0.5f

/**
 * What a screen shows instead of nothing.
 *
 * Always says what to *do*, not just what is absent — "no activities" is a
 * dead end, "sync to pull your activities down" is not. An offline-first app
 * hits these states routinely and by design, so they are ordinary screens
 * rather than error states.
 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier.padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * A small selectable control for the app bar.
 *
 * Deliberately not a `FilterChip`. A chip is sized to be the content of a
 * screen; four of them beside a page title wrap onto a second line, and at that
 * point they read as the point of the screen rather than as a control on it.
 * This is the same idea at bar scale — the sort on the activity list, the
 * window on the dashboard — and it is shared so the two cannot drift into
 * looking like different mechanisms.
 */
@Composable
fun BarPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(Tokens.Radius.lg),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}
