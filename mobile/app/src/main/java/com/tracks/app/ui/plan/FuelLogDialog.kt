// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.tracks.app.ui.components.NeutralButton
import com.tracks.app.ui.components.OptionGrid
import com.tracks.app.ui.components.PrimaryButton
import com.tracks.core.api.PlannedWorkout

/** GI comfort 1 (bad) .. 5 (no issues), as gut_training_log stores it. */
internal val COMFORT = listOf(1 to "Bad", 2 to "Poor", 3 to "OK", 4 to "Good", 5 to "Perfect")

/**
 * How a fuelled long session went: carbs actually taken and how the gut coped.
 * Written as a gut_training_log; the ramp (com.tracks.core.fuel.FuelPlan)
 * steps back after a bad day and on after a good one that hit the target.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FuelLogDialog(
    workout: PlannedWorkout,
    target: Int?,
    onDismiss: () -> Unit,
    onSave: (Double, Int, String?) -> Unit,
) {
    val suggested = target?.let { t -> workout.durationMinutes?.let { (t * it / 60.0).toInt() } }
    var carbs by remember { mutableStateOf(suggested?.toString() ?: "") }
    var comfort by remember { mutableIntStateOf(4) }
    var notes by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How did fuelling go?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = carbs, onValueChange = { v -> carbs = v.filter { it.isDigit() || it == '.' } },
                    label = { Text("Carbs taken (g)") }, singleLine = true,
                )
                Text("Stomach", style = MaterialTheme.typography.labelMedium)
                OptionGrid(COMFORT, isSelected = { it == comfort }, onPick = { comfort = it })
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes") })
            }
        },
        confirmButton = {
            PrimaryButton("Save", onClick = { onSave(carbs.toDoubleOrNull() ?: 0.0, comfort, notes) })
        },
        dismissButton = { NeutralButton("Cancel", onClick = onDismiss) },
    )
}
