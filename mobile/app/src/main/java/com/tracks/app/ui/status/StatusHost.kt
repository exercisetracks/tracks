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
 * [StatusBoard] is drawn here and in the drawer's status area
 * ([StatusDrawerSection]), and nowhere else; see docs/mobile-status-ui.md.
 *
 * The pill is news, not a dashboard. It pops up for a few seconds when there
 * is something new -- a job starting, a result, a failure -- and then gets out
 * of the way; [POP_MS] for news, [FAILURE_POP_MS] for a failure. A sync that
 * runs for many minutes used to keep the pill on screen the whole time, which
 * is what this changes: the job carries on in the drawer, and the menu button
 * carries a ring while anything runs and a red dot for a failure not yet seen
 * there. Progress ticks of a running job do not pop it again.
 *
 * Tapping it opens the card listing everything, which stays open until closed.
 */
@Composable
fun StatusHost(
    board: StatusBoard,
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
    LaunchedEffect(jobs.keys) {
        ready = ready intersect jobs.keys
        // A source that stopped may pop again the next time it starts.
        board.poppedJobs.retainAll(jobs.keys)
    }
    val shownJobs = jobs.filter { (source, job) -> source in ready || job.posted }

    // What is popped up right now, and a counter so a newer pop is not cut
    // short by an older one's timer.
    var popped by remember { mutableStateOf<Pop?>(null) }
    var popSeq by remember { mutableLongStateOf(0L) }

    val newest = notes.firstOrNull()
    LaunchedEffect(newest?.id) {
        val note = newest ?: return@LaunchedEffect
        if (note.id <= board.lastPoppedNote) return@LaunchedEffect
        board.lastPoppedNote = note.id
        val mine = ++popSeq
        popped = Pop.Note(note.id)
        delay(popDurationMs(note))
        if (popSeq == mine) popped = null
    }
    val newJob = shownJobs.keys.sortedBy { it.ordinal }.firstOrNull { it !in board.poppedJobs }
    LaunchedEffect(newJob) {
        val source = newJob ?: return@LaunchedEffect
        board.poppedJobs += source
        // A job starting is routine; a failure on screen is not, and is not
        // cut short for it. The job is in the "+N" and the drawer.
        val showing = popped
        if (showing is Pop.Note && notes.firstOrNull { it.id == showing.id }?.failed == true) return@LaunchedEffect
        val mine = ++popSeq
        popped = Pop.Job(source)
        delay(POP_MS)
        if (popSeq == mine) popped = null
    }

    val failures = notes.filter { it.failed }
    val running = shownJobs.entries.sortedBy { it.key.ordinal }
    // A popped job shows its live progress; a popped note its words. Either
    // gone in the meantime (dismissed, finished) ends the pop.
    val headline: Line? = when (val p = popped) {
        is Pop.Note -> notes.firstOrNull { it.id == p.id }?.let { Line.Note(it) }
        is Pop.Job -> shownJobs[p.source]?.let { Line.Job(p.source, it) }
        null -> null
    }
    val others = failures.count { (headline as? Line.Note)?.note?.id != it.id } +
        running.count { (headline as? Line.Job)?.source != it.key }
    if (headline == null && notes.isEmpty() && running.isEmpty()) expanded = false

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

/** What the pill has popped up for: a note by id, or a source's job. */
private sealed interface Pop {
    data class Note(val id: Long) : Pop
    data class Job(val source: StatusSource) : Pop
}

/** How long a note stays popped up: a failure longer, so it can be read. */
internal fun popDurationMs(note: StatusNote): Long = if (note.failed) FAILURE_POP_MS else POP_MS

internal sealed interface Line {
    data class Job(val source: StatusSource, val job: StatusJob) : Line
    data class Note(val note: StatusNote) : Line
}

@Composable
internal fun CardRow(line: Line, onClose: (() -> Unit)?) {
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
internal fun androidx.compose.foundation.layout.RowScope.LineContent(line: Line, compact: Boolean) {
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

/** How long news pops up before it moves to the drawer. */
internal const val POP_MS = 4_000L

/** A failure stays a little longer: it usually asks something of the reader. */
internal const val FAILURE_POP_MS = 8_000L
private const val POSTED_CEILING_MS = 120_000L
