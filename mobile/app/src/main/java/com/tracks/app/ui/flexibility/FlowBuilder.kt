// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.flexibility

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.FilterChip
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
import com.tracks.core.api.FlexibilityFlow
import com.tracks.core.api.FlexibilityFlowIn
import com.tracks.core.api.FlowStretch
import com.tracks.core.api.Stretch

/**
 * Write or edit a mobility flow.
 *
 * Deliberately the same sheet, in the same order, as the strength builder next
 * door: name, notes, tags, the two switches, an ordered list you add to and
 * reorder, then save or delete. They are the same shape of thing and there is
 * nothing to gain from them being two different shapes of screen.
 *
 * ## What a stretch's fields mean here
 *
 * A flow says only what it wants to *change*. A stretch left with no duration
 * is held for whatever the library says it should be — see [FlowStretch] — so
 * every field below is optional and an empty one means "as written in the
 * library", not zero. That is why the placeholder text names the library's
 * value rather than showing it as if the flow had chosen it.
 */
internal val FLOW_TAGS = listOf(
    "morning" to "Morning",
    "evening" to "Evening",
    "pre_workout" to "Pre-workout",
    "post_workout" to "Post-workout",
    "recovery" to "Recovery",
    "hips" to "Hips",
    "back" to "Back",
    "shoulders" to "Shoulders",
)

/**
 * The stretches somebody ticked, as a flow's opening sequence.
 *
 * Nothing but names and an order: a flow carries only its deviations, and a
 * stretch straight out of the library has none yet — it is held for whatever
 * the library prescribes until somebody says otherwise.
 */
internal fun flowStretchesFrom(picked: List<String>): List<FlowStretch> =
    picked.mapIndexed { index, name -> FlowStretch(exerciseName = name, orderIndex = index) }

/**
 * One line describing a stretch's place in the flow.
 *
 * A flow carries only its deviations, so anything it does not set is shown as
 * the library's own value with "from the library" said out loud — otherwise a
 * hold the flow never chose looks like one it did, and editing the library
 * later would silently change a flow that appeared to be explicit.
 */
internal fun flowStretchLine(stretch: FlowStretch, fromLibrary: Stretch?): String = buildString {
    val seconds = stretch.durationSeconds ?: fromLibrary?.durationPerSideSec
    val inherited = stretch.durationSeconds == null && seconds != null
    if (seconds != null) {
        append("${seconds}s")
        if (fromLibrary?.eachSide == true) append(" each side")
    } else {
        append("as written")
    }
    stretch.sets?.takeIf { it > 1 }?.let { append(" · × $it") }
    stretch.restSeconds?.takeIf { it > 0 }?.let { append(" · ${it}s rest") }
    if (inherited) append(" · from the library")
}

@Composable
internal fun StretchStepDialog(
    stretch: FlowStretch,
    fromLibrary: Stretch?,
    onSave: (FlowStretch) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(stretch) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stretch.exerciseName ?: "Stretch") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Leave a field empty to use the library's own figure.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OptionalIntField(
                    label = "Hold (s)",
                    placeholder = fromLibrary?.durationPerSideSec,
                    value = draft.durationSeconds,
                ) { draft = draft.copy(durationSeconds = it) }
                OptionalIntField(
                    label = "Sets",
                    placeholder = fromLibrary?.sets,
                    value = draft.sets,
                ) { draft = draft.copy(sets = it) }
                OptionalIntField(label = "Rest (s)", placeholder = null, value = draft.restSeconds) {
                    draft = draft.copy(restSeconds = it)
                }
                OutlinedTextField(
                    value = draft.coachingNote.orEmpty(),
                    onValueChange = {
                        draft = draft.copy(coachingNote = it.takeIf(String::isNotBlank))
                    },
                    label = { Text("Cue (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(draft) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * A number the flow may or may not set.
 *
 * Empty is a real value here — it means "whatever the library says" — so it is
 * never turned into a zero, and the library's own figure is shown as the
 * placeholder rather than filled in.
 */
@Composable
private fun OptionalIntField(
    label: String,
    placeholder: Int?,
    value: Int?,
    onChange: (Int?) -> Unit,
) {
    var text by remember(label) { mutableStateOf(value?.toString().orEmpty()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it.filter(Char::isDigit)
            onChange(text.toIntOrNull())
        },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it.toString()) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

// ── The builder page's view of a flow ───────────────────────────────────────

/** A library stretch as the builder's add panel lists it. */
internal fun Stretch.libraryEntry() = com.tracks.app.ui.builder.LibraryEntry(
    name = name,
    primary = primaryMuscles,
    secondary = secondaryMuscles,
    detail = "${durationPerSideSec ?: 30}s" + if (eachSide) " each side" else "",
)

/** Stretch flows in the shared [com.tracks.app.ui.builder.BuilderPage]. */
internal class FlowKind(
    private val library: Map<String, Stretch>,
) : com.tracks.app.ui.builder.BuilderKind<FlowStretch> {
    override val thing = "flow"
    override val noun = "stretch"
    override val tagChoices = FLOW_TAGS
    override fun nameOf(item: FlowStretch) = item.exerciseName
    override fun isRest(item: FlowStretch) = item.isRest
    override fun group(item: FlowStretch) = item.group
    override fun withGroup(item: FlowStretch, group: com.tracks.core.api.StepGroup?) = item.copy(
        groupUid = group?.uid, groupKind = group?.kind,
        groupRounds = group?.rounds, groupRestSeconds = group?.restSeconds,
    )
    override fun restItem(seconds: Int) = FlowStretch(itemKind = "rest", durationSeconds = seconds)
    override fun restSeconds(item: FlowStretch) = item.durationSeconds ?: 0
    override fun withRestSeconds(item: FlowStretch, seconds: Int) = item.copy(durationSeconds = seconds)
    override fun newItem(entry: com.tracks.app.ui.builder.LibraryEntry) = FlowStretch(exerciseName = entry.name)
    override fun line(item: FlowStretch) = flowStretchLine(item, item.exerciseName?.let(library::get))

    @androidx.compose.runtime.Composable
    override fun EditDialog(item: FlowStretch, onSave: (FlowStretch) -> Unit, onDismiss: () -> Unit) =
        StretchStepDialog(item, item.exerciseName?.let(library::get), onSave, onDismiss)
}

internal fun flowDraft(
    flow: FlexibilityFlow?,
    startFrom: List<FlowStretch> = emptyList(),
    name: String = "",
) = com.tracks.app.ui.builder.BuilderDraft(
    id = flow?.id,
    name = flow?.name ?: name,
    notes = flow?.description.orEmpty(),
    tags = flow?.tags?.toSet().orEmpty(),
    includeInPlan = flow?.includeInPlan ?: true,
    syncToWatch = flow?.syncToWatch ?: true,
    items = (flow?.stretches?.sortedBy { it.orderIndex } ?: startFrom)
        .map { com.tracks.app.ui.builder.Keyed(com.tracks.app.ui.builder.newKey(), it) },
)

/** What gets saved: the rows in structure order, positions renumbered. */
internal fun com.tracks.app.ui.builder.BuilderDraft<FlowStretch>.toFlowIn() = FlexibilityFlowIn(
    name = name.trim(),
    description = notes.trim().ifBlank { null },
    tags = tags.toList(),
    includeInPlan = includeInPlan,
    syncToWatch = syncToWatch,
    stretches = items.mapIndexed { i, k -> k.value.copy(orderIndex = i) },
)
