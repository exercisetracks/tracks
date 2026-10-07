// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.tracks.app.ui.components.PasswordField
import com.tracks.app.ui.components.PrimaryButton
import com.tracks.app.ui.components.TonalButton
import com.tracks.app.ui.theme.Tokens

/**
 * Connecting to a server, in two parts on one screen: the address, then —
 * only once that address has answered — the username and password.
 *
 * Shared by onboarding and Settings' "Connect server", so the two cannot
 * drift. It replaced separate Save / Check / Connect buttons and a sign-in
 * form shown before anything was known to be listening: credentials typed for
 * a server that turned out to be unreachable were the commonest dead end.
 * Sign-in stays hidden until the server is reached, and editing the address
 * hides it again until the new one is.
 *
 * [connectedTo] is the reached server's description, or null; [message] is
 * the last result to show (an error when not busy and not connected).
 */
@Composable
internal fun ServerConnectForm(
    serverUrl: String,
    busy: Boolean,
    scan: String?,
    connectedTo: String?,
    message: String?,
    onConnect: (String) -> Unit,
    onSearch: () -> Unit,
    onLogin: (String, String) -> Unit,
) {
    var draft by remember(serverUrl) { mutableStateOf(serverUrl) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    // Reached, and still the address in the field.
    val connected = connectedTo != null && draft.trim() == serverUrl.trim()

    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s3)) {
        // Offered before the text field, because when it works it is the whole
        // step — somebody who runs the server on a box in their house has no
        // reason to know its address. The search fills the field in on success,
        // so a found server can still be reviewed rather than silently adopted.
        if (!connected) {
            TonalButton(
                if (scan != null) "Stop searching" else "Find it on my network",
                onClick = onSearch, modifier = Modifier.fillMaxWidth(), enabled = !busy,
            )
            scan?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("Server address") },
            placeholder = { Text("https://tracks.example.com") },
            // The app finds the API itself, at the address or behind /api
            // (MainViewModel.checkServer), so any address the browser uses works.
            supportingText = if (connected) null else ({ Text("The address you use in a browser.") }),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!connected) {
            PrimaryButton(
                if (busy) "Connecting…" else "Connect",
                onClick = { onConnect(draft.trim()) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && draft.isNotBlank(),
            )
            if (message != null && !busy) {
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            return@Column
        }

        Text(
            "Connected to $connectedTo. Sign in once — this phone is enrolled so it keeps syncing " +
                "for weeks without asking again, even with no signal.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        PasswordField(value = password, onValueChange = { password = it }, modifier = Modifier.fillMaxWidth())
        PrimaryButton(
            if (busy) "Signing in…" else "Sign in",
            onClick = { onLogin(username.trim(), password) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && username.isNotBlank() && password.isNotBlank(),
        )
        // The check's own success line is still the message at this point;
        // only what sign-in says after it (a wrong password) is worth showing.
        val atConnect = remember(connectedTo) { message }
        if (message != null && !busy && message != atConnect) {
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
