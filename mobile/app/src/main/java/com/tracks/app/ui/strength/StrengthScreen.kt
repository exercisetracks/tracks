// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.strength

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracks.app.ui.body.MusclePanel
import com.tracks.app.ui.body.suggestedSessionName
import com.tracks.app.ui.components.DangerButton
import com.tracks.app.ui.components.Explain
import com.tracks.app.ui.components.InfoHeading
import com.tracks.app.ui.components.EmptyState
import com.tracks.app.ui.components.NeutralButton
import com.tracks.app.ui.components.PendingBanner
import com.tracks.app.ui.components.PrimaryButton
import com.tracks.app.ui.components.TonalButton
import com.tracks.app.ui.theme.Tokens
import com.tracks.core.api.Exercise
import com.tracks.core.api.StrengthHistoryEntry
import com.tracks.core.api.StrengthProgress
import com.tracks.core.api.UserWorkout
import com.tracks.core.format.weight
import com.tracks.core.spec.muscleLabel
import kotlinx.coroutines.launch

/**
 * The strength library, and the way into a session.
 *
 * Two jobs on one screen, and they are the same job: you find the lift you want
 * and then you do it. Splitting "browse" from "build a workout" would mean
 * finding each exercise twice, so picking is a checkbox on the row you were
 * already reading and the session is a button that appears once something is
 * ticked.
 *
 * The list leads with where the user stands on each lift rather than with the
 * exercise's own description. What a back squat is, they know; what they squatted
 * last time is the thing they came here to look up.
 */
