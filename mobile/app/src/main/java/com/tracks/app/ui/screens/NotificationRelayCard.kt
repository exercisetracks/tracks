// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tracks.app.ui.components.TracksSwitch
import com.tracks.app.feeds.InstalledApps
import com.tracks.app.feeds.NotificationRelayPreferences
import com.tracks.app.feeds.RelayApp
import com.tracks.app.feeds.TracksNotificationListener
import com.tracks.app.ui.components.TonalButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What of the phone's notifications the watch is allowed to see.
 *
 * The relay was all-or-nothing: grant notification access and everything that
 * is not chrome goes to the wrist, or grant nothing and none of it does. That
 * is a bad pair of options for the one feature in this app that can interrupt
 * you — a delivery tracker, a work chat at midnight and a two-factor code are
 * all "a notification", and only the person wearing the watch knows which of
 * them is worth a buzz.
 *
 * Two controls, because they cover different halves of the problem:
 *
 * - **Silent notifications** are the ones Android already knows about. Someone
 *   who has long-pressed a notification and chosen Silent has said, in the
 *   place Android provides for saying it, that this app should not interrupt
 *   them. Forwarding it to a watch overrides that decision, so the default is
 *   not to. This one switch tends to remove most of the noise on its own.
 * - **The blacklist** is for the rest: apps that shout at full importance and
 *   still have nothing to say to a wrist.
 *
 * Both are read on the notification itself rather than at connect time, so a
 * change here takes effect on the very next notification.
 *
 * And the quick replies: the answers the watch offers when replying to a
 * message from the wrist. They are the only thing here that goes *to* the
 * watch rather than filtering what does, and they are pushed to a connected
 * watch as soon as the editor closes.
 *
 * State is kept here rather than in a view model: these two settings are read
 * by a system-owned service that the app's object graph cannot reach, so they
 * live in [NotificationRelayPreferences], and this screen is their only writer.
 */
@Composable
fun NotificationRelayCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var skipSilent by remember { mutableStateOf(NotificationRelayPreferences.skipSilent(context)) }
    var blocked by remember { mutableStateOf(NotificationRelayPreferences.blocked(context)) }
    var picking by remember { mutableStateOf(false) }
    var replies by remember { mutableStateOf(NotificationRelayPreferences.quickReplies(context)) }
    var editingReplies by remember { mutableStateOf(false) }

    Column(modifier) {
        SettingsCard("Notifications on the watch") {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Skip silent notifications",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    com.tracks.app.ui.components.InfoTip(
                        com.tracks.app.ui.components.MetricInfo("Skip silent notifications", "Silenced notifications stay on the phone."),
                    )
                }
                TracksSwitch(
                    checked = skipSilent,
                    onCheckedChange = {
                        skipSilent = it
                        NotificationRelayPreferences.setSkipSilent(context, it)
                    },
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

            Text(
                "Blocked apps",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                blockedSummary(blocked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TonalButton("Choose apps", onClick = { picking = true })

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Quick replies",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                com.tracks.app.ui.components.InfoTip(
                    com.tracks.app.ui.components.MetricInfo(
                        "Quick replies",
                        "Offered on the watch when you reply to a message. Clear, mute and the app's own buttons are there too.",
                    ),
                )
            }
            Text(
                repliesSummary(replies),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TonalButton("Edit replies", onClick = { editingReplies = true })
        }
    }

    if (editingReplies) {
        QuickRepliesSheet(
            initial = replies,
            onDone = { edited ->
                NotificationRelayPreferences.setQuickReplies(context, edited)
                replies = NotificationRelayPreferences.quickReplies(context)
                editingReplies = false
            },
        )
    }

    if (picking) {
        BlockedAppsSheet(
            blocked = blocked,
            onToggle = { packageName, block ->
                blocked = if (block) blocked + packageName else blocked - packageName
                NotificationRelayPreferences.setBlocked(context, packageName, block)
            },
            onDismiss = { picking = false },
        )
    }
}

private fun repliesSummary(replies: List<String>): String = when {
    replies.isEmpty() -> "None — the watch will have nothing to reply with."
    replies.size <= 3 -> replies.joinToString(", ")
    else -> "${replies.take(3).joinToString(", ")} and ${replies.size - 3} more"
}

