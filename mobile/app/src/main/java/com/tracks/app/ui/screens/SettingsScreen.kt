// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.tracks.app.ui.components.ButtonRow
import com.tracks.app.AppContainer
import com.tracks.app.UiState
import com.tracks.app.ui.components.DangerButton
import com.tracks.app.ui.components.InfoTip
import com.tracks.app.ui.components.MetricInfo
import com.tracks.app.ui.components.NeutralButton
import com.tracks.app.ui.components.PasswordField
import com.tracks.app.ui.components.PrimaryButton
import com.tracks.app.ui.components.TonalButton
import com.tracks.app.ui.profile.AiCoachingRow
import com.tracks.app.ui.profile.BackupSection
import com.tracks.app.ui.profile.BodyForm
import com.tracks.app.ui.profile.FrequencyForm
import com.tracks.app.ui.profile.LookForm
import com.tracks.app.ui.profile.PrivacyForm
import com.tracks.app.ui.profile.ProfileState
import com.tracks.app.ui.profile.SetField
import com.tracks.app.ui.profile.StrengthForm
import com.tracks.app.ui.profile.ToggleRow
import com.tracks.app.ui.profile.TrainingPrefsForm
import com.tracks.app.ui.profile.ZonesForm
import com.tracks.app.ui.theme.Tokens
import com.tracks.app.ui.tour.TourAnchor
import com.tracks.core.api.SessionState

/**
 * Settings, in the web's sections and order, so the two read as one page:
 * who you are, how the app looks, zones, training, strength, the watch, what
 * reaches beyond your hardware, then the server, backup and erasing.
 *
 * Every section is open, with nothing behind an expander: the page was
 * reorganised to be scanned, and a control that must be found by opening
 * things is one people assume does not exist. What keeps it short is
 * grouping — one card per subject, with related rows inside it — and putting
 * explanations behind each title's "?" rather than under every field.
 *
 * Every account setting here is the same synced field the web edits (see
 * [com.tracks.app.ui.profile.ProfileViewModel]), so a change on either reaches
 * the other; the watch, feeds and notification rows are this phone's own, and
 * AI coaching is the server's (see [AiCoachingRow]).
 *
 * The server sits after the settings rather than first because it is
 * optional: a phone with none is a complete Tracks, and "Link a server" is
 * one choice among the others, not the gate it used to be.
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
    /** Whether the user owns a watch at all — see [WatchSection]. */
    hasDevice: Boolean,
    onHasDeviceChange: (Boolean) -> Unit,
    onLogout: () -> Unit,
    onErase: () -> Unit,
    onReparseHealth: () -> Unit,
    onRestored: () -> Unit,
    sportLabel: (String) -> String,
    /** The tutorial's switch — see [com.tracks.app.ui.tour.TourViewModel]. */
    tutorialEnabled: Boolean,
    onTutorialEnabled: (Boolean) -> Unit,
    onRestartTutorial: () -> Unit,
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
            TourAnchor("settings-tutorial") {
                SettingsCard("Tutorial") {
                    TutorialForm(tutorialEnabled, onTutorialEnabled, onRestartTutorial)
                }
            }
            SettingsCard("Heart rate & power") { ZonesForm(profile, onSet) }
            SettingsCard("Training") {
                TrainingPrefsForm(profile, onSet, sportLabel)
                SectionDivider()
                SubHeading(
                    "How often you train",
                    MetricInfo(
                        "How often you train",
                        "Until Tracks has your own history of a sport, this sets where its plan starts.",
                    ),
                )
                FrequencyForm(profile, onSet)
            }
            SettingsCard("Strength") { StrengthForm(profile, onSet) }
        }

        TourAnchor("settings-watch") {
        WatchSection(
            state = state,
            profile = profile,
            onSet = onSet,
            feeds = feeds,
            hasDevice = hasDevice,
            onHasDeviceChange = onHasDeviceChange,
            onPair = onWatchPair,
            onSync = onWatchSync,
        )
        }
        // Its own card under the watch: which notifications reach the wrist
        // is a subject of its own, with its own sheets.
        if (hasDevice) NotificationRelayCard()

        if (profile.loaded) {
            SettingsCard("Privacy & connectivity") {
                PrivacyForm(profile, onSet, hasDevice) { AiCoachingRow(container, linked) }
            }
        }

        TourAnchor("settings-server") {
        ServerSection(
            state = state,
            linked = linked,
            onServerUrlChange = onServerUrlChange,
            onCheck = onCheck,
            onLogin = onLogin,
            onSync = onSync,
            onLogout = onLogout,
            onReparseHealth = onReparseHealth,
        )
        }

        VersionCard(
            container = container,
            serverVersion = state.capabilities?.serverVersion,
            // Vault-locked still counts: /version needs a token, not the vault.
            signedIn = linked && state.session !is SessionState.LoggedOut,
        )

        TourAnchor("settings-backup") {
        SettingsCard(
            "Backup",
            MetricInfo(
                "Backup",
                if (linked) "Your server keeps a copy, so a file backup is optional."
                else "This phone holds the only copy. Keep an encrypted backup somewhere else.",
            ),
        ) { BackupSection(container, linked, onRestored) }
        }

        DangerZone(busy = state.busy, linked = linked, onErase = onErase)
    }
}

