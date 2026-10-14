// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.status

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tracks.app.ui.theme.Tokens
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The app's one status pill, at the top of every screen. Everything from
 * [StatusBoard] is drawn here and nowhere else; see docs/mobile-status-ui.md.
 *
 * Collapsed, it shows one thing, in this order:
 *  1. the newest failure, which stays until it is dismissed or answered;
 *  2. a running job, with a ring that fills or spins;
 *  3. the newest success, for [SUCCESS_MS], then the pill goes.
 * Anything else that is running or unresolved is a "+N" on the right. Tapping
 * opens the card listing all of it, with "Sync now" at the bottom.
 */
@Composable
fun StatusHost(
    jobs: Map<StatusSource, StatusJob>,
    notes: List<StatusNote>,
    onDismiss: (Long) -> Unit,
    onExpirePosted: (StatusSource) -> Unit,
    onSyncNow: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onHeight: (Int) -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }

    // A job only earns the pill once it has run long enough to be worth
    // noticing: a sync that finds nothing in half a second should not flash.
    // A "…" posted by a tap is the answer to that tap and shows at once.
    var ready by remember { mutableStateOf(emptySet<StatusSource>()) }
    jobs.forEach { (source, job) ->
        key(source) {
            LaunchedEffect(source) {
                delay(SHOW_AFTER_MS)
                ready = ready + source
            }
            // A "…" message that never hears back is cleared, not left spinning.
            if (job.posted) LaunchedEffect(job.label) {
                delay(POSTED_CEILING_MS)
                onExpirePosted(source)
            }
        }
    }
    LaunchedEffect(jobs.keys) { ready = ready intersect jobs.keys }
    val shownJobs = jobs.filter { (source, job) -> source in ready || job.posted }

    // Successes age out of the collapsed pill on a clock; failures do not.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val newestSuccess = notes.firstOrNull { !it.failed }
    LaunchedEffect(newestSuccess?.id) {
        now = System.currentTimeMillis()
        if (newestSuccess != null) {
            delay(SUCCESS_MS)
            now = System.currentTimeMillis()
        }
    }
    val failures = notes.filter { it.failed }
    val freshSuccess = newestSuccess?.takeIf { now - it.at < SUCCESS_MS }
    val running = shownJobs.entries.sortedBy { it.key.ordinal }

    val headline: Line? = when {
        failures.isNotEmpty() -> Line.Note(failures.first())
        running.isNotEmpty() -> running.first().let { Line.Job(it.key, it.value) }
        freshSuccess != null -> Line.Note(freshSuccess)
        else -> null
    }
    val others = (failures.size - 1).coerceAtLeast(0) +
        (running.size - if (headline is Line.Job) 1 else 0)
    if (headline == null && failures.isEmpty()) expanded = false

    Column(modifier.onSizeChanged { onHeight(it.height) }.widthIn(max = 420.dp).padding(horizontal = Tokens.Space.s4), horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedVisibility(
            visible = headline != null || expanded,
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 },
        ) {
            Surface(
                shape = RoundedCornerShape(if (expanded) Tokens.Radius.xl else Tokens.Radius.full),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = Tokens.Space.s2).animateContentSize(),
            ) {
                if (!expanded) {
                    val line = headline ?: return@Surface
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s3),
                        modifier = Modifier.clickable { expanded = true }
                            .padding(horizontal = Tokens.Space.s4, vertical = Tokens.Space.s2_5),
                    ) {
                        LineContent(line, compact = true)
                        if (others > 0) {
                            Text(
                                "+$others",
                                maxLines = 1,
                                softWrap = false,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    Column(Modifier.fillMaxWidth().padding(vertical = Tokens.Space.s2)) {
                        running.forEach { (source, job) -> CardRow(Line.Job(source, job), onClose = null) }
                        notes.forEach { note -> CardRow(Line.Note(note), onClose = { onDismiss(note.id) }) }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.Space.s2),
                        ) {
                            if (onSyncNow != null) {
                                androidx.compose.material3.TextButton(onClick = { onSyncNow(); expanded = false }) {
                                    Text("Sync now")
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            androidx.compose.material3.TextButton(onClick = { expanded = false }) { Text("Close") }
                        }
                    }
                }
            }
        }
    }
}

private sealed interface Line {
    data class Job(val source: StatusSource, val job: StatusJob) : Line
    data class Note(val note: StatusNote) : Line
}

@Composable
private fun CardRow(line: Line, onClose: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s3),
        modifier = Modifier.fillMaxWidth().padding(start = Tokens.Space.s4, end = Tokens.Space.s1, top = 2.dp, bottom = 2.dp),
    ) {
        Box(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s3)) {
                LineContent(line, compact = false)
            }
        }
        if (onClose != null) {
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
            }
        } else {
            Spacer(Modifier.size(36.dp))
        }
    }
}

/** The glyph and words for one line; the same in the pill and the card. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.LineContent(line: Line, compact: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error
    when (line) {
        is Line.Job -> {
            val f = line.job.fraction
            if (f != null) {
                CircularProgressIndicator(progress = { f }, color = accent, strokeWidth = 2.5.dp, modifier = Modifier.size(18.dp))
            } else {
                CircularProgressIndicator(color = accent, strokeWidth = 2.5.dp, modifier = Modifier.size(18.dp))
            }
            Label(line.source.title, line.job.label.removeSuffix("…"), compact, Modifier.weight(1f, fill = false))
            if (f != null) {
                Text("${(f * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium, color = accent)
            }
        }
        is Line.Note -> {
            val n = line.note
            Icon(
                if (n.failed) Icons.Filled.Warning else Icons.Filled.CheckCircle,
                contentDescription = if (n.failed) "Failed" else null,
                tint = if (n.failed) error else accent,
                modifier = Modifier.size(18.dp),
            )
            Label(n.source.title.takeIf { n.source != StatusSource.App }, n.text, compact, Modifier.weight(1f, fill = false))
        }
    }
}

/** "Watch · Reading the watch": the source muted, so the words lead. */
@Composable
private fun Label(source: String?, text: String, compact: Boolean, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        if (source != null && !text.contains(source, ignoreCase = true)) {
            Text(
                "$source · ",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Unspecified,
            maxLines = if (compact) 1 else 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val SHOW_AFTER_MS = 700L
internal const val SUCCESS_MS = 3_500L
private const val POSTED_CEILING_MS = 120_000L