@Composable
fun StrengthScreen(vm: StrengthViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    var detail by remember { mutableStateOf<Exercise?>(null) }
    // Null when shut. `Builder(null)` is a new workout, which is why this is a
    // wrapper rather than a nullable UserWorkout — the two are different states.
    var builder by remember { mutableStateOf<Builder?>(null) }
    var custom by remember { mutableStateOf<CustomDraft?>(null) }
    var musclesOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (state.session != null) {
        SessionRunner(vm = vm, modifier = modifier)
        return
    }

    MusclePanel(
        open = musclesOpen,
        onOpenChange = { musclesOpen = it },
        selected = state.muscles,
        onSelectedChange = vm::setMuscles,
    ) {
    Box(modifier.fillMaxSize()) {
        when {
            state.loading && state.exercises.isEmpty() ->
                Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }

            state.exercises.isEmpty() -> EmptyState(
                title = "No exercises",
                body = state.error ?: "The exercise library is empty.",
                modifier = Modifier.fillMaxSize(),
            )

            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 12.dp,
                    // Room for the session bar, which floats over the list.
                    bottom = if (state.picked.isEmpty()) 12.dp else 88.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { PendingBanner(state.pending) }

                // A session paused (or cut short by the process being
                // reclaimed) is waiting on disk; say so first, because it is
                // almost always why someone came back to this screen.
                if (vm.hasSavedSession) {
                    item {
                        androidx.compose.material3.Card(
                            Modifier.fillMaxWidth(),
                            colors = androidx.compose.material3.CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("A session is in progress", Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium)
                                DangerButton("Discard", onClick = vm::discardSession)
                                PrimaryButton("Resume", onClick = vm::resumeSession)
                            }
                        }
                    }
                }

                item {
                    SavedWorkouts(
                        workouts = state.savedWorkouts,
                        onNew = { builder = Builder(null) },
                        onEdit = { builder = Builder(workout = it) },
                        onStart = vm::startSavedWorkout,
                    )
                }

                item {
                    LibraryFilters(
                        title = "Exercise library",
                        searchLabel = "Search exercises",
                        search = state.search,
                        onSearch = vm::setSearch,
                        muscles = state.muscles,
                        onOpenMuscles = { musclesOpen = true },
                        filter = state.filter,
                        onFilter = vm::setFilter,
                        onCustom = { custom = CustomDraft() },
                        onlyMyEquipment = state.onlyMyEquipment,
                        myEquipment = state.myEquipment,
                        onToggleEquipment = vm::toggleOnlyMyEquipment,
                        onEquipment = vm::setEquipment,
                    )
                }

                if (state.visible.isEmpty()) {
                    item {
                        Text(
                            "Nothing matches those filters.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }

                items(state.visible, key = { it.name }) { exercise ->
                    ExerciseRow(
                        exercise = exercise,
                        standing = state.progress[exercise.name],
                        picked = exercise.name in state.picked,
                        onPick = { vm.togglePicked(exercise.name) },
                        onOpen = {
                            // A custom exercise opens its own form, as on the web.
                            val cid = exercise.customId
                            if (exercise.isCustom && cid != null) custom = CustomDraft(
                                id = cid, name = exercise.name, primary = exercise.primaryMuscles.toSet(),
                                secondary = exercise.secondaryMuscles.toSet(), description = exercise.description ?: "",
                                sets = exercise.defaultSets ?: 3, reps = exercise.defaultReps ?: 10,
                            ) else detail = exercise
                        },
                        onPreference = { vm.setPreference(exercise.name, it) },
                    )
                }
            }
        }

        if (state.picked.isNotEmpty()) {
            SessionBar(
                count = state.picked.size,
                onClear = vm::clearPicked,
                onStart = vm::startSession,
                onSave = {
                    builder = Builder(
                        from = workoutEntriesFrom(state.picked, state.exercises),
                        name = suggestedSessionName(state.muscles),
                    )
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    }

    builder?.let { open ->
        val library = state.exercises.associateBy { it.name }
        com.tracks.app.ui.builder.BuilderPage(
            kind = remember(library) { WorkoutKind(library) },
            initial = remember(open) { workoutDraft(open.workout, open.from, open.name) },
            library = remember(state.exercises) { state.exercises.map { it.libraryEntry() } },
            onSave = { draft ->
                vm.saveWorkout(draft.id, draft.toWorkoutIn())
                // A selection turned into a workout has been spent; leaving it
                // ticked invites starting a session of something just saved.
                if (open.from.isNotEmpty()) vm.clearPicked()
                builder = null
            },
            onDelete = open.workout?.let { w -> { vm.deleteWorkout(w.id); builder = null } },
            onBack = { builder = null },
        )
    }

    custom?.let { draft ->
        CustomSheet(
            stretch = false, initial = draft,
            onSave = { vm.saveCustom(it); custom = null },
            onDelete = draft.id?.let { id -> { vm.deleteCustom(id); custom = null } },
            onDismiss = { custom = null },
        )
    }

    detail?.let { exercise ->
        ExerciseSheet(
            exercise = exercise,
            standing = state.progress[exercise.name],
            loadHistory = { vm.history(exercise.name) },
            onSetOneRepMax = { vm.setOneRepMax(exercise.name, it) },
            onDismiss = { detail = null },
        )
    }
}

/**
 * What the builder is open on.
 *
 * `workout` null means a new one — and a new one may arrive pre-filled from the
 * selection on the screen behind, which is what [from] and [name] carry.
 */
private data class Builder(
    val workout: UserWorkout? = null,
    val from: List<com.tracks.core.api.UserWorkoutExercise> = emptyList(),
    val name: String = "",
)

/**
 * The user's own saved workouts.
 *
 * The browser keeps these on a tab of their own. Here they sit above the
 * library, because that is the order somebody uses them in: run the session you
 * already wrote, or go looking for lifts to build a new one.
 */
@Composable
private fun SavedWorkouts(
    workouts: List<UserWorkout>,
    onNew: () -> Unit,
    onEdit: (UserWorkout) -> Unit,
    onStart: (UserWorkout) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        InfoHeading("My workouts", Explain.MyWorkouts, Modifier.fillMaxWidth()) {
            TonalButton("New", onClick = onNew)
        }

        val hasDevice = com.tracks.app.ui.components.LocalHasDevice.current
        workouts.forEach { workout ->
            Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant)) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                        Text(workout.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            buildString {
                                append("${workout.exercises.size} exercise")
                                if (workout.exercises.size != 1) append("s")
                                if (hasDevice && workout.syncToWatch) append(" · saved to watch")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TonalButton("Edit", onClick = { onEdit(workout) }, small = true)
                    TonalButton("Start", onClick = { onStart(workout) }, small = true)
                }
            }
        }
    }
}

@Composable
private fun ExerciseRow(
    exercise: Exercise,
    standing: StrengthProgress?,
    picked: Boolean,
    onPick: () -> Unit,
    onOpen: () -> Unit,
    onPreference: (String?) -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(Tokens.Radius.xl),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    exercise.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    exercise.primaryMuscles.take(3).joinToString(" · ") { muscleLabel(it) }
                        .ifBlank { "Bodyweight" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                standingLine(standing)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            PrefToggle(exercise.preference, onPreference)
            Checkbox(checked = picked, onCheckedChange = { onPick() })
        }
    }
}

