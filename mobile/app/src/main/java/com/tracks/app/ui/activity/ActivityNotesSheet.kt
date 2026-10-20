// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tracks.app.ui.components.ButtonRow
import com.tracks.app.ui.components.DangerButton
import com.tracks.app.ui.components.NeutralButton
import com.tracks.app.ui.components.OptionGrid
import com.tracks.app.ui.components.PrimaryButton
import com.tracks.app.ui.components.TonalButton
import com.tracks.core.api.ActivityDetail

/**
 * Everything a person can say about an activity: its name, notes, what sport it
 * really was, and whether it should count at all.
 *
 * One sheet rather than a rename dialog plus a menu, because these are the
 * same act — correcting what the watch recorded — and a sheet shows the
 * current state of all of them at once. Hide and delete sit at the bottom, away
 * from Save, and delete asks first: it is the one edit here that cannot be
 * taken back, on this phone or any other.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ActivityEditSheet(
    detail: ActivityDetail,
    onDismiss: () -> Unit,
    onSave: (name: String, notes: String, choice: SportChoice?) -> Unit,
    onHide: () -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(detail.name.orEmpty()) }
    var notes by remember { mutableStateOf(detail.notes.orEmpty()) }
    var choice by remember {
        mutableStateOf(SPORT_CHOICES.firstOrNull { it.sport == detail.sport && it.subSport == detail.subSport })
    }
    var confirmDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Edit activity", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = notes, onValueChange = { notes = it },
                label = { Text("Notes") }, minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Sport", style = MaterialTheme.typography.labelLarge)
            OptionGrid(SPORT_CHOICES.map { it to it.label }, isSelected = { it == choice }, onPick = { choice = it })
            PrimaryButton("Save", onClick = { onSave(name, notes, choice) }, modifier = Modifier.fillMaxWidth(), enabled = name.isNotBlank())
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
