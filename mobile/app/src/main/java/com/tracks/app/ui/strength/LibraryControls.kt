// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.strength

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import com.tracks.app.ui.components.ButtonRow
import com.tracks.app.ui.components.EvenGrid
import com.tracks.app.ui.components.OptionCell
import com.tracks.app.ui.profile.EquipmentPicker
import com.tracks.app.ui.components.TracksSwitch
import com.tracks.app.ui.components.DangerButton
import com.tracks.app.ui.components.Explain
import com.tracks.app.ui.components.InfoHeading
import com.tracks.app.ui.components.NeutralButton
import com.tracks.app.ui.components.PrimaryButton
import com.tracks.app.ui.components.TonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tracks.app.ui.theme.Tokens
import com.tracks.core.spec.muscleKeys
import com.tracks.core.spec.muscleLabel

/**
 * The library controls both Strength and Mobility share with the web's
 * Exercises and Stretches tabs: the All / Preferred / Excluded / Custom filter,
 * the ♡ / ⊘ preference on each row, and the "+ Custom" form.
 *
 * One file for both pages because they are one feature on the web
 * (ExercisesTab / StretchesTab share the same toggle and filter), and two
 * copies here would drift the way the phone and web did before.
 */
enum class LibraryFilter(val label: String) { All("All"), Preferred("Preferred"), Excluded("Excluded"), Custom("Custom") }

/** Whether an item with this [preference] / [isCustom] passes [filter]. */
fun LibraryFilter.accepts(preference: String?, isCustom: Boolean): Boolean = when (this) {
    LibraryFilter.All -> true
    LibraryFilter.Preferred -> preference == PREFERRED
    LibraryFilter.Excluded -> preference == EXCLUDED
    LibraryFilter.Custom -> isCustom
}

const val PREFERRED = "preferred"
const val EXCLUDED = "excluded"

/** Tapping the active choice clears it — the web's PrefToggle, same rule. */
fun nextPreference(current: String?, tapped: String): String? = if (current == tapped) null else tapped

/**
 * Prefer / never-plan. Excluded is never scheduled by the plan generator,
 * which is why it is worth a button on every row rather than a menu.
 *
 * These were a bare ✓ and ✗, which read as "did this work?" — users took them
 * for animation confirmations. A heart and a no-entry sign, named for screen
 * readers and explained behind the library heading's "?", say what they do.
 */
@Composable
fun PrefToggle(preference: String?, onChange: (String?) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    Row {
        IconButton(onClick = { onChange(nextPreference(preference, PREFERRED)) }, modifier = Modifier.size(40.dp)) {
            Text(
                if (preference == PREFERRED) "♥" else "♡",
                color = if (preference == PREFERRED) MaterialTheme.colorScheme.primary else muted,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics {
                    contentDescription = if (preference == PREFERRED) "Preferred — tap to clear" else "Prefer in my plan"
                },
            )
        }
        IconButton(onClick = { onChange(nextPreference(preference, EXCLUDED)) }, modifier = Modifier.size(40.dp)) {
            Text(
                "⊘",
                color = if (preference == EXCLUDED) Color(0xFFE5484D) else muted,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics {
                    contentDescription = if (preference == EXCLUDED) "Never planned — tap to clear" else "Never put in my plan"
                },
            )
        }
    }
}

/**
 * The library heading, search, and one row of filters — used as is by Strength
 * and Flexibility, which the user asked to behave identically.
 *
 * The filters are one row of equal cells (see Justified.kt): the library
 * filter as a dropdown, Muscles, and on Strength My equipment. The user asked
 * for them on one line; four chips and a clear button had become two
 * scrolling rows. That only fits a 360 dp phone as three cells, so:
 * - All / Preferred / Excluded / Custom is a dropdown, not four chips;
 * - clearing muscles lives in the muscle panel (it has its own Clear), not
 *   beside the chip;
 * - "+ Custom" trails the heading, as "New" does above "My workouts".
 *
 * My equipment opens a sheet, exactly as Muscles opens its panel: the switch
 * that filters, and the owned-equipment grid Settings › Strength shows, so the
 * list can be narrowed and the equipment corrected in one place.
 *
 * @param myEquipment what the user owns; null leaves the equipment cell out
 * (stretches need none).
 */
