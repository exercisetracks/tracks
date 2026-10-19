// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.status

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tracks.app.ui.theme.Tokens

/**
 * The drawer's status area, under Settings: where background work lives once
 * the pill has popped up and gone ([StatusHost]).
 *
 * Running jobs first, one per source with its progress, then the last few
 * results with when they happened. A row is one line until tapped, then shows
 * all of its words -- the pill's expanded card, kept somewhere it can be
 * looked at without racing a timer.
 */
@Composable
fun StatusDrawerSection(
    jobs: Map<StatusSource, StatusJob>,
    notes: List<StatusNote>,
    onDismiss: (Long) -> Unit,
    onSyncNow: (() -> Unit)?,
    now: Long = System.currentTimeMillis(),
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = Tokens.Space.s2)) {
        Text(
            "Status",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = Tokens.Space.s1),
        )
        val running = jobs.entries.sortedBy { it.key.ordinal }
        val recent = notes.take(DRAWER_NOTES)
        if (running.isEmpty() && recent.isEmpty()) {
            Text(
                "Nothing running.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Tokens.Space.s1),
            )
        }
        running.forEach { (source, job) -> DrawerRow(Line.Job(source, job), whenText = null, onClose = null) }
        recent.forEach { note ->
            DrawerRow(Line.Note(note), whenText = ago(now, note.at), onClose = { onDismiss(note.id) })
        }
        if (onSyncNow != null) {
            TextButton(onClick = onSyncNow, modifier = Modifier.padding(top = Tokens.Space.s1)) { Text("Sync now") }
        }
    }
}

@Composable
private fun DrawerRow(line: Line, whenText: String?, onClose: (() -> Unit)?) {
    var open by rememberSaveable(
        when (line) {
            is Line.Job -> "job-${line.source}"
            is Line.Note -> "note-${line.note.id}"
        },
    ) { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s2),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open = !open }
            .padding(vertical = 2.dp)
            .animateContentSize(),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s3)) {
                LineContent(line, compact = !open)
            }
            if (whenText != null) {
                Text(
                    whenText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 30.dp),
                )
            }
        }
        if (onClose != null) {
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss", modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** "just now", "4 min ago", "2 h ago": how long since a result, read at a glance. */
internal fun ago(now: Long, at: Long): String {
    val s = ((now - at) / 1000).coerceAtLeast(0)
    return when {
        s < 45 -> "just now"
        s < 3600 -> "${(s + 30) / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        else -> "${s / 86_400} d ago"
    }
}

/**
 * The menu button, carrying the status the pill no longer shows: a ring while
 * anything runs, a red dot for a failure not yet seen in the drawer.
 */
@Composable
fun MenuIconWithStatus(running: Boolean, alert: Boolean) {
    Box(contentAlignment = Alignment.Center) {
        if (running) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp,
                modifier = Modifier.size(32.dp),
            )
        }
        Icon(Icons.Filled.Menu, contentDescription = "Open navigation")
        if (alert) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(9.dp).align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp),
            ) {}
        }
    }
}

/** How many results the drawer keeps in view. */
internal const val DRAWER_NOTES = 5
