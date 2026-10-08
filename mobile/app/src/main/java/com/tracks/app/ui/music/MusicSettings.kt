// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.tracks.app.ui.tour.TourAnchor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracks.app.MainViewModel
import com.tracks.app.MusicUiState
import com.tracks.app.ui.components.DangerButton
import com.tracks.app.ui.components.MetricInfo
import com.tracks.app.ui.components.NeutralButton
import com.tracks.app.ui.components.PasswordField
import com.tracks.app.ui.components.PrimaryButton
import com.tracks.app.ui.components.TonalButton
import com.tracks.app.ui.screens.SettingsCard
import com.tracks.app.ui.theme.Tokens
import com.tracks.core.api.MusicTrackSummary
import com.tracks.device.garmin.GarminIntegration
import kotlin.math.roundToInt

/**
 * Music, from the phone.
 *
 * The phone's job here is different from the browser's. A browser can push
 * files down a cable into the watch's own library; a phone cannot — Bluetooth
 * has no file type for audio, on any Garmin. So this screen is about the path
 * the phone *can* drive end to end: the watch app, which downloads from the
 * user's music server over Wi-Fi and needs the phone only to be told where
 * that server is.
 *
 * The cable library is here too, below, because its "what to carry" choices
 * live on the Tracks server and this is a fine place to make them — but it is
 * labelled as the other library, and kept apart.
 *
 * Uploading loose files stays in the web UI, where there is a filesystem and a
 * keyboard. What this screen does not do, it says so rather than hiding.
 */
