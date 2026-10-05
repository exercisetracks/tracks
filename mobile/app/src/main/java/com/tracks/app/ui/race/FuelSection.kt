// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.race

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import com.tracks.app.ui.components.EvenGrid
import com.tracks.app.ui.components.NeutralButton
import com.tracks.app.ui.components.PrimaryButton
import com.tracks.app.ui.components.TonalButton
import com.tracks.app.ui.theme.Tokens
import com.tracks.core.format.distance
import com.tracks.core.fuel.FuelPlan
import com.tracks.core.local.LocalRacePlans

/**
 * Race fuelling on the phone, computed from synced rows
 * (LocalRacePlans.fuel) and identical to the web's.
 *
 * Laid out to be read at a glance on race morning: one summary line of the
 * targets in force, the products carried as chips, a time · distance · what
 * timeline, and one line of gut training. It had grown a paragraph of help
 * under every part; the numbers turned out to explain themselves once they were
 * in one place, so there is deliberately no helper text here. Editing the
 * targets lives behind the pencil because it is done once per race, not read
 * every time.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun FuelSection(
    s: LocalRacePlans.Strategy,
    f: LocalRacePlans.Fuel,
    onSet: (String, Any?) -> Unit,
    onAddProduct: (String, String, Double, Int, Int, Int) -> Unit,
    onToggleProduct: (String) -> Unit,
) {
    val t = f.targets
    var editing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "${t.carbsGPerH} g carbs · ${t.fluidMlPerH} ml · ${t.sodiumMgPerH} mg Na / h",
                style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.Medium,
            )
            Text(
                "every ${t.intervalMin} min",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { editing = true }) {
            Icon(Icons.Default.Edit, contentDescription = "Edit fuelling targets")
        }
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s1_5),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.s1_5),
    ) {
        f.products.forEach { p ->
            val uid = p.uid ?: return@forEach
            val carried = uid in s.fuelProductUids
            FilterChip(
                selected = carried,
                onClick = { onToggleProduct(uid) },
                label = { Text("${p.name ?: "Product"} ${trim(p.carbsG)}g") },
                leadingIcon = if (carried) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else {
                    null
                },
            )
        }
        AssistChip(
            onClick = { adding = true },
            label = { Icon(Icons.Default.Add, contentDescription = "Add product", modifier = Modifier.size(18.dp)) },
        )
    }

    if (f.timeline.items.isNotEmpty()) {
        Column {
            f.timeline.items.forEach { TimelineRow(it) }
        }
    }

    if (f.gut.isNotEmpty()) {
        Text(
            "Gut training: ${f.gut.values.min()} → ${f.gut.values.max()} g/h over ${f.gut.size} " +
                if (f.gut.size == 1) "run" else "runs",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (adding) ProductDialog(onDismiss = { adding = false }) { n, k, c, na, caf, ml ->
        onAddProduct(n, k, c, na, caf, ml); adding = false
    }
    if (editing) {
        ModalBottomSheet(
            onDismissRequest = { editing = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            FuelTargetsForm(s, t, onSet, onDone = { editing = false })
        }
    }
}

/** One stop: when, where, and what to take. Fluid only when the product carries some. */
@Composable
private fun TimelineRow(i: FuelPlan.Item) {
    val num = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(clock(i.minute), style = num, modifier = Modifier.width(52.dp))
        Text(
            i.distanceM?.let { distance(it.toDouble()) } ?: "",
            style = num,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(76.dp),
        )
        Text(
            buildString {
                append(i.name ?: "${trim(i.carbsG)} g carbs")
                if (i.fluidMl > 0) append(" + ${i.fluidMl} ml")
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The per-hour targets. A blank box means "calculated", and the calculated
 * value sits in it as the placeholder, so clearing a box is how an override
 * is undone and nothing needs explaining.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FuelTargetsForm(
    s: LocalRacePlans.Strategy,
    t: FuelPlan.Targets,
    onSet: (String, Any?) -> Unit,
    onDone: () -> Unit,
) {
    // [t] is the effective targets, and a placeholder only shows in a blank
    // box — where effective is calculated, since every keystroke is saved.
    val calc = t
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Fuelling targets", style = MaterialTheme.typography.titleMedium)
        // Two by two: a text field's intrinsic width is Material's 280 dp
        // floor, so without the pin the grid would put one per row.
        EvenGrid(minColumns = 2, maxColumns = 2) {
            TargetField("Carbs", "g/h", s.fuelCarbs, calc.carbsGPerH) {
                onSet("fuel_carbs_per_hour", it)
            }
            TargetField("Fluid", "ml/h", s.fuelFluid, calc.fluidMlPerH) {
                onSet("fuel_fluid_ml_per_hour", it)
            }
            TargetField("Sodium", "mg/h", s.fuelSodium, calc.sodiumMgPerH) {
                onSet("fuel_sodium_mg_per_hour", it)
            }
            TargetField("Every", "min", s.fuelInterval, calc.intervalMin) {
                onSet("fuel_interval_min", it)
            }
        }
        ButtonRow {
            NeutralButton("Reset to calculated", onClick = {
                listOf("fuel_carbs_per_hour", "fuel_fluid_ml_per_hour", "fuel_sodium_mg_per_hour", "fuel_interval_min")
                    .forEach { onSet(it, null) }
            })
            PrimaryButton("Done", onClick = onDone)
        }
    }
}

@Composable
private fun TargetField(label: String, unit: String, value: Int?, fallback: Int, onSave: (Int?) -> Unit) {
    var draft by remember(value) { mutableStateOf(value?.toString() ?: "") }
    // The name sits above the box rather than as its floating label: with a
    // label, Material hides the placeholder until the box is focused, and the
    // placeholder is what shows the calculated value a blank box stands for.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = draft,
            onValueChange = { v ->
                draft = v.filter(Char::isDigit)
                onSave(draft.toIntOrNull())
            },
            placeholder = { Text("$fallback") },
            suffix = { Text(unit) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ProductDialog(onDismiss: () -> Unit, onAdd: (String, String, Double, Int, Int, Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("gel") }
    var carbs by remember { mutableStateOf("25") }
    var sodium by remember { mutableStateOf("0") }
    var caffeine by remember { mutableStateOf("0") }
    var fluid by remember { mutableStateOf("0") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New product") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("gel", "drink", "chew", "bar").forEach { k ->
                        FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k) })
                    }
                }
                listOf(
                    Triple("Carbs (g)", carbs) { v: String -> carbs = v },
                    Triple("Sodium (mg)", sodium) { v: String -> sodium = v },
                    Triple("Caffeine (mg)", caffeine) { v: String -> caffeine = v },
                    Triple("Fluid (ml)", fluid) { v: String -> fluid = v },
                ).forEach { (label, value, set) ->
                    OutlinedTextField(
                        value, { set(it.filter { c -> c.isDigit() || c == '.' }) }, label = { Text(label) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            }
        },
        confirmButton = {
            PrimaryButton("Add", onClick = {
                onAdd(name.trim(), kind, carbs.toDoubleOrNull() ?: 0.0, sodium.toIntOrNull() ?: 0,
                    caffeine.toIntOrNull() ?: 0, fluid.toIntOrNull() ?: 0)
            }, enabled = name.isNotBlank())
        },
        dismissButton = { NeutralButton("Cancel", onClick = onDismiss) },
    )
}

/** "Choose saved course": a map track as the race course. */
@Composable
internal fun SavedCourseMenu(tracks: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        TonalButton("Choose saved course", onClick = { open = true }, modifier = Modifier.fillMaxWidth())
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            tracks.forEach { (uid, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onPick(uid) })
            }
        }
    }
}

private fun trim(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()

/** Race clock, h:mm: one width for every row, so the column lines up. */
private fun clock(m: Int): String = "${m / 60}:${(m % 60).toString().padStart(2, '0')}"
