// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.flexibility

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracks.app.ui.body.MusclePanel
import com.tracks.app.ui.body.suggestedSessionName
import com.tracks.app.ui.components.EmptyState
import com.tracks.app.ui.components.Explain
import com.tracks.app.ui.components.InfoHeading
import com.tracks.app.ui.components.NeutralButton
import com.tracks.app.ui.components.PendingBanner
import com.tracks.app.ui.components.PrimaryButton
import com.tracks.app.ui.components.TonalButton
import com.tracks.app.ui.strength.CustomDraft
import com.tracks.app.ui.strength.CustomSheet
import com.tracks.app.ui.strength.LibraryFilters
import com.tracks.app.ui.strength.PrefToggle
import com.tracks.app.ui.theme.Tokens
import com.tracks.core.api.FlexibilityFlow
import com.tracks.core.api.Stretch
import com.tracks.core.spec.muscleLabel
import kotlinx.coroutines.launch

/**
 * Mobility: what you have saved, and everything you could add.
 *
 * Flows first and the library second, because a flow is the thing someone opens
 * this screen to *do* and the library is what they open it to browse. A screen
 * that led with four hundred stretches would bury the two routines that get run
 * every week.
 */
@Composable
fun FlexibilityScreen(vm: FlexibilityViewModel, modifier: Modifier = Modifier) {
    // Null when shut; FlowEditor(null) means a new flow, which is a different
    // state from "no editor open".
    var builder by remember { mutableStateOf<FlowEditor?>(null) }
    val state by vm.state.collectAsStateWithLifecycle()
    var detail by remember { mutableStateOf<Stretch?>(null) }
    var custom by remember { mutableStateOf<CustomDraft?>(null) }
    var musclesOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (state.session != null) {
        FlowRunner(vm = vm, modifier = modifier)
        return
    }

    MusclePanel(
        open = musclesOpen,
        onOpenChange = { musclesOpen = it },
        selected = state.muscles,
        onSelectedChange = vm::setMuscles,
    ) {
    Box(Modifier.fillMaxSize()) {
    when {
        state.loading && state.flows.isEmpty() && state.stretches.isEmpty() ->
            Box(modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }

        state.flows.isEmpty() && state.stretches.isEmpty() -> EmptyState(
            title = "No stretches",
            body = state.error ?: "The stretch library is empty.",
            modifier = modifier.fillMaxSize(),
        )

        else -> LazyColumn(
            modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 16.dp,
                // Room for the selection bar, which floats over the list.
                bottom = if (state.picked.isEmpty()) 16.dp else 88.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { PendingBanner(state.pending) }

            item {
                InfoHeading("Your flows", Explain.MyFlows, Modifier.fillMaxWidth()) {
                    TonalButton("New", onClick = { builder = FlowEditor() })
                }
            }

            items(state.flows, key = { it.id }) { flow ->
                FlowRow(
                    flow = flow,
                    onStart = { vm.startFlow(flow) },
                    onEdit = { builder = FlowEditor(flow = flow) },
                )
            }

            item {
                LibraryFilters(
                    title = "Stretch library",
                    searchLabel = "Search stretches",
                    search = state.search,
                    onSearch = vm::setSearch,
                    muscles = state.muscles,
                    onOpenMuscles = { musclesOpen = true },
                    filter = state.filter,
                    onFilter = vm::setFilter,
                    onCustom = { custom = CustomDraft() },
                )
            }

            items(state.visibleStretches, key = { it.name }) { stretch ->
                StretchRow(
                    stretch = stretch,
                    picked = stretch.name in state.picked,
                    onPick = { vm.togglePicked(stretch.name) },
                    onOpen = {
                        val cid = stretch.customId
                        if (cid != null) custom = CustomDraft(
                            id = cid, name = stretch.name, primary = stretch.primaryMuscles.toSet(),
                            secondary = stretch.secondaryMuscles.toSet(), description = stretch.description ?: "",
                            seconds = stretch.durationPerSideSec ?: 60, eachSide = stretch.eachSide,
                        ) else detail = stretch
                    },
                    onPreference = { vm.setPreference(stretch.name, it) },
                )
            }
        }
    }

        if (state.picked.isNotEmpty()) {
            SelectionBar(
                count = state.picked.size,
                onClear = vm::clearPicked,
                onStart = vm::startPickedFlow,
                onSave = {
                    builder = FlowEditor(
                        from = flowStretchesFrom(state.picked),
                        name = suggestedSessionName(state.muscles),
                    )
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    }

    custom?.let { draft ->
        CustomSheet(
            stretch = true, initial = draft,
            onSave = { vm.saveCustom(it); custom = null },
            onDelete = draft.id?.let { id -> { vm.deleteCustom(id); custom = null } },
            onDismiss = { custom = null },
        )
    }

    detail?.let { stretch ->
        StretchSheet(stretch = stretch, onDismiss = { detail = null })
    }

    builder?.let { open ->
        val library = state.stretches.associateBy { it.name }
        com.tracks.app.ui.builder.BuilderPage(
            kind = remember(library) { FlowKind(library) },
            initial = remember(open) { flowDraft(open.flow, open.from, open.name) },
            library = remember(state.stretches) { state.stretches.map { it.libraryEntry() } },
            onSave = { draft ->
                vm.saveFlow(draft.id, draft.toFlowIn())
                // A selection turned into a flow has been spent.
                if (open.from.isNotEmpty()) vm.clearPicked()
                builder = null
            },
            onDelete = open.flow?.let { f -> { vm.deleteFlow(f.id); builder = null } },
            onBack = { builder = null },
        )
    }
}

/**
 * What the builder is open on.
 *
 * `flow` null means a new one — and a new one may arrive pre-filled from the
 * selection on the screen behind, which is what [from] and [name] carry.
 */
private data class FlowEditor(
    val flow: FlexibilityFlow? = null,
    val from: List<com.tracks.core.api.FlowStretch> = emptyList(),
    val name: String = "",
)

@Composable
private fun FlowRow(flow: FlexibilityFlow, onStart: () -> Unit, onEdit: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Tokens.Radius.xl),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    flow.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    // An estimate rather than a promise — the runner's real
                    // total depends on how many stretches turn out to be
                    // one-sided, which needs the library.
                    "${flow.stretches.size} stretch${if (flow.stretches.size == 1) "" else "es"}" +
                        (flow.description?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TonalButton("Edit", onClick = onEdit)
            PrimaryButton("Start", onClick = onStart)
        }
    }
}

@Composable
private fun StretchRow(
    stretch: Stretch,
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
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                stretch.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                buildList {
                    stretch.primaryMuscles.take(2).forEach { add(muscleLabel(it)) }
                    stretch.durationPerSideSec?.let { add("${it}s") }
                    if (stretch.eachSide) add("each side")
                }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
            // The same gesture as Strength: the card opens the stretch, the box
            // adds it to what you are building. Two targets, because "tell me
            // about this" and "I want this" are different intentions.
            PrefToggle(stretch.preference, onPreference)
            Checkbox(checked = picked, onCheckedChange = { onPick() })
        }
    }
}

