// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.workout

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tracks.core.format.distance
import com.tracks.core.run.haversineMetres
import com.tracks.device.garmin.live.LiveActivity

/**
 * The watch is recording an activity: the same strip as [ActiveWorkoutBar],
 * for a recording the phone is not making. Tapping it opens the map, where
 * the track so far is drawn as it comes in.
 *
 * Shown from what the watch last said (LiveActivity): its position reports,
 * and its answer when asked after they go quiet (WatchManager), so a pause
 * reads as paused and a stop takes the strip away within half a minute.
 */
@Composable
fun WatchRecordingBar(track: LiveActivity.Track, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val paused = track.status == LiveActivity.Status.PAUSED
    val metres = trackMetres(track.points)
    val detail = listOfNotNull(
        if (paused) "Paused" else "Recording",
        distance(metres).takeIf { metres > 0 },
    )

    Surface(
        modifier.fillMaxWidth().clickable(onClick = onOpen),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RecordingDot(paused)
            Column(Modifier.weight(1f)) {
                Text(
                    "Recording on the watch",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    detail.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "Open the map")
        }
    }
}

/**
 * Along the track so far. The watch's own figure is not sent, so this is the
 * line as the phone has it -- short of the watch's while the phone missed part
 * of it, until an Explore sync fills the gap in.
 */
internal fun trackMetres(points: List<LiveActivity.Point>): Double =
    points.zipWithNext { a, b -> haversineMetres(a.lat, a.lng, b.lat, b.lng) }.sum()
