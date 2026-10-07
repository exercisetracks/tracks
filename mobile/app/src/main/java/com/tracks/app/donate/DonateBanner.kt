// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.donate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.tracks.app.ui.components.ButtonRow
import com.tracks.app.ui.components.NeutralButton
import com.tracks.app.ui.components.PrimaryButton
import com.tracks.app.ui.theme.Tokens

/**
 * Dashboard's gentle, occasional nudge to support the project — see
 * [DonatePreference] for the week-then-monthly schedule it follows. The
 * mirror of this on the web is `DonateBanner.jsx`; see `DonateSection` for
 * the standing, undismissable version of this in Settings.
 */
@Composable
fun DonateBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var hidden by rememberSaveable { mutableStateOf(false) }
    if (hidden || !DonatePreference.isDue(context)) return

    Surface(
        shape = RoundedCornerShape(Tokens.Card.radius),
        color = MaterialTheme.colorScheme.secondaryContainer,
        // The container's own "on" colour is accent-tinted (see Accent.onContainer)
        // and reads muddy against the equally-tinted background; plain onSurface
        // is just the normal body-text colour, legible on the light accent wash.
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(Tokens.Space.s5),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.s2),
        ) {
            Text("Enjoying Tracks?", style = MaterialTheme.typography.titleMedium)
            Text(
                "It's free, open source, and ad-free. If it's useful to you, a donation helps keep it going.",
                style = MaterialTheme.typography.bodyMedium,
            )
            ButtonRow {
                PrimaryButton("Donate", onClick = { openDonatePage(context) })
                NeutralButton("Not now", onClick = {
                    DonatePreference.dismiss(context)
                    hidden = true
                })
            }
        }
    }
}