/**
 * The watch, and whether there is one in this user's life.
 *
 * The switch decides how much of this app is about hardware. Off, the watch
 * status, pace coaching, satellite pre-fetch and the phone feeds all
 * disappear — they are meaningless without a device to talk to, and leaving
 * them visible makes a working app look broken to someone who simply does not
 * own one. Absent rather than greyed out: a disabled control is a promise
 * that something could be turned on, and there is nothing to turn on here.
 *
 * Nothing is deleted by turning it off. A watch that was paired stays paired,
 * and turning it back on brings every control back in the state it was left in;
 * this hides UI, it does not unpair anything.
 */
@Composable
private fun WatchSection(
    state: UiState,
    profile: ProfileState,
    onSet: SetField,
    feeds: List<FeedStatus>,
    hasDevice: Boolean,
    onHasDeviceChange: (Boolean) -> Unit,
    onPair: () -> Unit,
    onSync: () -> Unit,
) {
    SettingsCard(
        "Watch",
        MetricInfo("Watch", "Syncs about hourly while in range — no signal needed."),
    ) {
        ToggleRow("I have a Garmin watch", null, hasDevice, onHasDeviceChange)
        if (!hasDevice) return@SettingsCard
        SectionDivider()
        WatchStatus(watch = state.watch, busy = state.busy, onPair = onPair, onSync = onSync)
        if (profile.loaded) {
            SectionDivider()
            ToggleRow(
                "Pace coaching",
                "Planned runs carry pace targets the watch coaches you to. Sync the watch after changing it.",
                profile.bool("pace_coaching", false),
            ) { onSet("pace_coaching", it) }
        }
        SectionDivider()
        SubHeading("Phone feeds")
        FeedRows(feeds)
    }
}

/**
 * The server: where this phone syncs to, the account on it, and what only the
 * web edits — one card, because each part only means something with the
 * others (an address with no account syncs nothing).
 */
@Composable
private fun ServerSection(
    state: UiState,
    linked: Boolean,
    onServerUrlChange: (String) -> Unit,
    onCheck: () -> Unit,
    onLogin: (String, String) -> Unit,
    onSync: () -> Unit,
    onLogout: () -> Unit,
    onReparseHealth: () -> Unit,
) {
    val signedIn = state.session !is SessionState.LoggedOut && state.session !is SessionState.VaultLocked
    SettingsCard(
        if (linked) "Server" else "Link a server",
        if (linked) {
            MetricInfo(
                "Server",
                "Unlocks on its own: this phone holds a device key, so you are not asked for your " +
                    "password when the server's session lapses. Signing out keeps this phone's data.",
                "Re-read health files: re-reads the files your watch has already sent, so history an " +
                    "older reader dropped (steps, stress, respiration) fills in.",
            )
        } else {
            MetricInfo(
                "Link a server",
                "A self-hosted Tracks server keeps a second copy on hardware you control, adds map " +
                    "basemaps and AI coaching, and lets you use Tracks on the web. This phone's data merges into it.",
            )
        },
    ) {
        ServerAddress(state, onServerUrlChange, onCheck, linked)
        SectionDivider()
        when (state.session) {
            is SessionState.LoggedOut -> SignIn(prompt = null, busy = state.busy, onLogin = onLogin)
            is SessionState.VaultLocked -> SignIn(
                // Not a logout, and it must not read like one — the session is
                // fine, only the decryption key lapsed, and everything already
                // on the phone stays readable.
                prompt = "Locked — enter your password. You're still signed in and nothing is lost.",
                busy = state.busy,
                onLogin = onLogin,
            )
            else -> SignedIn(state, onSync, onLogout, onReparseHealth)
        }
        if (linked && signedIn) {
            SectionDivider()
            WebOnly(state.serverUrl)
        }
    }
}