@Composable
fun LibraryFilters(
    title: String,
    searchLabel: String,
    search: String,
    onSearch: (String) -> Unit,
    muscles: Set<String>,
    onOpenMuscles: () -> Unit,
    filter: LibraryFilter,
    onFilter: (LibraryFilter) -> Unit,
    onCustom: () -> Unit,
    myEquipment: Set<String>? = null,
    onlyMyEquipment: Boolean = false,
    onToggleEquipment: () -> Unit = {},
    onEquipment: (List<String>) -> Unit = {},
) {
    var equipmentOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s2)) {
        InfoHeading(title, Explain.Library, Modifier.fillMaxWidth()) {
            TonalButton("+ Custom", onClick = onCustom, small = true)
        }
        OutlinedTextField(
            value = search,
            onValueChange = onSearch,
            label = { Text(searchLabel) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val cells = if (myEquipment == null) 2 else 3
        // minColumns = every cell: one row is the point, and a long label
        // wraps inside its cell rather than pushing a cell to a second row.
        EvenGrid(spacing = Tokens.Space.s1_5, minColumns = cells) {
            LibraryFilterMenu(filter, onFilter)
            OptionCell(musclesLabel(muscles), selected = muscles.isNotEmpty(), onClick = onOpenMuscles)
            if (myEquipment != null) {
                OptionCell("My equipment", selected = onlyMyEquipment, onClick = { equipmentOpen = true })
            }
        }
    }
    if (equipmentOpen && myEquipment != null) {
        EquipmentSheet(myEquipment, onlyMyEquipment, onToggleEquipment, onEquipment) { equipmentOpen = false }
    }
}

/**
 * One muscle by name, more as a count: the cell is a third of a small
 * phone's width, and "Quads, Lats +1" was cropped to "Quads, L…" there.
 */
internal fun musclesLabel(muscles: Set<String>): String = when (muscles.size) {
    0 -> "Muscles"
    1 -> muscleLabel(muscles.first())
    else -> "Muscles · ${muscles.size}"
}

/** All / Preferred / Excluded / Custom as one cell that opens a menu. */
@Composable
private fun LibraryFilterMenu(filter: LibraryFilter, onFilter: (LibraryFilter) -> Unit) {
    var open by remember { mutableStateOf(false) }
    // Passes the grid's row height on, so this cell is as tall as a
    // neighbour whose label wrapped.
    Box(propagateMinConstraints = true) {
        OptionCell(
            "${filter.label} ▾",
            // Highlighted only when it narrows the list, like the other cells.
            selected = filter != LibraryFilter.All,
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LibraryFilter.entries.forEach { f ->
                DropdownMenuItem(
                    text = {
                        Text(
                            f.label,
                            color = if (f == filter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = { onFilter(f); open = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EquipmentSheet(
    have: Set<String>,
    onlyMine: Boolean,
    onToggle: () -> Unit,
    onChange: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(Tokens.Space.s4),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.s3),
        ) {
            Text("My equipment", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Only exercises I can do", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                TracksSwitch(onlyMine, { onToggle() })
            }
            // The setting itself, not a filter-only copy: what is ticked here
            // is also what planned sessions may use (Settings › Strength).
            EquipmentPicker(have, onChange)
            ButtonRow { PrimaryButton("Done", onClick = onDismiss) }
        }
    }
}

/** What the custom form edits — the fields the web's modal offers, keyed by contract field. */
data class CustomDraft(
    val id: Int? = null,
    val name: String = "",
    val primary: Set<String> = emptySet(),
    val secondary: Set<String> = emptySet(),
    val description: String = "",
    /** Stretches only. */
    val seconds: Int = 60,
    val eachSide: Boolean = false,
    /** Exercises only. */
    val sets: Int = 3,
    val reps: Int = 10,
) {
    fun values(stretch: Boolean): Map<String, Any?> = buildMap {
        put("name", name.trim())
        put("primary_muscles", primary.toList())
        put("secondary_muscles", secondary.toList())
        put("description", description.ifBlank { null })
        if (stretch) {
            put("duration_per_side_sec", seconds); put("each_side", eachSide)
        } else {
            put("default_sets", sets); put("default_reps", reps)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomSheet(
    stretch: Boolean,
    initial: CustomDraft,
    onSave: (CustomDraft) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var d by remember { mutableStateOf(initial) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(Tokens.Space.s4),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.s3),
        ) {
            Text(
                (if (d.id == null) "New custom " else "Edit custom ") + if (stretch) "stretch" else "exercise",
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(d.name, { d = d.copy(name = it) }, label = { Text("Name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            MusclePickRow("Primary muscles", d.primary) { d = d.copy(primary = it) }
            MusclePickRow("Secondary muscles", d.secondary) { d = d.copy(secondary = it) }
            if (stretch) {
                NumberRow("Hold (seconds)", d.seconds, 5) { d = d.copy(seconds = it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Each side", Modifier.weight(1f))
                    TracksSwitch(d.eachSide, { d = d.copy(eachSide = it) })
                }
            } else {
                NumberRow("Sets", d.sets, 1) { d = d.copy(sets = it) }
                NumberRow("Reps", d.reps, 1) { d = d.copy(reps = it) }
            }
            OutlinedTextField(d.description, { d = d.copy(description = it) }, label = { Text("Description") },
                modifier = Modifier.fillMaxWidth())
            ButtonRow {
                onDelete?.let { DangerButton("Delete", onClick = it) }
                NeutralButton("Cancel", onClick = onDismiss)
                PrimaryButton("Save", onClick = { onSave(d) }, enabled = d.name.isNotBlank())
            }
        }
    }
}

@Composable
private fun MusclePickRow(label: String, selected: Set<String>, onChange: (Set<String>) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s1_5)) {
            // Selected first, so what is picked stays in view.
            val keys = muscleKeys.sortedWith(compareBy({ it !in selected }, { muscleLabel(it) }))
            keys.forEach { k ->
                FilterChip(selected = k in selected, onClick = { onChange(if (k in selected) selected - k else selected + k) },
                    label = { Text(muscleLabel(k)) })
            }
        }
    }
}

@Composable
private fun NumberRow(label: String, value: Int, step: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        NeutralButton("−", onClick = { onChange((value - step).coerceAtLeast(step)) }, small = true)
        Text("$value", style = MaterialTheme.typography.titleMedium)
        NeutralButton("+", onClick = { onChange(value + step) }, small = true)
    }
}
