// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tracks.app.ui.components.ButtonRow
import com.tracks.app.ui.components.TracksSwitch
import com.tracks.app.AppContainer
import com.tracks.app.UiState
import com.tracks.app.ui.components.DangerButton
import com.tracks.app.ui.components.InfoTip
import com.tracks.app.ui.components.MetricInfo
import com.tracks.app.ui.components.NeutralButton
import com.tracks.app.ui.components.PrimaryButton
import com.tracks.app.ui.components.TonalButton
import com.tracks.app.ui.profile.AgpsForm
import com.tracks.app.ui.profile.BackupSection
import com.tracks.app.ui.profile.BodyForm
import com.tracks.app.ui.profile.FeaturesForm
import com.tracks.app.ui.profile.FrequencyForm
import com.tracks.app.ui.profile.LookForm
import com.tracks.app.ui.profile.ProfileState
import com.tracks.app.ui.profile.SetField
import com.tracks.app.ui.profile.StrengthForm
import com.tracks.app.ui.profile.TrainingPrefsForm
import com.tracks.app.ui.profile.ZonesForm
import com.tracks.app.ui.theme.Tokens
import com.tracks.core.api.SessionState

/**
 * Settings, in the web's sections and order: who you are, how the app looks,
 * zones, strength, training, what reaches beyond the phone, then the watch,
 * backup, and the server.
 *
 * Every account setting here is the same synced field the web edits (see
 * [com.tracks.app.ui.profile.ProfileViewModel]), so a change on either reaches
 * the other; the watch, feeds and notification cards are this phone's own.
 *
 * The server sits last rather than first because it is optional now: a phone
 * with none is a complete Tracks, and "Link a server" is one choice among the
 * others, not the gate it used to be.
 *
 * The [SessionState.VaultLocked] branch is the one worth reading. Tracks
 * encrypts GPS at rest with a key the server holds only while a crypto session
 * is live — and that session dies on its own schedule, weekly, or whenever the
 * Redis container restarts. What comes back is a 401, and treating it the way
 * clients normally treat a 401 would sign the user out over a lapse that a
 * password re-entry fixes in seconds. So the vault gets its own state and its
 * own prompt, and the data on the phone stays put.
 */
@Composable
fun SettingsScreen(
    state: UiState,
    profile: ProfileState,
    onSet: SetField,
    container: AppContainer,
    linked: Boolean,
    feeds: List<FeedStatus>,
    onServerUrlChange: (String) -> Unit,
    onCheck: () -> Unit,
    onLogin: (String, String) -> Unit,
    onSync: () -> Unit,
    onWatchPair: () -> Unit,
    onWatchSync: () -> Unit,
    /** Whether the user owns a watch at all — see [DeviceCard]. */
    hasDevice: Boolean,
    onHasDeviceChange: (Boolean) -> Unit,
    onLogout: () -> Unit,
    onErase: () -> Unit,
    onReparseHealth: () -> Unit,
    onRestored: () -> Unit,
    sportLabel: (String) -> String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Tokens.Space.s4),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.s4),
    ) {
        // Nothing account-shaped renders until the row is read: a form drawn
        // over an empty map would show every field as unset for a frame, and
        // a tap in that frame would write a default over the real value.
        if (profile.loaded) {
            SettingsCard("Profile") { BodyForm(profile, onSet) }
            SettingsCard("Appearance") { LookForm(profile, onSet) }
            SettingsCard("Heart rate & power") { ZonesForm(profile, onSet) }
            SettingsCard("Strength") { StrengthForm(profile, onSet) }
            SettingsCard("Training") { TrainingPrefsForm(profile, onSet, sportLabel) }
            SettingsCard(
                "How often you train",
                MetricInfo(
                    "How often you train",
                    "Until Tracks has your own history of a sport, this sets where its plan starts.",
                ),
            ) { FrequencyForm(profile, onSet) }
            SettingsCard("Privacy & data") { FeaturesForm(profile, onSet) }
        }

        DeviceCard(hasDevice = hasDevice, onChange = onHasDeviceChange)

        // Absent entirely for a phone-only user rather than shown disabled. A
        // greyed-out control is a promise that something could be turned on;
        // there is nothing here to turn on if you do not own the hardware, and
        // the toggle above is where that changes.
        if (hasDevice) {
            WatchCard(
                watch = state.watch,
                busy = state.busy,
                onPair = onWatchPair,
                onSync = onWatchSync,
            )
            if (profile.loaded) SettingsCard("Satellite pre-fetch") { AgpsForm(profile, onSet) }

            FeedsCard(feeds)

            // Directly under the feeds, because it is the same subject read
            // from the other end: that card says whether notifications reach
            // the watch at all, this one says which of them should.
            NotificationRelayCard()
        }

        SettingsCard(
            "Backup",
            MetricInfo(
                "Backup",
                if (linked) "Your server keeps a copy, so a file backup is optional."
                else "This phone holds the only copy. Keep an encrypted backup somewhere else.",
            ),
        ) { BackupSection(container, linked, onRestored) }

        ServerCard(state, onServerUrlChange, onCheck, linked = linked)

        when (state.session) {
            is SessionState.LoggedOut -> SignInCard(
                prompt = null,
                busy = state.busy,
                onLogin = onLogin,
            )
            is SessionState.VaultLocked -> SignInCard(
                // Not a logout, and it must not read like one — the session is
                // fine, only the decryption key lapsed, and everything already
                // on the phone stays readable.
                prompt = "Locked — enter your password. You're still signed in and nothing is lost.",
                busy = state.busy,
                onLogin = onLogin,
            )
            else -> SignedInCard(state, onSync, onLogout, onReparseHealth)
        }

        if (linked) WebOnlyCard(state.serverUrl)

        EraseCard(busy = state.busy, linked = linked, onErase = onErase)
    }
}

