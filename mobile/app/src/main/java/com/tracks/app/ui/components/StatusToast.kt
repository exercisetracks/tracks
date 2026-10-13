// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import kotlinx.coroutines.delay

/**
 * A passing word about something that happened out of sight — a watch took a
 * track, a sync found nothing new — in the same pill as the sync progress
 * ([ProgressPill]), so the app's background work has one voice.
 *
 * It used to be a line of small text inside whichever sheet was open, which
 * was easy to miss and pushed the list down. A message ending in "…" is still
 * happening and wears the spinning ring until the next message replaces it;
 * anything else is a result, shown briefly and then gone by itself.
 */
@Composable
fun StatusToast(message: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(message) }
    if (message != null) shown = message
    val busy = message?.endsWith("…") == true

    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        // A result reads in a few seconds; work in progress waits for its
        // outcome, with a ceiling so a lost one cannot hang there forever.
        delay(if (busy) BUSY_CEILING_MS else SHOW_MS)
        onDismiss()
    }

    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut() + slideOutVertically { -it / 2 },
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(Tokens.Radius.full),
            // The theme's surface, raised like the sheets, not the inverse one: inverse
            // turned the pill white in dark mode.
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 6.dp,
            modifier = Modifier.padding(top = Tokens.Space.s2).clickable(onClick = onDismiss),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s3),
                modifier = Modifier.padding(horizontal = Tokens.Space.s4, vertical = Tokens.Space.s2_5),
            ) {
                val accent = MaterialTheme.colorScheme.primary
                if (busy) {
                    CircularProgressIndicator(color = accent, strokeWidth = 2.5.dp, modifier = Modifier.size(18.dp))
                } else {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                }
                Text(shown.orEmpty(), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private const val SHOW_MS = 3_500L
private const val BUSY_CEILING_MS = 120_000L
