// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tracks.app.WatchUiState
import com.tracks.app.ui.components.ButtonRow
import com.tracks.app.ui.components.PrimaryButton
import com.tracks.app.ui.components.StatValue
import com.tracks.app.ui.components.TonalButton
import com.tracks.device.ConnectionState

/**
 * The watch: what is paired, what it is doing, and the two buttons that matter.
 *
 * ## Why this is a card in Settings and not a tab
 *
 * It had a bottom-bar slot for a while, on the reasoning that watch sync is why
 * this app exists. That confused *important* with *interacted with*. Sync runs
 * on its own about once an hour whenever the watch is in range; a user who
 * opens this screen weekly is a user whose watch is working, and one who never
 * opens it at all is the intended outcome. A permanent slot for a screen with
 * nothing to do on it costs a fifth of the bar forever, and the bar is the one
 * piece of the app with a hard size limit.
 *
 * So it lives where the other things-you-set-up-once live, and the parts that
 * genuinely need attention — a watch that has not synced, a pairing that
 * dropped — belong on the dashboard where they will actually be seen.
 *
 * Pull is deliberately available whether or not there is a session. Getting
 * activities off the watch is the one flow that must survive a locked vault
 * and no signal — the files land on the phone sealed, and upload when there is
 * something to upload to. Hiding this behind a sign-in would break exactly the
 * case the app exists for.
 */
@Composable
fun WatchCard(
    watch: WatchUiState,
    busy: Boolean,
    onPair: () -> Unit,
    onSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "WATCH",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            com.tracks.app.ui.components.InfoTip(
                com.tracks.app.ui.components.MetricInfo("Watch", "Syncs about hourly while in range — no signal needed."),
            )
        }
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ConnectionDot(watch.connection)
                    Column {
                        Text(
                            watch.pairedName ?: "No watch paired",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            connectionLabel(watch.connection),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (watch.batteryPercent != null || watch.pulled.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        watch.batteryPercent?.let { StatValue("$it%", "Battery") }
                        if (watch.pulled.isNotEmpty()) {
                            StatValue("${watch.pulled.size}", "Files this run")
                        }
                    }
                }

                // Indeterminate on purpose: the watch never says how many files
                // it intends to send, so a percentage here would be invented.
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                ButtonRow {
                    PrimaryButton("Sync now", onClick = onSync, enabled = !busy && watch.pairedName != null)
                    TonalButton(if (watch.pairedName == null) "Pair a watch" else "Re-pair", onClick = onPair, enabled = !busy)
                }
            }
        }
    }
}

@Composable
private fun ConnectionDot(state: ConnectionState) {
    val color = when (state) {
        is ConnectionState.Connected -> MaterialTheme.colorScheme.primary
        is ConnectionState.Connecting, is ConnectionState.Initializing ->
            MaterialTheme.colorScheme.secondary
        is ConnectionState.Failed -> MaterialTheme.colorScheme.error
        is ConnectionState.Disconnected -> MaterialTheme.colorScheme.outline
    }
    Surface(
        modifier = Modifier.size(10.dp),
        shape = CircleShape,
        color = color,
    ) { Box(Modifier.size(10.dp)) }
}

private fun connectionLabel(state: ConnectionState): String = when (state) {
    is ConnectionState.Disconnected -> "Disconnected"
    is ConnectionState.Connecting -> "Connecting…"
    is ConnectionState.Initializing -> "Handshaking…"
    is ConnectionState.Connected -> "Connected"
    is ConnectionState.Failed -> "Failed — ${state.reason}"
}
