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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
        Text(value, style = statValue, color = MaterialTheme.colorScheme.onSurface, textAlign = align)
        Text(label, style = statLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = align)
    }
}

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
 * "Two entries waiting to sync."
 *
 * Shown when the phone is holding writes the server has not taken yet — a
 * session finished in a basement, a dose ticked off on a plane. Not an error
 * and deliberately not styled as one: nothing has gone wrong, the app is doing
 * exactly what it promises, and the only thing the user needs to know is that
 * it has not happened *yet*.
 *
 * It says nothing about how to fix it, because there is nothing to do. The
 * entries go out on their own the next time the server answers.
 */
@Composable
fun PendingBanner(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Tokens.Radius.lg),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (count == 1) "1 entry waiting to sync" else "$count entries waiting to sync",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
