// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tracks.app.ui.theme.Tokens
import com.tracks.core.replica.SyncProgress
import kotlinx.coroutines.delay
import java.text.NumberFormat
import kotlin.math.roundToInt

/**
 * The small "Syncing 412 of 2,994 files" pill at the bottom of the screen.
 *
 * For the long syncs a person would otherwise wonder about — a first sync, a
 * phone that has not seen its server in weeks. A routine sync finishes in well
 * under a second, and a pill flashing up for that would be noise, so it only
 * appears once a sync has been running for [SHOW_AFTER_MS]; it leaves the
 * moment [progress] goes back to null.
 */
@Composable
fun SyncProgressPopup(progress: SyncProgress?, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    val running = progress != null
    LaunchedEffect(running) {
        if (running) {
            delay(SHOW_AFTER_MS)
            visible = true
        } else {
            visible = false
        }
    }
    // Held so the pill keeps its last words while it animates away.
    var last by remember { mutableStateOf(progress) }
    if (progress != null) last = progress

    AnimatedVisibility(
        visible = visible && running,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        val p = last ?: return@AnimatedVisibility
        Surface(
            shape = RoundedCornerShape(Tokens.Radius.full),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 6.dp,
            modifier = Modifier.padding(bottom = Tokens.Space.s4),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s3),
                modifier = Modifier.padding(horizontal = Tokens.Space.s4, vertical = Tokens.Space.s2_5),
            ) {
                val fraction = p.fraction
                val indicator = MaterialTheme.colorScheme.inversePrimary
                if (fraction != null) {
                    CircularProgressIndicator(
                        progress = { fraction }, color = indicator, strokeWidth = 2.5.dp,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    CircularProgressIndicator(color = indicator, strokeWidth = 2.5.dp, modifier = Modifier.size(18.dp))
                }
                Text(syncLabel(p), style = MaterialTheme.typography.bodyMedium)
                if (fraction != null) {
                    Text(
                        "${(fraction * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = indicator,
                    )
                }
            }
        }
    }
}

/** What the pill says for each step. Plain, and counted where there is a count. */
internal fun syncLabel(p: SyncProgress): String {
    val n = NumberFormat.getIntegerInstance()
    return when (p.step) {
        SyncProgress.Step.Sending -> "Sending changes"
        SyncProgress.Step.Receiving -> if (p.done > 0) "Receiving ${n.format(p.done)} changes" else "Receiving changes"
        SyncProgress.Step.Files -> "Syncing ${n.format(p.done)} of ${n.format(p.total ?: 0)} files"
        SyncProgress.Step.Uploading -> "Uploading ${n.format(p.done)} of ${n.format(p.total ?: 0)} files"
    }
}

private const val SHOW_AFTER_MS = 700L
