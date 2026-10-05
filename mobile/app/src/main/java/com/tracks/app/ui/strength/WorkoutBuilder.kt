// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.strength

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.tracks.app.ui.components.OptionGrid
import com.tracks.core.api.Exercise
import com.tracks.core.api.UserWorkout
import com.tracks.core.api.UserWorkoutExercise
import com.tracks.core.api.UserWorkoutIn

/**
 * Tags a workout can carry. The server refuses anything outside this list, so
 * it is a fixed vocabulary rather than free text — mirrors `VALID_TAGS`.
 */
internal val WORKOUT_TAGS = listOf(
    "upper_body" to "Upper body",
    "lower_body" to "Lower body",
    "core" to "Core",
    "push" to "Push",
    "pull" to "Pull",
    "aerobic" to "Aerobic",
    "full_body" to "Full body",
    "mobility" to "Mobility",
)

/**
 * How a working weight is decided.
 *
 * The three are not interchangeable and the number means something different
 * under each — see [UserWorkoutExercise.weightValue] — so the field's label
 * changes with the method rather than staying a generic "value".
 */
private val WEIGHT_METHODS = listOf(
    "percentage_e1rm" to "% of e1RM",
    "rpe" to "RPE",
    "fixed" to "Fixed kg",
)

/**
 * A library exercise as a line in a workout, with the library's own defaults.
 *
 * One definition, used both when a single exercise is picked inside the sheet
 * and when a whole muscle-filtered selection is turned into a workout from the
 * screen behind it — those two must not drift into prescribing differently.
 */
internal fun workoutEntry(exercise: Exercise, orderIndex: Int) = UserWorkoutExercise(
    exerciseName = exercise.name,
    exerciseSource = if (exercise.isCustom) "custom" else "library",
    targetSets = exercise.defaultSets ?: 3,
    targetReps = exercise.defaultReps ?: 8,
    orderIndex = orderIndex,
)

/**
 * The exercises somebody ticked, as a workout's opening line-up.
 *
 * Keeps the order they were ticked in, which is the order they are shown and
 * the order they will be done. A name the library no longer has is dropped
 * rather than carried as a line nothing can resolve.
 */
internal fun workoutEntriesFrom(
    picked: List<String>,
    library: List<Exercise>,
): List<UserWorkoutExercise> {
    val byName = library.associateBy(Exercise::name)
    return picked.mapNotNull(byName::get).mapIndexed(::indexedEntry)
}

private fun indexedEntry(index: Int, exercise: Exercise) = workoutEntry(exercise, index)

/**
 * The prescription in one line.
 *
 * The weight is spelled out with its method because the same number means a
 * fraction, an RPE, or kilograms depending on it — "0.75" alone is unreadable.
 */
internal fun prescriptionLine(exercise: UserWorkoutExercise): String = buildString {
    append("${exercise.targetSets} × ${exercise.targetReps}")
    exercise.weightValue?.let { value ->
        when (exercise.weightMethod) {
            "percentage_e1rm" -> append(" · ${(value * 100).toInt()}% e1RM")
            "rpe" -> append(" · RPE ${formatKg(value)}")
            else -> append(" · ${formatKg(value)} kg")
        }
    }
    append(" · RIR ${exercise.rirTarget}")
    append(" · ${exercise.restSeconds}s rest")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExercisePrescriptionDialog(
    exercise: UserWorkoutExercise,
    onSave: (UserWorkoutExercise) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(exercise) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(exercise.exerciseName ?: "Exercise") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                IntField("Sets", draft.targetSets) { draft = draft.copy(targetSets = it ?: 3) }
                // One number, not a range: the watch's step counts one.
                IntField("Reps", draft.targetReps) { draft = draft.copy(targetReps = it ?: 8) }
                IntField("Reps in reserve", draft.rirTarget) {
                    draft = draft.copy(rirTarget = it ?: 2)
                }
                IntField("Rest (s)", draft.restSeconds) {
                    draft = draft.copy(restSeconds = it ?: 90)
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Weight from",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OptionGrid(WEIGHT_METHODS, isSelected = { it == draft.weightMethod }, onPick = { draft = draft.copy(weightMethod = it) })
                }

                // The label follows the method, because the number means a
                // different thing under each and a generic one invites a 75
                // where 0.75 was meant.
                DecimalField(
                    label = when (draft.weightMethod) {
                        "percentage_e1rm" -> "Fraction of e1RM (0.75)"
                        "rpe" -> "Target RPE"
                        else -> "Weight (kg)"
                    },
                    value = draft.weightValue,
                ) { draft = draft.copy(weightValue = it) }
            }
        },
        confirmButton = { com.tracks.app.ui.components.PrimaryButton("Done", onClick = { onSave(draft) }) },
        dismissButton = { com.tracks.app.ui.components.NeutralButton("Cancel", onClick = onDismiss) },
    )
}