/**
 * What to do with the stretches you have ticked.
 *
 * Deliberately the twin of Strength's bar, down to the verbs: filtering the
 * library by muscle and ticking what you want is how a routine gets written,
 * and it should not have to be done twice — once to stretch now, again inside
 * the builder to keep it.
 */
@Composable
private fun SelectionBar(
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
                "$count stretch${if (count == 1) "" else "es"}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            NeutralButton("Clear", onClick = onClear)
            TonalButton("Save as flow", onClick = onSave)
            PrimaryButton("Start", onClick = onStart)
        }
    }
}

/**
 * One stretch, in full.
 *
 * Cautions are not buried under the instructions: a stretch with a caution on it
 * is one somebody can hurt themselves doing, and that line is the reason the
 * library carries the field at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StretchSheet(stretch: Stretch, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stretch.name, style = MaterialTheme.typography.headlineSmall)
            Text(
                buildList {
                    stretch.primaryMuscles.forEach { add(muscleLabel(it)) }
                    stretch.position?.let { add(it.replace('_', ' ')) }
                }.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            stretch.cautions?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val blurb = stretch.instructions?.takeIf { it.isNotBlank() }
                ?: stretch.description?.takeIf { it.isNotBlank() }
            if (blurb != null) Text(blurb, style = MaterialTheme.typography.bodyMedium)
            stretch.breathCue?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            stretch.cues.forEach {
                Text("• $it", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