/**
 * What only the web edits: AI coaching keys, the music server, other accounts,
 * sync agents and map regions. Each is a server-side service or secret, so the
 * phone links out to where it lives rather than growing a second copy of an
 * admin panel it could only half-drive.
 */
@Composable
private fun WebOnlyCard(serverUrl: String) {
    val uriHandler = LocalUriHandler.current
    SettingsCard(
        "On your server",
        MetricInfo(
            "On your server",
            "AI coaching, the music server, accounts, sync agents and map regions are server-side " +
                "services and secrets, so they are managed in Tracks on the web.",
        ),
    ) {
        TonalButton("Open web settings", onClick = { runCatching { uriHandler.openUri(serverUrl.trimEnd('/') + "/settings") } }, enabled = serverUrl.isNotBlank())
    }
}

/**
 * Erasing this phone's data — the explicit step before a different account can
 * use it. Behind a confirmation that says what is lost, because for a phone
 * that was never linked (or has unsent edits) it is the only copy.
 */
@Composable
private fun EraseCard(busy: Boolean, linked: Boolean, onErase: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    SettingsCard("Erase") {
        DangerButton("Erase this phone's data", onClick = { confirming = true }, enabled = !busy)
    }
    if (confirming) {
        // What is lost is said here, at the moment it matters, rather than
        // on the card every visit.
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Erase this phone's data?") },
            text = {
                Text(
                    "Every activity, plan, log and setting on this phone goes. " +
                        if (linked) "Anything not yet sent to your server is lost." else "Without a backup, it cannot be undone.",
                )
            },
            confirmButton = {
                DangerButton("Erase", onClick = { confirming = false; onErase() })
            },
            dismissButton = { NeutralButton("Cancel", onClick = { confirming = false }) },
        )
    }
}


/**
 * Whether there is a watch in this user's life.
 *
 * The switch that decides how much of this app is about hardware. Off, the watch
 * pairing and sync controls and the phone-feed forwarding all disappear —
 * they are meaningless without a device to talk to, and leaving them visible
 * makes a working app look broken to someone who simply does not own one.
 *
 * Nothing is deleted by turning it off. A watch that was paired stays paired,
 * and turning it back on brings every control back in the state it was left in;
 * this hides UI, it does not unpair anything.
 */
@Composable
private fun DeviceCard(hasDevice: Boolean, onChange: (Boolean) -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "I have a Garmin watch",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TracksSwitch(checked = hasDevice, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun ServerCard(
    state: UiState,
    onServerUrlChange: (String) -> Unit,
    onCheck: () -> Unit,
    linked: Boolean,
) {
    var draft by remember(state.serverUrl) { mutableStateOf(state.serverUrl) }
    // A linked phone shows where it syncs to and nothing else; the address
    // is rarely changed, so its editor waits behind "Change".
    var editing by remember { mutableStateOf(!linked) }
    SettingsCard(
        if (linked) "Server" else "Link a server",
        if (linked) null else MetricInfo(
            "Link a server",
            "A self-hosted Tracks server keeps a second copy on hardware you control, adds map " +
                "basemaps, and lets you use Tracks on the web. This phone's data merges into it.",
        ),
    ) {
        if (!editing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(state.serverUrl, style = MaterialTheme.typography.bodyMedium)
                    state.capabilities?.let { caps ->
                        Text(
                            "${caps.app} ${caps.serverVersion} · API v${caps.apiVersion}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                NeutralButton("Change", onClick = { editing = true })
            }
            return@SettingsCard
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("Server URL") },
            placeholder = { Text("https://tracks.example.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        ButtonRow {
            PrimaryButton("Save", onClick = { onServerUrlChange(draft) }, enabled = !state.busy)
            TonalButton("Check", onClick = onCheck, enabled = !state.busy && state.serverUrl.isNotBlank())
        }
        state.capabilities?.let { caps ->
            Text(
                "${caps.app} ${caps.serverVersion} · API v${caps.apiVersion}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SignInCard(
    prompt: String?,
    busy: Boolean,
    onLogin: (String, String) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    SettingsCard("Account") {
        prompt?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton("Sign in", onClick = { onLogin(username, password) }, enabled = !busy && username.isNotBlank() && password.isNotBlank())
    }
}

@Composable
private fun SignedInCard(
    state: UiState,
    onSync: () -> Unit,
    onLogout: () -> Unit,
    onReparseHealth: () -> Unit,
) {
    SettingsCard(
        "Account",
        MetricInfo(
            "Account",
            "Unlocks on its own: this phone holds a device key, so you are not asked for your " +
                "password when the server's session lapses. Signing out keeps this phone's data.",
            "Re-read health files: re-reads the files your watch has already sent, so history an " +
                "older reader dropped (steps, stress, respiration) fills in.",
        ),
    ) {
        Text(
            if (state.deviceKeyEnrolled) "Unlocks on its own" else "May ask for your password again",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ButtonRow {
            PrimaryButton("Sync now", onClick = onSync, enabled = !state.busy)
            NeutralButton("Sign out", onClick = onLogout, enabled = !state.busy)
        }
        TonalButton("Re-read health files", onClick = onReparseHealth, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * The shell every block on this screen sits in — shared with [NotificationRelayCard].
 *
 * [info] puts the explanation behind a "?" beside the title. The screen was a
 * wall of paragraphs, each true and none needed twice; one short line (or none)
 * in the card, and the why for anyone who asks.
 */
@Composable
internal fun SettingsCard(title: String, info: MetricInfo? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s2)) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            info?.let { InfoTip(it) }
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
            ) { content() }
        }
    }
}
