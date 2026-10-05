// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.recap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tracks.app.ui.components.NeutralButton
import com.tracks.app.ui.components.PrimaryButton
import com.tracks.core.local.AnimationAsk
import com.tracks.core.local.AnimationRecap
import com.tracks.core.local.AnimationRecaps
import com.tracks.core.local.LocalSources
import kotlinx.coroutines.launch

/**
 * Asks, once, about the animations in a workout just done — see
 * [AnimationRecaps] for when and why. Shown by the shell when the app opens.
 * "Yes" and "No" record a confirmation for this watch model, anything left
 * unanswered records nothing, and Done or Skip closes the workout's recap so
 * it is never asked again.
 */
@Composable
fun AnimationRecapHost(sources: LocalSources, today: () -> String, nowIso: () -> String) {
    var queue by remember { mutableStateOf<List<AnimationRecap>>(emptyList()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { queue = runCatching { AnimationRecaps.load(sources, today()) }.getOrDefault(emptyList()) }

    val recap = queue.firstOrNull() ?: return
    val answers = remember(recap.workoutUid) { mutableStateMapOf<AnimationAsk, Boolean>() }

    fun finish(record: Boolean) {
        val given = if (record) answers.toMap() else emptyMap()
        scope.launch {
            runCatching { AnimationRecaps.answer(sources, recap, given, nowIso()) }
            queue = queue.drop(1)
        }
    }

    AnimationRecapDialog(recap, answers, onDone = { finish(true) }, onSkip = { finish(false) })
}

@Composable
internal fun AnimationRecapDialog(
    recap: AnimationRecap,
    answers: MutableMap<AnimationAsk, Boolean>,
    onDone: () -> Unit,
    onSkip: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Did these animate on your watch?") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "${recap.title} — your answers help pick exercises your watch can show.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                recap.asks.forEach { ask ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(ask.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        FilterChip(
                            selected = answers[ask] == true,
                            onClick = { if (answers[ask] == true) answers.remove(ask) else answers[ask] = true },
                            label = { Text("Yes") },
                        )
                        FilterChip(
                            selected = answers[ask] == false,
                            onClick = { if (answers[ask] == false) answers.remove(ask) else answers[ask] = false },
                            label = { Text("No") },
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                Text(
                    "Leave any you're not sure about.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { PrimaryButton("Done", onClick = onDone) },
        dismissButton = { NeutralButton("Skip", onClick = onSkip) },
    )
}
