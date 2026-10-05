// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tracks.app.ui.components.ButtonRow
import com.tracks.app.ui.components.DangerButton
import com.tracks.app.ui.components.NeutralButton
import com.tracks.app.ui.components.OptionGrid
import com.tracks.app.ui.components.PrimaryButton
import com.tracks.app.ui.components.TonalButton
import com.tracks.core.api.WorkoutStep

/**
 * Write or edit one workout.
 *
 * The desktop cannot do this — its calendar shows a generated plan and lets you
 * tick a session off — so this is not parity but the thing the phone needed to
 * be more than a viewer: a session composed in a car park, on a phone with no
 * signal, that reaches the watch anyway.
 *
 * ## Why the type comes before the steps
 *
 * Because it decides what a step *is*. A `strength` workout is built from
 * exercises and an endurance one from timed blocks, and the FIT encoder routes
 * on exactly that value — so offering both would let somebody write steps that
 * are silently dropped when the watch file is built. Changing the type
 * therefore changes which steps can be added, and says so.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun WorkoutEditorSheet(
    initial: WorkoutDraft,
    onSave: (WorkoutDraft) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember { mutableStateOf(initial) }
    var editingStep by remember { mutableStateOf<Int?>(null) }
    var addingStep by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                if (draft.isNew) "New workout" else "Edit workout",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            OutlinedTextField(
                value = draft.title,
                onValueChange = { draft = draft.copy(title = it) },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            ChipRow(
                label = "Sport",
                options = SPORT_OPTIONS,
                selected = draft.sport,
                onSelect = { draft = draft.copy(sport = it) },
            )

            ChipRow(
                label = "Type",
                options = WORKOUT_TYPE_OPTIONS,
                selected = draft.workoutType,
                onSelect = { picked ->
                    // Steps that no longer belong are dropped rather than kept
                    // invisibly: a mobility hold left on a workout retyped to
                    // strength would not be shown, would not be editable, and
                    // would still be written into the watch file.
                    val family = draft.copy(workoutType = picked).stepFamily
                    val kept = draft.steps.filter { StepKind.of(it)?.family == family }
                    draft = draft.copy(workoutType = picked, steps = kept)
                },
            )

            OutlinedTextField(
                value = draft.durationMinutes,
                onValueChange = { draft = draft.copy(durationMinutes = it.filter(Char::isDigit)) },
                label = { Text("Planned minutes (optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = draft.description,
                onValueChange = { draft = draft.copy(description = it) },
                label = { Text("Notes (optional)") },
                modifier = Modifier.fillMaxWidth(),
            )

            if (draft.stepFamily != StepFamily.NONE) {
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Structure",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    TonalButton("Add step", onClick = { addingStep = true }, icon = Icons.Default.Add, small = true)
                }

                if (draft.steps.isEmpty()) {
                    Text(
                        if (com.tracks.app.ui.components.LocalHasDevice.current) {
                            "No structure yet. The watch will show an open session — " +
                                "still runnable, just untimed."
                        } else {
                            "No structure yet — still runnable, just untimed."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                draft.steps.forEachIndexed { index, step ->
                    StepCard(
                        step = step,
                        canMoveUp = index > 0,
                        canMoveDown = index < draft.steps.lastIndex,
                        onEdit = { editingStep = index },
                        onMove = { delta ->
                            val moved = draft.steps.toMutableList()
                            val target = index + delta
                            moved.add(target, moved.removeAt(index))
                            draft = draft.copy(steps = moved)
                        },
                        onRemove = {
                            draft = draft.copy(
                                steps = draft.steps.filterIndexed { i, _ -> i != index },
                            )
                        },
                    )
                }
            }

            HorizontalDivider()

            ButtonRow {
                if (onDelete != null) {
                    DangerButton("Delete", onClick = { confirmingDelete = true })
                }
                NeutralButton("Cancel", onClick = onDismiss)
                PrimaryButton("Save", onClick = { onSave(draft) }, enabled = draft.canSave)
            }
        }
    }

    if (addingStep) {
        StepKindPicker(
            family = draft.stepFamily,
            onPick = { kind ->
                draft = draft.copy(steps = draft.steps + newStep(kind))
                addingStep = false
                editingStep = draft.steps.lastIndex
            },
            onDismiss = { addingStep = false },
        )
    }

    editingStep?.let { index ->
        draft.steps.getOrNull(index)?.let { step ->
            StepEditorDialog(
                step = step,
                sport = draft.sport,
                onSave = { edited ->
                    draft = draft.copy(
                        steps = draft.steps.mapIndexed { i, s -> if (i == index) edited else s },
                    )
                    editingStep = null
                },
                onDismiss = { editingStep = null },
            )
        }
    }

    if (confirmingDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete this workout?") },
            text = {
                Text(
                    if (com.tracks.app.ui.components.LocalHasDevice.current) "It will be removed from the calendar and from the watch on the next sync."
                    else "It will be removed from the calendar.",
                )
            },
            confirmButton = {
                DangerButton("Delete", onClick = { confirmingDelete = false; onDelete() })
            },
            dismissButton = {
                NeutralButton("Keep", onClick = { confirmingDelete = false })
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OptionGrid(options, isSelected = { it == selected }, onPick = onSelect)
    }
}

@Composable
private fun StepCard(
    step: WorkoutStep,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEdit: () -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    stepLabel(step),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                stepDetail(step).takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move earlier")
            }
            IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move later")
            }
            TonalButton("Edit", onClick = onEdit)
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove step",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepKindPicker(
    family: StepFamily,
    onPick: (StepKind) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a step") },
        text = {
            ButtonRow {
                StepKind.forFamily(family).forEach { kind ->
                    NeutralButton(kind.label, onClick = { onPick(kind) })
                }
            }
        },
        confirmButton = { NeutralButton("Cancel", onClick = onDismiss) },
    )
}

/**
 * One step's own fields.
 *
 * Which fields appear is decided by the step's type, because the encoder reads
 * a different set for each — an interval set has reps and a rest, a hold has a
 * duration and a side. Showing all of them would offer numbers that go nowhere.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepEditorDialog(
    step: WorkoutStep,
    sport: String,
    onSave: (WorkoutStep) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(step) }
    // Running steers by pace, everything else by effort. Same split the FIT
    // encoder makes when it resolves a target.
    val byPace = sport == "running"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(StepKind.of(step)?.label ?: "Step") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (step.type) {
                    "warmup", "cooldown", "run" -> {
                        NumberField("Minutes", draft.durationMin) {
                            draft = draft.copy(durationMin = it)
                        }
                        ZoneChips(byPace, draft) { draft = it }
                    }

                    "interval_set" -> {
                        NumberField("Repeats", draft.reps?.toDouble()) {
                            draft = draft.copy(reps = it?.toInt())
                        }
                        // One measure or the other, chosen explicitly rather
                        // than inferred from which field was typed in last. The
                        // encoder prefers distance when both are set, so two
                        // live fields would let somebody type a duration, see
                        // nothing change, and have no way to tell why.
                        var byDistance by remember { mutableStateOf(draft.durationSecEach == null) }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = byDistance,
                                onClick = {
                                    byDistance = true
                                    draft = draft.copy(durationSecEach = null)
                                },
                                label = { Text("By distance") },
                            )
                            FilterChip(
                                selected = !byDistance,
                                onClick = {
                                    byDistance = false
                                    draft = draft.copy(distanceM = null)
                                },
                                label = { Text("By time") },
                            )
                        }
                        if (byDistance) {
                            NumberField("Distance (m)", draft.distanceM) {
                                draft = draft.copy(distanceM = it)
                            }
                        } else {
                            NumberField("Seconds each", draft.durationSecEach) {
                                draft = draft.copy(durationSecEach = it)
                            }
                        }
                        NumberField("Rest (s)", draft.restSec?.toDouble()) {
                            draft = draft.copy(restSec = it?.toInt())
                        }
                        ZoneChips(byPace, draft) { draft = it }
                    }

                    "effort_set" -> {
                        NumberField("Repeats", draft.reps?.toDouble()) {
                            draft = draft.copy(reps = it?.toInt())
                        }
                        NumberField("Minutes each", draft.durationMinEach) {
                            draft = draft.copy(durationMinEach = it)
                        }
                        NumberField("Rest (min)", draft.restMin) {
                            draft = draft.copy(restMin = it)
                        }
                        ZoneChips(byPace = false, step = draft) { draft = it }
                    }

                    "fartlek" -> {
                        NumberField("Hard (min)", draft.hardMin) {
                            draft = draft.copy(hardMin = it)
                        }
                        NumberField("Easy (min)", draft.easyMin) {
                            draft = draft.copy(easyMin = it)
                        }
                        NumberField("Rounds", draft.reps?.toDouble()) {
                            draft = draft.copy(reps = it?.toInt())
                        }
                        ZoneChips(byPace = true, step = draft) { draft = it }
                    }

                    "strength_exercise" -> {
                        OutlinedTextField(
                            value = draft.name.orEmpty(),
                            onValueChange = { draft = draft.copy(name = it) },
                            label = { Text("Exercise") },
                            singleLine = true,
                        )
                        NumberField("Sets", draft.sets?.toDouble()) {
                            draft = draft.copy(sets = it?.toInt())
                        }
                        NumberField("Reps", draft.reps?.toDouble()) {
                            draft = draft.copy(reps = it?.toInt())
                        }
                        NumberField("Weight (kg)", draft.weightKg) {
                            draft = draft.copy(weightKg = it)
                        }
                        NumberField("Rest (s)", draft.restSeconds?.toDouble()) {
                            draft = draft.copy(restSeconds = it?.toInt())
                        }
                    }

                    "mobility_exercise" -> {
                        OutlinedTextField(
                            value = draft.name.orEmpty(),
                            onValueChange = { draft = draft.copy(name = it) },
                            label = { Text("Stretch") },
                            singleLine = true,
                        )
                        NumberField("Seconds", draft.durationSeconds?.toDouble()) {
                            draft = draft.copy(durationSeconds = it?.toInt())
                        }
                        NumberField("Sets", draft.sets?.toDouble()) {
                            draft = draft.copy(sets = it?.toInt())
                        }
                        FilterChip(
                            selected = draft.eachSide,
                            onClick = { draft = draft.copy(eachSide = !draft.eachSide) },
                            label = { Text("Each side") },
                        )
                    }
                }
            }
        },
        confirmButton = { PrimaryButton("Done", onClick = { onSave(draft) }) },
        dismissButton = { NeutralButton("Cancel", onClick = onDismiss) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZoneChips(
    byPace: Boolean,
    step: WorkoutStep,
    onChange: (WorkoutStep) -> Unit,
) {
    val options = if (byPace) PACE_OPTIONS else INTENSITY_OPTIONS
    val selected = if (byPace) step.pace else step.intensity
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            if (byPace) "Pace" else "Effort",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OptionGrid(
            options,
            isSelected = { it == selected },
            onPick = { value -> onChange(if (byPace) step.copy(pace = value) else step.copy(intensity = value)) },
        )
    }
}

/**
 * A number that may be absent.
 *
 * Kept as text while being edited so a half-typed figure stays half-typed —
 * parsing every keystroke turns "1" into a real value the moment somebody means
 * to type "15", and clearing the field has to mean "not set" rather than zero.
 */
@Composable
private fun NumberField(
    label: String,
    value: Double?,
    onChange: (Double?) -> Unit,
) {
    var text by remember(label) { mutableStateOf(value?.let(::trimZero).orEmpty()) }
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed.filter { it.isDigit() || it == '.' }
            onChange(text.toDoubleOrNull())
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}