@Composable
fun MusicScreen(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val music = state.music

    LaunchedEffect(Unit) { vm.loadMusicSetup() }

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { TourAnchor("music-server") { ServerSection(music, vm) } }
        item { TourAnchor("music-watch") { WatchAppSection(music, vm) } }
        if (music.server?.configured == true && music.watchPlaylists.isNotEmpty()) {
            item { WatchPlaylistsSection(music, vm) }
        }
        if (music.server?.configured == true && music.smart.isNotEmpty()) {
            item { SmartSection(music, vm) }
        }
        item { TourAnchor("music-library") { LibraryHeading(music) } }

        if (music.tracks.isEmpty()) {
            item {
                Text(
                    if (music.server?.configured == true) {
                        "Nothing carried yet. Pick a playlist above, or upload files in the web app."
                    } else {
                        "No music yet. Connect a music server above, or upload files in the web app."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(music.tracks, key = { it.id }) { track ->
                TrackRow(track) { vm.setTrackCarried(track.id, it) }
            }
        }

        if (music.message != null) {
            item {
                Text(
                    music.message!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * A Garmin holds music two ways and they do not mix — which used to be a card
 * of paragraphs at the top of this page. It is the "?" beside each library's
 * heading now: said where it applies, to whoever asks.
 */
private val TWO_LIBRARIES = MetricInfo(
    "Two libraries",
    "The Tracks Music watch app downloads playlists straight from your music server over the " +
        "watch's own Wi-Fi, on the charger, and plays them in its own player.",
    "Music sent over USB from the web app plays under the watch's own \"My Music\" instead. " +
        "Neither can see the other's files.",
)

@Composable
private fun ServerSection(music: MusicUiState, vm: MainViewModel) {
    var url by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val connected = music.server?.configured == true

    SettingsCard(
        "Music server",
        MetricInfo(
            "Music server",
            "Navidrome for the watch app; anything speaking the Subsonic API for the USB library. " +
                "Tracks keeps references, not copies.",
        ),
    ) {
        if (connected) {
            // Who is signed in, and where — the question someone opening this
            // page is usually asking.
            MusicAccountRow(music.server?.username.orEmpty(), music.server?.url, music.busy) { vm.disconnectMusicServer() }
        } else {
            MusicConnectForm(music, vm, url, { url = it }, username, { username = it }, password, { password = it })
        }
    }
}

/** Who is signed in to the music server, and where, with the way out. */
@Composable
internal fun MusicAccountRow(user: String, url: String?, busy: Boolean, onLogOut: () -> Unit) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s3)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            user.take(1).uppercase().ifBlank { "?" },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        user.ifBlank { "Signed in" },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        hostOf(url),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DangerButton("Log out", onClick = onLogOut, enabled = !busy)
            }
}

@Composable
private fun MusicConnectForm(
    music: MusicUiState,
    vm: MainViewModel,
    url: String, setUrl: (String) -> Unit,
    username: String, setUsername: (String) -> Unit,
    password: String, setPassword: (String) -> Unit,
) {
            // A found server lands in the field, not in the settings: the
            // login still has to be typed and checked.
            LaunchedEffect(music.foundServer) { music.foundServer?.let { setUrl(it) } }
            OutlinedTextField(
                value = url, onValueChange = setUrl,
                label = { Text("Server address") },
                placeholder = { Text("music.example.com or 10.0.0.5") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            TonalButton(if (music.scan != null) "Stop searching" else "Find it on my network", onClick = { vm.searchLanForMusicServer() }, modifier = Modifier.fillMaxWidth(), enabled = !music.busy)
            music.scan?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
                value = username, onValueChange = setUsername,
                label = { Text("Username") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            PasswordField(
                value = password, onValueChange = setPassword,
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryButton(if (music.busy) "Checking…" else "Connect", onClick = { vm.connectMusicServer(url, username, password) }, modifier = Modifier.fillMaxWidth(), enabled = !music.busy && url.isNotBlank() && username.isNotBlank())
            if (music.busy && music.message != null) {
                Text(music.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
}

/** "music.example.com" from "https://music.example.com:4533/" — the part a person recognises. */
internal fun hostOf(url: String?): String =
    url.orEmpty().substringAfter("://").substringBefore('/').ifBlank { url.orEmpty() }

/**
 * The phone's pick of what the watch keeps. Optional by design: the watch has
 * the same list on its own screen, and whichever was touched last wins — this
 * one only reaches the watch when Send is pressed, and then once.
 */
@Composable
private fun WatchPlaylistsSection(music: MusicUiState, vm: MainViewModel) {
    SettingsCard(
        "Playlists on the watch",
        MetricInfo(
            "Playlists on the watch",
            "Optional — you can pick these on the watch too. \"Liked songs\" and \"Recently played\" " +
                "follow what you listen to; the rest are your server's playlists. The watch re-syncs " +
                "them whenever it charges on Wi-Fi.",
            *TWO_LIBRARIES.body.toTypedArray(),
        ),
    ) {
        Column {
            for (pl in music.watchPlaylists) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = pl.id in music.watchSelection,
                        onCheckedChange = { vm.setWatchPlaylist(pl.id, it) },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(pl.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        if (pl.songCount != null) {
                            Text(
                                "${pl.songCount} songs",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        SingleSongs(music, vm)
        PrimaryButton("Send this selection to the watch", onClick = { vm.sendWatchPlaylists() }, modifier = Modifier.fillMaxWidth(), enabled = !music.busy)
        if (music.watchSelectionPending) {
            Text(
                "Queued — open Tracks Music on the watch to pick it up.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Single songs, on top of whole playlists. A search box because the phone
 * has a keyboard and the watch does not; the watch offers the same songs by
 * browsing albums instead.
 */
@Composable
private fun SingleSongs(music: MusicUiState, vm: MainViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    val picked = music.watchSongs.map { it.id }.toSet()

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Single songs",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        for (song in music.watchSongs) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = true, onCheckedChange = { vm.dropWatchSong(song.id) })
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    if (song.artist != null) {
                        Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; vm.searchWatchSongs(it) },
            label = { Text("Find a song") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (music.searching) {
            CircularProgressIndicator(Modifier.padding(4.dp))
        }
        for (song in music.songResults.filter { it.id !in picked }.take(12)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = false, onCheckedChange = { vm.setWatchSong(song, true) })
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        listOfNotNull(song.artist, song.album).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SmartSection(music: MusicUiState, vm: MainViewModel) {
    SettingsCard(
        "USB library — what to carry",
        MetricInfo(
            "USB library",
            "For the cable path only. These refresh themselves on the Tracks server — \"Recently " +
                "played\" keeps meaning recently played, not whatever was playing the day you picked it.",
            *TWO_LIBRARIES.body.toTypedArray(),
        ),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (kind in music.smart) {
                FilterChip(
                    selected = kind.imported,
                    onClick = { vm.setSmartPlaylist(kind.id, !kind.imported) },
                    enabled = !music.busy,
                    label = { Text(kind.label) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun WatchAppSection(music: MusicUiState, vm: MainViewModel) {
    SettingsCard(
        "Watch app",
        MetricInfo(
            "Tracks Music",
            "Sent straight from this phone over Bluetooth — no Connect IQ store, no Garmin account, " +
                "no cable. On first run it asks this phone for the music server and signs in by itself.",
            "It will not appear in the watch's file list — media apps are stored hidden. Find it " +
                "under Music › Music Providers.",
        ),
    ) {
        if (music.watchAppInstalled) {
            Text(
                "On the watch — Music › Music Providers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            PrimaryButton(if (music.busy) "Sending…" else "Send music app to watch", onClick = { vm.installWatchMusicApp() }, modifier = Modifier.fillMaxWidth(), enabled = !music.busy && music.server?.configured == true)
            if (music.server?.configured != true) {
                Text(
                    "Connect a music server first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ManageAppsSection(music, vm)
    }
}

/**
 * The watch's own list of installed apps, so any of them — Tracks Music
 * included — can be removed from the phone.
 *
 * The list is asked for only when the user opens it: reading it wakes a
 * Bluetooth exchange with the watch, which is not worth doing on every visit
 * to this screen. Tracks Music is called out so the one app this screen put
 * there is easy to find among the rest.
 */
@Composable
private fun ManageAppsSection(music: MusicUiState, vm: MainViewModel) {
    val apps = music.installedApps
    var pendingDelete by remember { mutableStateOf<GarminIntegration.InstalledApp?>(null) }

    pendingDelete?.let { app ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove ${app.name}?") },
            text = {
                Text(
                    "This deletes the app from the watch. Anything it had stored " +
                        "there — downloaded music, its settings — goes with it, and " +
                        "putting it back means installing it again.",
                )
            },
            confirmButton = {
                DangerButton("Remove", onClick = { pendingDelete = null; vm.deleteWatchApp(app) })
            },
            dismissButton = { NeutralButton("Keep", onClick = { pendingDelete = null }) },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (apps == null) {
            TonalButton(if (music.appsBusy) "Reading the watch…" else "Manage music apps on the watch", onClick = { vm.loadInstalledApps() }, modifier = Modifier.fillMaxWidth(), enabled = !music.appsBusy)
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeading("Music apps on the watch")
                TonalButton(if (music.appsBusy) "Refreshing…" else "Refresh", onClick = { vm.loadInstalledApps() }, enabled = !music.appsBusy)
            }
            if (apps.isEmpty()) {
                Text(
                    "No music apps on the watch.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                apps.forEach { app -> InstalledAppRow(app, music.appsBusy) { pendingDelete = app } }
            }
        }
    }
}

@Composable
private fun InstalledAppRow(app: GarminIntegration.InstalledApp, busy: Boolean, onDelete: () -> Unit) {
    val ours = app.isTracksMusic
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                app.name.ifBlank { "Untitled app" },
                style = MaterialTheme.typography.bodyMedium,
                color = if (ours) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (ours) FontWeight.Bold else FontWeight.Normal,
            )
            Text(
                if (ours) "Tracks Music" else appTypeLabel(app.type),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DangerButton("Remove", onClick = onDelete, enabled = !busy)
    }
}

/** The GDI app-type enum, in words the user recognises. */
private fun appTypeLabel(type: Int): String = when (type) {
    1 -> "Watch app"
    2 -> "Widget"
    3 -> "Watch face"
    4 -> "Data field"
    7 -> "Music provider"
    8 -> "Activity"
    else -> "App"
}

@Composable
private fun LibraryHeading(music: MusicUiState) {
    val carried = music.tracks.count { it.loadToDevice }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeading("USB library")
        val plan = music.plan
        Text(
            buildString {
                append("${music.tracks.size} tracks · $carried carried")
                if (plan != null && plan.bytesToAdd > 0) {
                    append(" · ${(plan.bytesToAdd / 1024.0 / 1024.0).roundToInt()} MB to send")
                }
                if (plan != null) {
                    append(" · ${plan.onDeviceAfter}/${plan.deviceFileLimit} files on watch")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TrackRow(track: MusicTrackSummary, onCarry: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = track.loadToDevice, onCheckedChange = onCarry)
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                listOfNotNull(track.artist, track.album).joinToString(" · ").ifBlank { "Unknown artist" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (track.onWatch) {
            Text(
                "USB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}
