// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.tracks.app.ui.components.ButtonRow
import com.tracks.app.ui.components.DangerButton
import com.tracks.app.ui.components.NeutralButton
import com.tracks.app.ui.components.PrimaryButton
import com.tracks.app.ui.components.TonalButton
import com.tracks.core.api.ActivityDetail

/**
 * The activity's notes, and whether it should count at all.
 *
 * Only the notes are edited here. The name is typed into the title itself
 * (see `ActivityDetailScreen`), as on the web, and the sport is no longer
 * correctable from the phone: what the watch recorded is what both apps lay
 * out. Hide and delete stay — this is the only place on the phone that has
 * them — at the bottom, away from Save, and delete asks first: it is the one
 * edit here that cannot be taken back, on this phone or any other.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityNotesSheet(
    detail: ActivityDetail,
    onDismiss: () -> Unit,
    onSave: (notes: String) -> Unit,
    onHide: () -> Unit,
    onDelete: () -> Unit,
) {
    var notes by remember { mutableStateOf(detail.notes.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    // Straight to the keyboard: the pencil was pressed to write.
    LaunchedEffect(Unit) { focus.requestFocus() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Notes", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = notes, onValueChange = { notes = it },
                placeholder = { Text("How it went, the conditions, the kit…") },
                minLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            PrimaryButton("Save", onClick = { onSave(notes) }, modifier = Modifier.fillMaxWidth())
            ButtonRow {
                TonalButton("Hide", onClick = onHide)
                DangerButton("Delete", onClick = { confirmDelete = true })
            }
            Text(
                "Hidden activities leave the list, the dashboard and training load, and keep their data. " +
                    "A delete is final on every device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this activity?") },
            text = { Text("It is removed from this phone and every device it syncs with, and a re-import of the same file will not bring it back.") },
            confirmButton = {
                DangerButton("Delete", onClick = { confirmDelete = false; onDelete() })
            },
            dismissButton = { NeutralButton("Cancel", onClick = { confirmDelete = false }) },
        )
    }
}

/**
 * The first few words of a note, for the app bar — or null when there is no
 * note, so the bar shows only the pencil.
 *
 * Words rather than characters, so the cut never lands mid-word; and the
 * first line only, since a note that opens with a short line ("Felt flat")
 * has said its headline there. The bar's own ellipsis still trims a run of
 * long words to the space it has.
 */
internal fun noteSnippet(notes: String?, words: Int = SNIPPET_WORDS): String? {
    val text = notes?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val firstLine = text.lineSequence().first().trim()
    val all = firstLine.split(Regex("\\s+"))
    val cut = all.take(words).joinToString(" ")
    return if (all.size > words || firstLine.length < text.length) "$cut…" else cut
}

private const val SNIPPET_WORDS = 4
