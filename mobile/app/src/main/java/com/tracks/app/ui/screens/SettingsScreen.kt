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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
 * The onboarding-only answers (how often you train) are not repeated here,
 * matching the web's Settings: they set where a first plan starts, and the
 * user's own history replaces them.
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
    onSearchServer: () -> Unit,
    onLogin: (String, String) -> Unit,
    onSync: () -> Unit,
    onWatchPair: () -> Unit,
    onWatchSync: () -> Unit,
    /** Whether the user owns a watch at all — see [WatchSection]. */
    hasDevice: Boolean,
    onHasDeviceChange: (Boolean) -> Unit,
    onLogout: () -> Unit,
    /** Current password, then the new one. */
    onChangePassword: (String, String) -> Unit,
    onErase: () -> Unit,
    onRestored: () -> Unit,
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
                // How often you train is not here: it is asked once in
                // onboarding, as on the web, and history replaces it.
                TrainingPrefsForm(profile, onSet)
                SectionDivider()
                // This phone's own switch, not the account's (WorkoutReminderRow).
                WorkoutReminderRow(container)
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
            onConnect = onServerUrlChange,
            onSearch = onSearchServer,
            onLogin = onLogin,
            onSync = onSync,
            onLogout = onLogout,
            onChangePassword = onChangePassword,
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

        com.tracks.app.donate.DonateSection()
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
 * The server: where this phone syncs to and the account on it, kept to a few
 * lines. Not signed in, it is one "Connect server" button that walks through
 * the address and then the sign-in ([ServerConnectForm], the same steps as
 * onboarding); signed in, it is the address, Sync now and Sign out, with the
 * password and the web-only settings a row below.
 */
@Composable
private fun ServerSection(
    state: UiState,
    linked: Boolean,
    onConnect: (String) -> Unit,
    onSearch: () -> Unit,
    onLogin: (String, String) -> Unit,
    onSync: () -> Unit,
    onLogout: () -> Unit,
    onChangePassword: (String, String) -> Unit,
) {
    var connecting by remember { mutableStateOf(false) }
    SettingsCard(
        "Server",
        MetricInfo(
            "Server",
            "A self-hosted Tracks server keeps a second copy on hardware you control, adds map " +
                "basemaps and AI coaching, and lets you use Tracks on the web. This phone's data merges into it.",
            "Once signed in, this phone holds a device key, so it unlocks on its own and is not asked for your " +
                "password when the server's session lapses. Signing out keeps this phone's data. The password, " +
                "other accounts, the music server, sync agents and map regions are managed in Tracks on the web.",
        ),
    ) {
        when (state.session) {
            is SessionState.LoggedOut -> {
                Text(
                    if (linked) "Signed out of ${state.serverUrl}." else "Not connected — everything stays on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PrimaryButton("Connect server", onClick = { connecting = true }, enabled = !state.busy)
            }
            is SessionState.VaultLocked -> SignIn(
                // Not a logout, and it must not read like one — the session is
                // fine, only the decryption key lapsed, and everything already
                // on the phone stays readable.
                prompt = "Locked — enter your password. You're still signed in and nothing is lost.",
                busy = state.busy,
                onLogin = onLogin,
            )
            else -> SignedIn(state, onSync, onLogout, onChangePassword)
        }
    }
    // Closes itself the moment the session goes live.
    if (connecting && state.session !is SessionState.LoggedOut) connecting = false
    if (connecting) {
        Dialog(onDismissRequest = { connecting = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                Modifier.fillMaxWidth().padding(Tokens.Space.s4),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(Tokens.Space.s5),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Space.s3),
                ) {
                    Text("Connect server", style = MaterialTheme.typography.titleLarge)
                    ServerConnectForm(
                        serverUrl = state.serverUrl,
                        busy = state.busy,
                        scan = state.scan,
                        connectedTo = state.capabilities?.let { "${it.app} ${it.serverVersion}" },
                        message = state.message,
                        onConnect = onConnect,
                        onSearch = onSearch,
                        onLogin = onLogin,
                    )
                    NeutralButton("Cancel", onClick = { connecting = false })
                }
            }
        }
    }
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
    onChangePassword: (String, String) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    var changingPassword by remember { mutableStateOf(false) }
    Column {
        Text(state.serverUrl, style = MaterialTheme.typography.bodyMedium)
        Text(
            listOfNotNull(
                state.capabilities?.let { "${it.app} ${it.serverVersion}" },
                if (state.deviceKeyEnrolled) "signed in · unlocks on its own" else "signed in",
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    ButtonRow {
        PrimaryButton("Sync now", onClick = onSync, enabled = !state.busy, small = true)
        NeutralButton("Sign out", onClick = onLogout, enabled = !state.busy, small = true)
    }
    ButtonRow {
        TonalButton("Change password", onClick = { changingPassword = true }, enabled = !state.busy, small = true)
        // Accounts, music, sync agents and map regions live on the web; the
        // phone links out rather than growing half an admin panel.
        TonalButton(
            "Web settings",
            onClick = { runCatching { uriHandler.openUri(state.serverUrl.trimEnd('/') + "/settings") } },
            enabled = state.serverUrl.isNotBlank(),
            small = true,
        )
    }
    if (changingPassword) {
        ChangePasswordDialog(
            onDismiss = { changingPassword = false },
            onChange = { current, new ->
                changingPassword = false
                onChangePassword(current, new)
            },
        )
    }
}

/**
 * The same three fields and the same 8-character floor as the web's Security
 * section. Changing the password signs every other device out — that is what
 * someone who thinks they are compromised needs — so the dialog says so before
 * they press it rather than after.
 */
@Composable
private fun ChangePasswordDialog(onDismiss: () -> Unit, onChange: (String, String) -> Unit) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val tooShort = new.isNotEmpty() && new.length < MIN_PASSWORD_LENGTH
    val mismatch = confirm.isNotEmpty() && confirm != new
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s2)) {
                Text(
                    "Every other device signed in to this account will be signed out.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PasswordField(current, { current = it }, Modifier.fillMaxWidth(), label = "Current password")
                PasswordField(new, { new = it }, Modifier.fillMaxWidth(), label = "New password",
                              placeholder = "At least $MIN_PASSWORD_LENGTH characters", isError = tooShort)
                PasswordField(confirm, { confirm = it }, Modifier.fillMaxWidth(), label = "Confirm new password",
                              isError = mismatch)
                if (mismatch) {
                    Text("The passwords do not match.", style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                "Change",
                onClick = { onChange(current, new) },
                enabled = current.isNotEmpty() && new.length >= MIN_PASSWORD_LENGTH && confirm == new,
            )
        },
        dismissButton = { NeutralButton("Cancel", onClick = onDismiss) },
    )
}

/** The server's floor (backend/app/api/users.py); checked here only to say so sooner. */
private const val MIN_PASSWORD_LENGTH = 8

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
