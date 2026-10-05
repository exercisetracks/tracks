// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import com.tracks.app.ui.theme.Tokens
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState

/**
 * What a download is doing, on the map itself.
 *
 * ## Why this is not only in the downloads sheet
 *
 * Saving an area is the longest operation in this app — the server has to cut
 * the region out of planet-sized archives before the phone can pull a byte of
 * it, and that can run for many minutes. The progress lived inside the offline
 * maps sheet, which is exactly the screen somebody dismisses to go and look at
 * the map while they wait. Once dismissed there was no evidence anything was
 * happening at all, which is indistinguishable from the download having failed
 * silently — and this app has a history of exactly that complaint.
 *
 * So the state follows the user to the map. It appears when a region starts and
 * takes itself away when everything is finished.
 *
 * ## Why the two phases are named
 *
 * "Extracting" and "Storing" are genuinely different waits and fail in
 * different ways. The first is the server working and will survive the phone
 * going to sleep; the second is this device pulling bytes and will not. A
 * single anonymous bar for both would tell somebody nothing about whether they
 * can lock their screen — which is the actual question they have while looking
 * at it.
 */
@Composable
fun DownloadBanner(rows: List<RegionRow>, modifier: Modifier = Modifier) {
    // Finished and failed regions are the sheet's business, not the map's. This
    // is only ever about work in flight.
    // Work in flight only. A finished area, one the server holds and nobody
    // asked to store, and one paused waiting for a person to tap Resume are all
    // the sheet's business — this is about what is happening right now.
    val active = rows.filter {
        it.phase is DownloadPhase.Extracting || it.phase is DownloadPhase.Storing
    }

    AnimatedVisibility(
        visible = active.isNotEmpty(),
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = modifier,
    ) {
        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Tokens.Radius.xl),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
        ) {
            Column(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                active.forEach { row -> DownloadRow(row) }
                if (active.size > 1) {
                    Text(
                        "${active.size} areas downloading",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(row: RegionRow) {
    val phase = row.phase
    val fraction = when (phase) {
        is DownloadPhase.Extracting -> phase.fraction
        is DownloadPhase.Storing -> phase.fraction
        is DownloadPhase.Paused -> phase.fraction
        else -> 0f
    }
    // Animated, because the server reports extraction in coarse jumps and a bar
    // that leaps 40% at a time reads as broken rather than as fast.
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        label = "download",
    )

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                row.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "${(animated * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        LinearProgressIndicator(
            progress = { animated },
            modifier = Modifier.fillMaxWidth(),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )

        Text(
            phaseDetail(phase),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The line under the bar: which half of the job, and what it is doing.
 *
 * The server's own detail string is preferred where it sent one — it names the
 * archive being cut, which is the difference between "still going" and "stuck".
 */
internal fun phaseDetail(phase: DownloadPhase): String = when (phase) {
    is DownloadPhase.Extracting ->
        phase.detail?.takeIf { it.isNotBlank() }?.let { "Building on the server · $it" }
            ?: "Building on the server — you can leave this screen"
    is DownloadPhase.Storing -> when {
        phase.waiting != null -> "${phase.waiting} · ${megabytes(phase.bytes)}"
        phase.bytes > 0 -> "Saving to this phone · ${megabytes(phase.bytes)}"
        // It used to say "keep the app open", which was the honest instruction
        // at the time and is no longer true: the pull runs under a foreground
        // service now and survives the screen going off. See MapDownloadService.
        else -> "Saving to this phone…"
    }
    is DownloadPhase.Paused -> "Paused · ${megabytes(phase.bytes)}"
    is DownloadPhase.ServerOnly -> "On the server"
    is DownloadPhase.Ready -> "Ready"
    is DownloadPhase.Failed -> phase.reason
}

internal fun megabytes(bytes: Long): String {
    val mb = bytes / 1_048_576.0
    return if (mb >= 1024) "%.1f GB".format(mb / 1024) else "${mb.toInt()} MB"
}
