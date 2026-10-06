// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tracks.app.ui.components.TonalButton
import com.tracks.app.ui.theme.Tokens

/**
 * What the phone is actually feeding the watch, and what is stopping it.
 *
 * Every one of these three was implemented and silent. A feed that is wired
 * but ungranted looks exactly like a feed that is broken — nothing arrives,
 * nothing errors — so the fix is not only to ask for the grants but to make
 * the state visible. Each row says what it is for, whether it is on, and
 * offers the one action that turns it on.
 */
data class FeedStatus(
    val name: String,
    val description: String,
    val enabled: Boolean,
    /** Null when there is nothing the user can do from here. */
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
)

/** The feeds as rows inside Settings' Watch section, which supplies the card. */
@Composable
fun FeedRows(feeds: List<FeedStatus>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Tokens.Space.s3)) {
        feeds.forEach { FeedRow(it) }
    }
}

@Composable
private fun FeedRow(feed: FeedStatus) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                Modifier.size(8.dp),
                shape = CircleShape,
                color = if (feed.enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
            ) {}
            Text(
                feed.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            feed.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!feed.enabled && feed.actionLabel != null && feed.onAction != null) {
            TonalButton(feed.actionLabel, onClick = feed.onAction)
        }
    }
}
