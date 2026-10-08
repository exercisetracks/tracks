// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.tracks.app.MainViewModel
import com.tracks.app.MusicUiState
import com.tracks.app.ui.components.ButtonRow
import com.tracks.app.ui.components.DangerButton
import com.tracks.app.ui.components.MetricInfo
import com.tracks.app.ui.components.NeutralButton
import com.tracks.app.ui.components.PasswordField
import com.tracks.app.ui.components.PrimaryButton
import com.tracks.app.ui.components.TonalButton
import com.tracks.app.ui.screens.SettingsCard
import com.tracks.app.ui.theme.Tokens
import com.tracks.device.garmin.GarminIntegration

/**
 * Music, in Settings.
 *
 * It was a page of its own, and a page was more than it is: a login, a watch
 * app to send, and an optional pick of playlists. The phone cannot carry music
 * itself — Bluetooth has no file type for audio, on any Garmin — so everything
 * here serves the Tracks Music watch app, which downloads from the user's
 * music server over the watch's own Wi-Fi and needs the phone only to be told
 * where that server is.
 *
 * The USB library is not here at all. Pushing files into the watch's own "My
 * Music" needs a cable and a computer, and choosing what to carry belongs with
 * that, in the web app.
 */
@Composable
fun MusicSettings(vm: MainViewModel, music: MusicUiState) {
    // Not armed on load — see MainViewModel.loadMusicSetup.
    LaunchedEffect(Unit) { vm.loadMusicSetup(arm = false) }
    val connected = music.server?.configured == true

    SettingsCard(
        "Music",
        MetricInfo(
            "Music",
            "The Tracks Music watch app downloads playlists straight from your music server " +
                "(Navidrome) over the watch's own Wi-Fi, on the charger, and plays them in its " +
                "own player. Tracks keeps the login, not the music.",
            "Music sent over USB from the web app plays under the watch's own \"My Music\" " +
                "instead. Neither can see the other's files.",
        ),
    ) {
        MusicServerRow(music, vm)
        WatchAppRows(music, vm, connected)
        if (connected && music.watchPlaylists.isNotEmpty()) WatchPlaylists(music, vm)
        if (music.message != null && !music.busy) {
            Text(music.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * The account, or the way to one. Shared with onboarding's music step, so the
 * two sign in the same way.
 */
@Composable
internal fun MusicServerRow(music: MusicUiState, vm: MainViewModel) {
    var connecting by rememberSaveable { mutableStateOf(false) }
    if (music.server?.configured == true) {
        MusicAccountRow(music.server?.username.orEmpty(), music.server?.url, music.busy) { vm.disconnectMusicServer() }
    } else {
        Text(
            "Connect your music server and the watch can play from it — no cable, no Garmin account.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PrimaryButton("Connect server", onClick = { connecting = true }, modifier = Modifier.fillMaxWidth())
    }
    if (connecting) {
        MusicServerConnectDialog(
            music,
            onSearch = vm::searchLanForMusicServer,
            onFind = vm::findMusicServer,
            onLogIn = vm::logInMusicServer,
            onDismiss = { connecting = false },
        )
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
            Text(user.ifBlank { "Signed in" }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(hostOf(url), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DangerButton("Log out", onClick = onLogOut, enabled = !busy)
    }
}

/** "music.example.com" from "https://music.example.com:4533/" — the part a person recognises. */
internal fun hostOf(url: String?): String =
    url.orEmpty().substringAfter("://").substringBefore('/').ifBlank { url.orEmpty() }

/**
 * Connecting a music server, one question at a time: where is it, then — once
 * something there has answered as a music server — who are you.
 *
 * It replaced a form that asked for the address, username and password at
 * once, with a network search wedged between them. Credentials typed for an
 * address nothing was listening on were the commonest dead end; here the login
 * is not asked for until there is something to log in to. A server the
 * network search turns up has already answered, so it goes straight to the
 * login.
 */
@Composable
internal fun MusicServerConnectDialog(
    music: MusicUiState,
    onSearch: () -> Unit,
    /** The address typed; answers with the server that answered, or null and why. */
    onFind: (String, (String?, String?) -> Unit) -> Unit,
    /** Address, username, password; answers with null on success or why not. */
    onLogIn: (String, String, String, (String?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = { if (!music.busy) onDismiss() }) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            MusicServerConnectSteps(music, onSearch, onFind, onLogIn, onDismiss, Modifier.padding(24.dp))
        }
    }
}

/**
 * The dialog's contents, apart from its window — so they can be tested: a text
 * field inside a dialog window never settles under Robolectric.
 */
@Composable
internal fun MusicServerConnectSteps(
    music: MusicUiState,
    onSearch: () -> Unit,
    onFind: (String, (String?, String?) -> Unit) -> Unit,
    onLogIn: (String, String, String, (String?) -> Unit) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var url by rememberSaveable { mutableStateOf("") }
    var found by rememberSaveable { mutableStateOf<String?>(null) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    // A search result from before this dialog opened is not an answer to it.
    val foundBefore = remember { music.foundServer }
    LaunchedEffect(music.foundServer) {
        val hit = music.foundServer
        if (hit != null && hit != foundBefore) { url = hit; found = hit; error = null }
    }
    val busy = music.busy
    val server = found

    Column(modifier, verticalArrangement = Arrangement.spacedBy(Tokens.Space.s3)) {
        Text(
            if (server == null) "Connect music server" else "Sign in",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (server == null) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; error = null },
                label = { Text("Server address") },
                placeholder = { Text("music.example.com or 10.0.0.5") },
                supportingText = { Text("http/https and the usual ports are tried for you") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            TonalButton(
                if (music.scan != null) "Stop searching" else "Find it on my network",
                onClick = onSearch,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )
            music.scan?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            Text(
                "Found a music server at ${hostOf(server)}. Sign in with your account on it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it; error = null },
                label = { Text("Username") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            PasswordField(value = password, onValueChange = { password = it; error = null }, modifier = Modifier.fillMaxWidth())
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        ButtonRow {
            if (server == null) {
                NeutralButton("Cancel", onClick = onDone, enabled = !busy)
                PrimaryButton(
                    if (busy) "Looking…" else "Next",
                    onClick = { onFind(url) { hit, why -> found = hit; error = why } },
                    enabled = !busy && url.isNotBlank(),
                )
            } else {
                // Back to the address, keeping what was typed there.
                NeutralButton("Back", onClick = { found = null; error = null }, enabled = !busy)
                PrimaryButton(
                    if (busy) "Signing in…" else "Log in",
                    onClick = { onLogIn(server, username, password) { why -> if (why == null) onDone() else error = why } },
                    enabled = !busy && username.isNotBlank() && password.isNotBlank(),
                )
            }
        }
    }
}

/** Send the watch app (once there is a server for it), and manage what the watch has installed. */
@Composable
private fun WatchAppRows(music: MusicUiState, vm: MainViewModel, connected: Boolean) {
    SubHeading("Watch app")
    if (music.watchAppInstalled) {
        Text(
            "Tracks Music is on the watch — Music › Music Providers. It signs in to your server by itself.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (connected) ResendLogin(vm)
    } else if (connected) {
        Text(
            "Sent straight from this phone over Bluetooth — no Connect IQ store. It will not show in " +
                "the watch's file list; media apps are stored hidden. Find it under Music › Music Providers.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PrimaryButton(
            if (music.busy) "Sending…" else "Send music app to watch",
            onClick = { vm.installWatchMusicApp() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !music.busy,
        )
    } else {
        Text(
            "Connect a music server first — the app signs in to it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // Offered signed in or not: removing the app is as much a reason to come
    // here as installing it.
    ManageApps(music, vm)
}

/**
 * The way to hand the watch a changed login. The phone answers the watch app's
 * request for it only for ten minutes after being asked to (WatchAppConfig),
 * and merely opening Settings no longer counts.
 */
@Composable
private fun ResendLogin(vm: MainViewModel) {
    var sent by remember { mutableStateOf(false) }
    TonalButton(
        "Send the login to the watch again",
        onClick = { vm.resendWatchMusicLogin(); sent = true },
        modifier = Modifier.fillMaxWidth(),
    )
    if (sent) {
        Text(
            "Open Tracks Music on the watch in the next ten minutes to pick it up.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The watch's own list of installed apps, so any of them — Tracks Music
 * included — can be removed from the phone.
 *
 * Asked for only when opened: reading it wakes a Bluetooth exchange with the
 * watch, which is not worth doing on every visit to Settings.
 */
@Composable
private fun ManageApps(music: MusicUiState, vm: MainViewModel) {
    val apps = music.installedApps
    var pendingDelete by remember { mutableStateOf<GarminIntegration.InstalledApp?>(null) }

    pendingDelete?.let { app ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove ${app.name}?") },
            text = {
                Text(
                    "This deletes the app from the watch. Anything it had stored there — downloaded " +
                        "music, its settings — goes with it, and putting it back means installing it again.",
                )
            },
            confirmButton = { DangerButton("Remove", onClick = { pendingDelete = null; vm.deleteWatchApp(app) }) },
            dismissButton = { NeutralButton("Keep", onClick = { pendingDelete = null }) },
        )
    }

    if (apps == null) {
        TonalButton(
            if (music.appsBusy) "Reading the watch…" else "Manage music apps on the watch",
            onClick = { vm.loadInstalledApps() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !music.appsBusy,
        )
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        SubHeading("Music apps on the watch")
        TonalButton(if (music.appsBusy) "Refreshing…" else "Refresh", onClick = { vm.loadInstalledApps() }, enabled = !music.appsBusy)
    }
    if (apps.isEmpty()) {
        Text("No music apps on the watch.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    apps.forEach { app ->
        val ours = app.isTracksMusic
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
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
            DangerButton("Remove", onClick = { pendingDelete = app }, enabled = !music.appsBusy)
        }
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

/**
 * The phone's pick of what the watch keeps. Optional by design: the watch has
 * the same list on its own screen, and whichever was touched last wins — this
 * one only reaches the watch when Send is pressed, and then once.
 */
@Composable
private fun WatchPlaylists(music: MusicUiState, vm: MainViewModel) {
    SubHeading("Playlists on the watch")
    Text(
        "Optional — you can pick these on the watch too. \"Liked songs\" and \"Recently played\" follow " +
            "what you listen to. The watch re-syncs them whenever it charges on Wi-Fi.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    for (pl in music.watchPlaylists) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = pl.id in music.watchSelection, onCheckedChange = { vm.setWatchPlaylist(pl.id, it) })
            Column(Modifier.weight(1f)) {
                Text(pl.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                if (pl.songCount != null) {
                    Text("${pl.songCount} songs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    SingleSongs(music, vm)
    PrimaryButton("Send this selection to the watch", onClick = { vm.sendWatchPlaylists() }, modifier = Modifier.fillMaxWidth(), enabled = !music.busy)
    if (music.watchSelectionPending) {
        Text("Queued — open Tracks Music on the watch to pick it up.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
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
        Text("Single songs", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        for (song in music.watchSongs) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
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
        if (music.searching) CircularProgressIndicator(Modifier.padding(4.dp))
        for (song in music.songResults.filter { it.id !in picked }.take(12)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
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
private fun SubHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
}
