// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import com.tracks.app.AppContainer
import com.tracks.app.ui.components.PasswordField
import com.tracks.app.ui.theme.Tokens
import com.tracks.core.api.UserSettings
import kotlinx.coroutines.launch

/**
 * AI coaching, the web's row of the same name in Privacy & connectivity.
 *
 * Unlike the rows beside it this is not a synced setting: the coach runs on
 * the server and its key is a server secret, so `ai_*` stays out of the sync
 * contract (spec/sync.yaml). The phone reads and writes it over the API while
 * linked, and without a server says so instead of offering a switch that
 * could not do anything.
 *
 * Every choice is on the page — provider buttons, then the model and its key
 * or endpoint — rather than behind an expanding panel, the same as the rest
 * of Settings.
 */
@Composable
fun AiCoachingRow(container: AppContainer, linked: Boolean) {
    if (!linked) {
        ConnectivityRow("AI coaching", "Needs a Tracks server — the coach runs there", Tone.Off, checked = null)
        return
    }
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf<UserSettings?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { container.client().userSettings() }
            .onSuccess { settings = it }
            .onFailure { error = "Could not reach your server." }
    }
    fun save(vararg fields: Pair<String, String?>) {
        scope.launch {
            runCatching {
                container.client().patchUserSettings(fields.toMap())
            }
                .onSuccess { settings = it; error = null }
                .onFailure { error = "Not saved: ${it.message ?: "the server did not answer"}." }
        }
    }

    val s = settings
    val provider = s?.aiProvider
    val (detail, tone) = when {
        s == null -> (error ?: "Loading…") to Tone.Off
        provider == "ollama" -> "Runs on your own Ollama — your data stays home" to Tone.Ok
        provider != null -> "Sends training summaries to ${PROVIDERS[provider] ?: provider}" to Tone.Warn
        else -> "Off" to Tone.Off
    }
    ConnectivityRow("AI coaching", detail, tone, checked = null)
    if (s == null) return

    Column(Modifier.padding(start = Tokens.Space.s3), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s2)) {
        SegmentedChoice(
            options = listOf("off" to "Off", "anthropic" to "Claude", "openai" to "OpenAI", "ollama" to "Ollama"),
            selected = provider ?: "off",
            onSelect = { v ->
                if (v == "off") save("ai_provider" to null, "ai_model" to null, "ai_endpoint" to null)
                else save("ai_provider" to v)
            },
        )
        if (provider != null) {
            TextSettingField("Model", s.aiModel) { save("ai_model" to it) }
            if (provider == "ollama") {
                TextSettingField("Endpoint URL", s.aiEndpoint) { save("ai_endpoint" to it) }
            } else {
                ApiKeyField(configured = s.aiConfigured) { save("ai_api_key" to it) }
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

private val PROVIDERS = mapOf("anthropic" to "Anthropic", "openai" to "OpenAI")

/**
 * The provider's API key, write-only.
 *
 * Safe to take here because it goes nowhere new: it is sent once to the
 * user's own server, over the same connection their password uses (see
 * network_security_config.xml for when that may be cleartext), where it is
 * stored encrypted. The phone never stores it — the field empties once sent —
 * and the server never sends it back, only that one is stored.
 */
@Composable
private fun ApiKeyField(configured: Boolean, onCommit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    fun commit() {
        val key = text.trim()
        if (key.isNotEmpty()) onCommit(key)
        text = ""
    }
    PasswordField(
        value = text,
        onValueChange = { text = it },
        label = "API key",
        placeholder = if (configured) "Stored — paste to replace" else "Paste your API key",
        imeAction = ImeAction.Done,
        keyboardActions = KeyboardActions(onDone = { commit(); focus.clearFocus() }),
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) commit() },
    )
}