@Composable
private fun IntField(label: String, value: Int, onChange: (Int?) -> Unit) {
    var text by remember(label) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it.filter(Char::isDigit)
            onChange(text.toIntOrNull())
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DecimalField(label: String, value: Double?, onChange: (Double?) -> Unit) {
    var text by remember(label) { mutableStateOf(value?.let(::formatKg).orEmpty()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it.filter { c -> c.isDigit() || c == '.' }
            onChange(text.toDoubleOrNull())
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

// ── The builder page's view of a workout ────────────────────────────────────

/** A library exercise as the builder's add panel lists it. */
internal fun Exercise.libraryEntry() = com.tracks.app.ui.builder.LibraryEntry(
    name = name,
    primary = primaryMuscles,
    secondary = secondaryMuscles,
    detail = "${defaultSets ?: 3} × ${defaultReps ?: 8}",
)

/** Strength workouts in the shared [com.tracks.app.ui.builder.BuilderPage]. */
internal class WorkoutKind(
    private val library: Map<String, Exercise>,
) : com.tracks.app.ui.builder.BuilderKind<UserWorkoutExercise> {
    override val thing = "workout"
    override val noun = "exercise"
    override val tagChoices = WORKOUT_TAGS
    override fun nameOf(item: UserWorkoutExercise) = item.exerciseName
    override fun isRest(item: UserWorkoutExercise) = item.isRest
    override fun group(item: UserWorkoutExercise) = item.group
    override fun withGroup(item: UserWorkoutExercise, group: com.tracks.core.api.StepGroup?) = item.copy(
        groupUid = group?.uid, groupKind = group?.kind,
        groupRounds = group?.rounds, groupRestSeconds = group?.restSeconds,
    )
    override fun restItem(seconds: Int) = UserWorkoutExercise(itemKind = "rest", restSeconds = seconds)
    override fun restSeconds(item: UserWorkoutExercise) = item.restSeconds
    override fun withRestSeconds(item: UserWorkoutExercise, seconds: Int) = item.copy(restSeconds = seconds)
    override fun newItem(entry: com.tracks.app.ui.builder.LibraryEntry) =
        library[entry.name]?.let { workoutEntry(it, 0) } ?: UserWorkoutExercise(exerciseName = entry.name)

    // Inside a group the group's rounds are the sets, so a member shows its
    // reps and load only — a "3 ×" there would be a number nothing uses.
    // Nor does a member rest between sets — the group's rest does that.
    override fun line(item: UserWorkoutExercise) =
        if (item.group != null) prescriptionLine(item).substringAfter("× ").substringBeforeLast(" · ")
        else prescriptionLine(item)

    @androidx.compose.runtime.Composable
    override fun EditDialog(item: UserWorkoutExercise, onSave: (UserWorkoutExercise) -> Unit, onDismiss: () -> Unit) =
        ExercisePrescriptionDialog(item, onSave, onDismiss)
}

internal fun workoutDraft(
    workout: UserWorkout?,
    startFrom: List<UserWorkoutExercise> = emptyList(),
    name: String = "",
) = com.tracks.app.ui.builder.BuilderDraft(
    id = workout?.id,
    name = workout?.name ?: name,
    notes = workout?.description.orEmpty(),
    tags = workout?.tags?.toSet().orEmpty(),
    includeInPlan = workout?.includeInPlan ?: true,
    syncToWatch = workout?.syncToWatch ?: false,
    items = (workout?.exercises?.sortedBy { it.orderIndex } ?: startFrom)
        .map { com.tracks.app.ui.builder.Keyed(com.tracks.app.ui.builder.newKey(), it) },
)

/** What gets saved: the rows in structure order, positions renumbered. */
internal fun com.tracks.app.ui.builder.BuilderDraft<UserWorkoutExercise>.toWorkoutIn() = UserWorkoutIn(
    name = name.trim(),
    description = notes.trim().ifBlank { null },
    tags = tags.toList(),
    includeInPlan = includeInPlan,
    syncToWatch = syncToWatch,
    exercises = items.mapIndexed { i, k -> k.value.copy(orderIndex = i) },
)