/**
 * The one line worth putting on a list row: what you lifted, and what that
 * implies your max is. Null for a lift with no history — a row of dashes says
 * less than an absent line.
 */
private fun standingLine(standing: StrengthProgress?): String? {
    if (standing == null) return null
    val parts = buildList {
        standing.lastWeightKg?.let { kg ->
            add("Last ${weight(kg)}" + (standing.lastReps?.let { " × $it" } ?: ""))
        }
        standing.estimated1rmKg?.let { add("1RM ${weight(it)}") }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Composable
private fun SessionBar(
    count: Int,
    onClear: () -> Unit,
    onStart: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "$count exercise${if (count == 1) "" else "s"}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            NeutralButton("Clear", onClick = onClear)
            // Two things to do with a selection, not one. Filtering the library
            // by muscle and ticking what you want is exactly how a workout gets
            // written, so it should not have to be done twice — once here to
            // train, and again inside the builder to save.
            TonalButton("Save as workout", onClick = onSave)
            PrimaryButton("Start", onClick = onStart)
        }
    }
}

/**
 * One exercise, in full: how to do it, what has been done, and the max.
 *
 * A sheet rather than a page, so closing it returns to the same scroll position
 * in a library someone is working through.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExerciseSheet(
    exercise: Exercise,
    standing: StrengthProgress?,
    loadHistory: suspend () -> List<StrengthHistoryEntry>,
    onSetOneRepMax: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var history by remember(exercise.name) {
        mutableStateOf<List<StrengthHistoryEntry>?>(null)
    }
    LaunchedEffect(exercise.name) { history = loadHistory() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                exercise.name,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                buildList {
                    if (exercise.isCompound) add("Compound")
                    exercise.movementPattern?.let { add(it.replace('_', ' ')) }
                    if (exercise.equipment.isNotEmpty()) add(exercise.equipment.joinToString(", "))
                }.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Instructions when there are any, the description otherwise. Not
            // both: the two overlap heavily in the library and stacking them
            // reads as the same paragraph written twice.
            val blurb = exercise.instructions?.takeIf { it.isNotBlank() }
                ?: exercise.description?.takeIf { it.isNotBlank() }
            if (blurb != null) Text(blurb, style = MaterialTheme.typography.bodyMedium)

            if (exercise.cues.isNotEmpty()) {
                SheetSection("Cues") {
                    exercise.cues.forEach {
                        Text("• $it", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            OneRepMaxEditor(standing = standing, onSave = onSetOneRepMax)

            val rows = history
            SheetSection("Recent sets") {
                when {
                    rows == null -> Text(
                        "Loading…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    rows.isEmpty() -> Text(
                        "Nothing recorded yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> rows.take(HISTORY_ROWS).forEach { entry ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                entry.activityDate,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                buildString {
                                    entry.weightKg?.let { append(weight(it)) }
                                    entry.repetitions?.let {
                                        if (isNotEmpty()) append(" × ")
                                        append(it)
                                    }
                                }.ifBlank { "—" },
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The max, entered rather than inferred.
 *
 * The server keeps a running estimate from logged sets, which is right for a
 * lift with history and useless for one the user has tested in a different gym.
 * Both numbers live in the same field: what is shown is whatever the server
 * currently believes, and saving replaces it.
 */
@Composable
private fun OneRepMaxEditor(standing: StrengthProgress?, onSave: (Double) -> Unit) {
    var text by remember(standing?.estimated1rmKg) {
        mutableStateOf(standing?.estimated1rmKg?.let { formatKg(it) } ?: "")
    }
    val parsed = text.trim().toDoubleOrNull()

    SheetSection("One-rep max") {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("kg") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                ),
                modifier = Modifier.weight(1f),
            )
            PrimaryButton(
                "Save",
                // A zero or a blank field is not a max, and sending one would
                // wipe a real number the server had good reason to hold.
                onClick = { parsed?.let(onSave) },
                enabled = parsed != null && parsed > 0,
            )
        }
        standing?.sessionsCompleted?.takeIf { it > 0 }?.let {
            Text(
                "$it session${if (it == 1) "" else "s"} logged · ${standing.progressionStage.replace('_', ' ')}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun SheetSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

/** Trailing `.0` dropped: "100 kg" is what a plate reads, not "100.0 kg". */
internal fun formatKg(kg: Double): String =
    if (kg % 1.0 == 0.0) kg.toInt().toString() else ((kg * 10).toInt() / 10.0).toString()

private const val HISTORY_ROWS = 12
