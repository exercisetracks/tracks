// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.health

import com.tracks.app.ui.theme.Tokens
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The card every entered section shares.
 *
 * This file used to hold the entered half of the page outright — injuries,
 * medications, meals, the three figures only the athlete can correct. Each of
 * those has since grown a home of its own: [HealthInjuries.kt],
 * [HealthMedications.kt], and [HealthLogSheet.kt] for everything that is now
 * behind one button. What is left is the shape they are all drawn in.
 */

// ── Shared chrome ────────────────────────────────────────────────────────────

/**
 * A titled card, matching the shape the rest of the app uses.
 *
 * Takes a composable [trailing] rather than a string because these sections put
 * *actions* in the header — "Log injury", "Edit" — where the dashboard's
 * equivalent only ever puts a label.
 */
@Composable
fun HealthSection(
    title: String,
    trailing: @Composable (() -> Unit)? = null,
    info: com.tracks.app.ui.components.MetricInfo? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                // Beside the name rather than at the end of the row: it explains
                // the label, and at the far right it would read as belonging to
                // the reading instead.
                info?.let { com.tracks.app.ui.components.InfoTip(it) }
            }
            trailing?.invoke()
        }
        androidx.compose.material3.Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Tokens.Radius.xl),
            colors = androidx.compose.material3.CardDefaults.cardColors(
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
