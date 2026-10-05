// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tracks.app.ui.components.NeutralButton

/**
 * The little "?" that says what a number means.
 *
 * A port of the web app's `InfoTooltip`, with the one change a phone forces: it
 * opens on a *tap* and closes on a tap, rather than on hover. There is no hover
 * on a touchscreen, and the usual workaround — a long-press tooltip — is a
 * gesture nobody discovers and screen readers announce badly.
 *
 * A dialog rather than a popover anchored to the button. Popovers on a phone
 * either overflow the screen or get clipped by the card they are inside, and
 * these are two or three sentences: at that length a centred sheet is easier to
 * read than a bubble squeezed against an edge.
 *
 * Small and quiet on purpose. It sits beside a section heading, and a help
 * affordance that draws the eye more than the number it explains has failed at
 * its job.
 */
@Composable
fun InfoTip(info: MetricInfo, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }

    Surface(
        onClick = { open = true },
        modifier = modifier
            .size(18.dp)
            .semantics { contentDescription = "What is ${info.title}?" },
        shape = CircleShape,
        // A tint, not surfaceVariant: most of these sit on surfaceVariant
        // cards, where a solid circle of the same colour disappears.
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            "?",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp),
        )
    }

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(info.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    info.body.forEach { paragraph ->
                        Text(
                            paragraph,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                NeutralButton("Got it", onClick = { open = false })
            },
        )
    }
}

/** A heading with its explanation beside it — the pairing this is always used in. */
@Composable
fun InfoHeading(
    text: String,
    info: MetricInfo,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    androidx.compose.foundation.layout.Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        InfoTip(info)
        if (trailing != null) {
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}
