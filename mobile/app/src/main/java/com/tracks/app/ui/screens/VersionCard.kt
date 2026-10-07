// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import com.tracks.app.AppContainer
import com.tracks.app.ui.components.MetricInfo
import com.tracks.app.ui.components.TonalButton
import com.tracks.app.ui.theme.Tokens
import com.tracks.core.api.ReleaseVersion
import com.tracks.core.api.VersionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Which version this app, its server and the newest release are, and whether
 * any of them is behind.
 *
 * Tracks is not in an app store, so nothing else tells someone that an update
 * exists, or that their phone and server have drifted apart — which matters
 * more than either being old, since the two speak one sync protocol. The
 * newest release comes from the server (GET /version), which asks GitHub on
 * the phone's behalf; a phone that only reaches its server over a LAN still
 * hears of updates, and a server with the check turned off is said to be.
 *
 * Updating is a link to the release, not an in-app install: installing the
 * APK is still the user's step (or Obtainium's).
 */
@Composable
fun VersionCard(container: AppContainer, serverVersion: String?, signedIn: Boolean) {
    val app = ReleaseVersion.ofBuild(container.appVersion.first)
    val status by produceState<VersionStatus?>(null, signedIn) {
        value = if (signedIn) {
            withContext(Dispatchers.IO) { runCatching { container.client().versionStatus() }.getOrNull() }
        } else {
            null
        }
    }
    val server = status?.serverVersion ?: serverVersion
    val latest = status?.latest

    SettingsCard(
        "Version",
        MetricInfo(
            "Version",
            "The newest release is checked by your server, every few hours, on GitHub. " +
                "The app and the server should run the same version: they share one sync protocol.",
        ),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s2)) {
            VersionRow(
                "This app",
                app,
                when {
                    ReleaseVersion.isNewer(latest?.version, app) -> Verdict.Behind("${latest!!.version} available")
                    latest != null -> Verdict.Current
                    else -> null
                },
            )
            if (server != null) {
                VersionRow(
                    "Server",
                    server,
                    when {
                        ReleaseVersion.isNewer(latest?.version, server) -> Verdict.Behind("${latest!!.version} available")
                        latest != null -> Verdict.Current
                        else -> null
                    },
                )
            }
            VersionRow(
                "Latest release",
                latest?.version ?: "—",
                note = when {
                    !signedIn -> "Sign in to check"
                    status == null -> null
                    status?.updateCheck == false -> "Not checked: turned off on the server"
                    status?.checkError != null -> "Could not reach GitHub"
                    else -> latest?.publishedAt?.take(10)
                },
            )
            Mismatch(app, server)
            // The server's own release when it is ahead — the APK that matches
            // it, which is what keeps sync working — and otherwise the newest.
            val releases = status?.releasesUrl ?: RELEASES
            val target = when {
                ReleaseVersion.isNewer(server, app) -> "$releases/tag/v$server"
                ReleaseVersion.isNewer(latest?.version, app) -> latest!!.url
                else -> null
            }
            if (target != null) {
                val uriHandler = LocalUriHandler.current
                TonalButton("Get the app update", onClick = { runCatching { uriHandler.openUri(target) } })
            }
        }
    }
}

private const val RELEASES = "https://github.com/exercisetracks/tracks/releases"

private sealed interface Verdict {
    data object Current : Verdict
    data class Behind(val text: String) : Verdict
}

@Composable
private fun VersionRow(label: String, version: String, verdict: Verdict? = null, note: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            note?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(version, style = MaterialTheme.typography.bodyMedium)
            when (verdict) {
                Verdict.Current -> Text(
                    "Up to date",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is Verdict.Behind -> Text(
                    verdict.text,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                null -> Unit
            }
        }
    }
}

/**
 * The drift that matters more than age: one side updated and the other not.
 * Said in terms of what to do, since "versions differ" alone leaves the
 * reader to work out which one to change.
 */
@Composable
private fun Mismatch(app: String, server: String?) {
    val text = when {
        ReleaseVersion.isNewer(server, app) -> "Your server is newer than this app. Install the matching app to keep them in step."
        ReleaseVersion.isNewer(app, server) -> "This app is newer than your server. Update the server to keep them in step."
        else -> return
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
}