/**
 * The quick-reply editor.
 *
 * Saved once, when the sheet closes, rather than per keystroke: every save is
 * pushed to a connected watch, and a radio round trip per letter typed would
 * be a strange thing to spend a battery on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickRepliesSheet(initial: List<String>, onDone: (List<String>) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember { mutableStateOf(initial) }

    ModalBottomSheet(onDismissRequest = { onDone(draft) }, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxHeight(0.9f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Quick replies",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(draft) { index, reply ->
                    OutlinedTextField(
                        value = reply,
                        onValueChange = { text ->
                            val capped = text.take(NotificationRelayPreferences.MAX_QUICK_REPLY_LENGTH)
                            draft = draft.toMutableList().also { it[index] = capped }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = { draft = draft.toMutableList().also { it.removeAt(index) } }) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove")
                            }
                        },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (draft.size < NotificationRelayPreferences.MAX_QUICK_REPLIES) {
                    TonalButton("Add reply", onClick = { draft = draft + "" })
                }
                TextButton(onClick = { draft = NotificationRelayPreferences.DEFAULT_QUICK_REPLIES }) {
                    Text("Reset")
                }
                Box(Modifier.weight(1f))
                TonalButton("Done", onClick = { onDone(draft) })
            }
        }
    }
}

/**
 * The count, and the names when there are few enough to read.
 *
 * Labels rather than package names: someone who blocked "Gmail" a month ago
 * should not have to translate `com.google.android.gm` to recognise their own
 * decision.
 */
@Composable
private fun blockedSummary(blocked: Set<String>): String {
    val context = LocalContext.current
    val labels by produceState(emptyList<String>(), blocked) {
        value = withContext(Dispatchers.IO) {
            blocked.map { InstalledApps.label(context, it) }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
        }
    }
    return when {
        blocked.isEmpty() ->
            "Nothing blocked."
        labels.isEmpty() -> "${blocked.size} blocked."
        labels.size <= 4 -> "Blocked: ${labels.joinToString(", ")}."
        else -> "Blocked: ${labels.take(3).joinToString(", ")} and ${labels.size - 3} more."
    }
}

/**
 * The picker.
 *
 * Apps that were blocked when it opened sort to the top, and the order is
 * *frozen* there — sorting live on the toggle would make every row you touch
 * leap to the top of the list and take the next one you meant to touch with it.
 *
 * The list itself is the launcher's apps plus whatever has actually notified
 * this session; see [InstalledApps] for why it is not simply every installed
 * package.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlockedAppsSheet(
    blocked: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val initiallyBlocked = remember { blocked }
    var query by remember { mutableStateOf("") }

    val apps by produceState<List<RelayApp>?>(null) {
        val candidates = InstalledApps.relayCandidates(
            context,
            extra = TracksNotificationListener.seenPackages + initiallyBlocked,
        )
        value = candidates.sortedWith(
            compareByDescending<RelayApp> { it.packageName in initiallyBlocked }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label },
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxHeight(0.9f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Block from the watch",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                com.tracks.app.ui.components.InfoTip(
                    com.tracks.app.ui.components.MetricInfo("Block from the watch", "Blocked apps notify only on the phone."),
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search apps") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            val listed = apps
            if (listed == null) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val shown = remember(listed, query) {
                    if (query.isBlank()) {
                        listed
                    } else {
                        listed.filter {
                            it.label.contains(query, ignoreCase = true) ||
                                it.packageName.contains(query, ignoreCase = true)
                        }
                    }
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(shown, key = { it.packageName }) { app ->
                        AppRow(
                            app = app,
                            blocked = app.packageName in blocked,
                            onChange = { onToggle(app.packageName, it) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: RelayApp, blocked: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    // Per row rather than up front: an app drawer can hold two hundred icons,
    // and a phone should only decode the dozen currently on screen.
    val icon by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, app.packageName) {
        value = InstalledApps.icon(context, app.packageName)?.asImageBitmap()
    }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(32.dp)) }
        }
        Column(Modifier.weight(1f)) {
            Text(
                app.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                app.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TracksSwitch(checked = blocked, onCheckedChange = onChange)
    }
}