/**
 * What only the web edits: other accounts, the music server, sync agents, map
 * regions and the password. Each is a server-side service or secret, so the
 * phone links out to where it lives rather than growing a second copy of an
 * admin panel it could only half-drive.
 *
 * The password especially: changing it revokes every device key and refresh
 * token (backend users.py `change_password`), this phone's included, so done
 * from here it would sign the phone out mid-change and need a sign-in flow
 * built around that. On the web it costs nothing.
 */
@Composable
private fun WebOnly(serverUrl: String) {
    val uriHandler = LocalUriHandler.current
    Text(
        "Password, accounts, the music server, sync agents and map regions are managed in Tracks on the web.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TonalButton(
        "Open web settings",
        onClick = { runCatching { uriHandler.openUri(serverUrl.trimEnd('/') + "/settings") } },
        enabled = serverUrl.isNotBlank(),
    )
}

/**
 * Erasing this phone's data — the explicit step before a different account can
 * use it. Behind a confirmation that says what is lost, because for a phone
 * that was never linked (or has unsent edits) it is the only copy.
 */
/**
 * The tutorial's two controls, the web's TutorialSection: tips on or off, and
 * a replay. Both are account settings, so they follow the user to the browser.
 */
@Composable
private fun TutorialForm(enabled: Boolean, onEnabled: (Boolean) -> Unit, onRestart: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s3)) {
        ToggleRow(
            "Show tutorial tips",
            "Guided tips appear the first time you open each page.",
            enabled,
            onEnabled,
        )
        SectionDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).padding(end = Tokens.Space.s3)) {
                com.tracks.app.ui.profile.LabelWithTip(
                    "Replay the tutorial",
                    "Start over from the Dashboard and see every page's tips again.",
                    MaterialTheme.typography.bodyLarge,
                )
            }
            PrimaryButton("Restart", onClick = onRestart, small = true)
        }
    }
}

@Composable
private fun DangerZone(busy: Boolean, linked: Boolean, onErase: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    SettingsCard("Danger zone") {
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

@Composable
private fun ServerAddress(
    state: UiState,
    onServerUrlChange: (String) -> Unit,
    onCheck: () -> Unit,
    linked: Boolean,
) {
    var draft by remember(state.serverUrl) { mutableStateOf(state.serverUrl) }
    // A linked phone shows where it syncs to and nothing else; the address
    // is rarely changed, so its editor waits behind "Change".
    var editing by remember { mutableStateOf(!linked) }
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
        return
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

@Composable
private fun SignIn(
    prompt: String?,
    busy: Boolean,
    onLogin: (String, String) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
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
    PasswordField(
        value = password,
        onValueChange = { password = it },
        modifier = Modifier.fillMaxWidth(),
    )
    PrimaryButton("Sign in", onClick = { onLogin(username, password) }, enabled = !busy && username.isNotBlank() && password.isNotBlank())
}

@Composable
private fun SignedIn(
    state: UiState,
    onSync: () -> Unit,
    onLogout: () -> Unit,
    onReparseHealth: () -> Unit,
) {
    Text(
        if (state.deviceKeyEnrolled) "Signed in · unlocks on its own" else "Signed in · may ask for your password again",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ButtonRow {
        PrimaryButton("Sync now", onClick = onSync, enabled = !state.busy)
        NeutralButton("Sign out", onClick = onLogout, enabled = !state.busy)
    }
    TonalButton("Re-read health files", onClick = onReparseHealth, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
}

/** The rule between subjects inside one card. */
@Composable
private fun SectionDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

/** A heading for a group of rows inside a card, a step below the card's own title. */
@Composable
private fun SubHeading(title: String, info: MetricInfo? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s2)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        info?.let { InfoTip(it) }
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
