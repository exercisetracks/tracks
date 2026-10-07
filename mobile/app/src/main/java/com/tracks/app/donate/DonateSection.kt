// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.donate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.tracks.app.ui.components.PrimaryButton
import com.tracks.app.ui.screens.SettingsCard
import com.tracks.app.ui.theme.Tokens

/**
 * A standing, undismissable "support this app" card at the bottom of
 * Settings — unlike [DonateBanner], which is a gentle periodic nudge, this
 * one is just always here for anyone who comes looking for it.
 */
@Composable
fun DonateSection() {
    val context = LocalContext.current
    SettingsCard("Support Tracks") {
        Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s2)) {
            Text(
                "Tracks is free, open source, and ad-free. If it's useful to you, a donation helps keep it going.",
                style = MaterialTheme.typography.bodySmall,
            )
            PrimaryButton("Donate", onClick = { openDonatePage(context) })
        }
    }
}
