// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.race

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
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
import com.tracks.core.spec.FUEL_CATALOG
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.heightIn

/**
 * Race fuelling on the phone, computed from synced rows
 * (LocalRacePlans.fuel) and identical to the web's (FuelSection.jsx).
 *
 * Laid out to be read at a glance on race morning: four gauges (carbs,
 * sodium, potassium, fluid per hour), the race's stops on a strip along its
 * clock with the list folded under it, the products carried as chips — the
 * built-in homemade mix first — and that mix's recipe when it is carried.
 * A gauge's scale runs to an elite ultra athlete's hour, its fill is what the
 * products deliver and its tick the target (docs/race-fuelling-research.md).
 * It had grown a paragraph of help under every part; the numbers turned out
 * to explain themselves once they were in one place, so there is deliberately
 * no helper text here. Editing the targets lives behind the pencil because it
 * is done once per race, not read every time.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun FuelSection(
    s: LocalRacePlans.Strategy,
    f: LocalRacePlans.Fuel,
    onSet: (String, Any?) -> Unit,
    onAddProduct: (LocalRacePlans.ProductDraft) -> Unit,
    onToggleProduct: (String) -> Unit,
    /** The predicted race, in minutes: the strip's length. */
    durationMin: Double = 0.0,
) {
    val t = f.targets
    var editing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        FuelGauges(t, f.timeline.perHour, Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            IconButton(onClick = { editing = true }) {
                Icon(Icons.Default.Edit, contentDescription = "Edit fuelling targets")
            }
            // The mix's sips set the stops when it is carried (FuelPlan.timeline).
            Text(
                "every ${f.homemade?.intervalMin ?: t.intervalMin} min",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    TimelineStrip(f.timeline.items, durationMin)

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s1_5),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.s1_5),
    ) {
        val mixOn = FuelPlan.HOMEMADE_UID in s.fuelProductUids
        FilterChip(
            selected = mixOn,
            onClick = { onToggleProduct(FuelPlan.HOMEMADE_UID) },
            label = { Text(FuelPlan.HOMEMADE_NAME) },
            leadingIcon = if (mixOn) {
                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
            } else {
                null
            },
        )
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

    f.homemade?.let { MixCard(it, s, onSet) }

    if (f.gut.isNotEmpty()) {
        Text(
            "Gut training: ${f.gut.values.min()} → ${f.gut.values.max()} g/h over ${f.gut.size} " +
                if (f.gut.size == 1) "run" else "runs",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (adding) ProductDialog(onDismiss = { adding = false }) { onAddProduct(it); adding = false }
    if (editing) {
        ModalBottomSheet(
            onDismissRequest = { editing = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            FuelTargetsForm(s, t, onSet, onDone = { editing = false })
        }
    }
}

/** The gauges' colours, as on the web (FuelSection.jsx's GAUGES); each is named under its dial. */
private val CARBS = Color(0xFFEDA100)
private val SODIUM = Color(0xFF1BAF7A)
private val POTASSIUM = Color(0xFFE87BA4)
private val FLUID = Color(0xFF2A78D6)

@Composable
private fun FuelGauges(t: FuelPlan.Targets, got: FuelPlan.Amounts, modifier: Modifier = Modifier) {
    val c = FuelPlan.CEILINGS
    // Two by two on a phone: four dials across leave each too small to read.
    EvenGrid(modifier, minColumns = 2, maxColumns = 4) {
        Gauge("Carbs", "g", got.carbsG, t.carbsGPerH, c.carbsGPerH, CARBS)
        Gauge("Sodium", "mg", got.sodiumMg, t.sodiumMgPerH, c.sodiumMgPerH, SODIUM)
        Gauge("Potassium", "mg", got.potassiumMg, t.potassiumMgPerH, c.potassiumMgPerH, POTASSIUM)
        Gauge("Fluid", "ml", got.fluidMl, t.fluidMlPerH, c.fluidMlPerH, FLUID)
    }
}

@Composable
private fun Gauge(label: String, unit: String, got: Int, target: Int, ceiling: Int, color: Color) {
    com.tracks.app.ui.dashboard.RadialGauge(
        value = got.toDouble(),
        zones = listOf(com.tracks.app.ui.dashboard.GaugeZone(label, 0.0, ceiling.toDouble(), color)),
        label = "$label / h",
        unit = unit,
        size = 96.dp,
        caption = "target $target",
        captionColor = MaterialTheme.colorScheme.onSurfaceVariant,
        target = target.toDouble(),
    )
}

/**
 * The stops along the race clock: a dot each, a sip of the homemade mix
 * small and a product large. Tapping one names it; the full list is folded
 * under the strip.
 */
@Composable
private fun TimelineStrip(items: List<FuelPlan.Item>, durationMin: Double) {
    if (items.isEmpty() || durationMin <= 0) return
    var picked by remember(items) { mutableStateOf<FuelPlan.Item?>(null) }
    var all by remember { mutableStateOf(false) }
    val sip = MaterialTheme.colorScheme.primary
    val line = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.fillMaxWidth().height(24.dp).pointerInput(items) {
                detectTapGestures { o ->
                    val m = o.x / size.width * durationMin
                    picked = items.minBy { kotlin.math.abs(it.minute - m) }
                }
            },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val y = size.height / 2
                drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx())
                items.forEach { i ->
                    val x = (i.minute / durationMin).toFloat().coerceIn(0f, 1f) * size.width
                    val isSip = i.productUid == FuelPlan.HOMEMADE_UID
                    val r = (if (isSip) 3.dp else 5.dp).toPx() * if (i == picked) 1.5f else 1f
                    drawCircle(if (isSip) sip else CARBS, r, Offset(x, y))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val small = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")
            Text("0:00", style = small, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                picked?.let { i ->
                    listOfNotNull(clock(i.minute), i.distanceM?.let { distance(it.toDouble()) }, stopName(i))
                        .joinToString(" · ")
                } ?: "${items.size} stops",
                style = small,
            )
            Text(clock(durationMin.toInt()), style = small, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            if (all) "Hide stops" else "Show all stops (${items.size})",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { all = !all }.padding(vertical = 4.dp),
        )
        if (all) Column { items.forEach { TimelineRow(it) } }
    }
}

/** What to take at a stop: the product (or plain grams), plus its fluid when it carries any. */
internal fun stopName(i: FuelPlan.Item): String = buildString {
    append(i.name ?: "${trim(i.carbsG)} g carbs")
    if (i.fluidMl > 0) append(" + ${i.fluidMl} ml")
}

/** "45 g (3½ tbsp)", or "60 ml (¼ cup)" for a liquid — the web's amountLabel. */
internal fun amountLabel(i: FuelPlan.Ingredient): String =
    i.ml?.let { "${trim(it)} ml (${i.measure})" } ?: "${trim(i.grams ?: 0.0)} g (${i.measure})"

/** The web's mixAdvice: the mix is too strong to absorb well, and how much food would fix it. */
internal fun mixAdvice(r: FuelPlan.Recipe): String? = when {
    r.sipMl == 0 && r.extraCarbsGPerH > 0 ->
        "Your drinks already bring all the fluid. Take about ${r.extraCarbsGPerH} g of carbs an hour as gels or food."
    !r.overBand -> null
    else -> "This mix is ${trim(r.concentrationPct)}%, stronger than the ${r.bandPct}% that absorbs well in these " +
        "conditions. About ${r.extraCarbsGPerH} g of carbs an hour from gels or food would bring it to ${r.bandPct}%."
}

private val PRESETS = listOf("basic" to "Basic", "high_carb" to "High-carb", "natural" to "Natural")

/** The homemade mix's recipe: pick a recipe, size the bottle, read what goes in. */
@Composable
private fun MixCard(r: FuelPlan.Recipe, s: LocalRacePlans.Strategy, onSet: (String, Any?) -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(Tokens.Radius.xl))
            .padding(Tokens.Space.s3),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.s2),
    ) {
        Text(FuelPlan.HOMEMADE_NAME, style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s1_5)) {
            PRESETS.forEach { (key, label) ->
                FilterChip(selected = r.preset == key, onClick = { onSet("fuel_mix_preset", key) }, label = { Text(label) })
            }
        }
        var bottle by remember(s.fuelMixBottleMl) { mutableStateOf(s.fuelMixBottleMl?.toString() ?: "") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s2)) {
            OutlinedTextField(
                value = bottle,
                onValueChange = { v ->
                    bottle = v.filter(Char::isDigit)
                    onSet("fuel_mix_bottle_ml", bottle.toIntOrNull()?.takeIf { it >= 100 })
                },
                placeholder = { Text("${FuelPlan.DEFAULT_BOTTLE_ML}") },
                suffix = { Text("ml") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(132.dp),
            )
            Text("per bottle · make ${r.bottles}", style = MaterialTheme.typography.bodyMedium)
        }
        Column {
            r.ingredients.forEach { IngredientRow(it.name, amountLabel(it)) }
            IngredientRow("Water", "to ${r.bottleMl} ml")
        }
        mixAdvice(r)?.let {
            // Amber, as the web's note: a warning, not an error.
            val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
            Text(it, style = MaterialTheme.typography.bodySmall, color = if (dark) Color(0xFFFCD34D) else Color(0xFF92400E),
                modifier = Modifier.fillMaxWidth()
                    .background(if (dark) Color(0x33F59E0B) else Color(0xFFFEF3C7), RoundedCornerShape(Tokens.Radius.lg))
                    .padding(horizontal = Tokens.Space.s3, vertical = Tokens.Space.s2))
        }
        Text(
            "${trim(r.concentrationPct)}% · sip ${r.sipMl} ml every ${r.intervalMin} min" +
                if (r.preset == "natural") " · coconut water varies: check its label" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun IngredientRow(name: String, amount: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, style = MaterialTheme.typography.bodyMedium)
        Text(amount, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
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
            stopName(i),
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
            TargetField("Potassium", "mg/h", s.fuelPotassium, calc.potassiumMgPerH) {
                onSet("fuel_potassium_mg_per_hour", it)
            }
            TargetField("Every", "min", s.fuelInterval, calc.intervalMin) {
                onSet("fuel_interval_min", it)
            }
        }
        ButtonRow {
            NeutralButton("Reset to calculated", onClick = {
                listOf("fuel_carbs_per_hour", "fuel_fluid_ml_per_hour", "fuel_sodium_mg_per_hour",
                    "fuel_potassium_mg_per_hour", "fuel_interval_min")
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

/**
 * Add a product: first a searchable list of common race foods with their
 * label nutrition (spec/fuel_catalog.yaml), then the form, filled in from the
 * pick — or blank for "Custom" — so a label that differs can be corrected
 * before adding. The web's ProductForm does the same.
 */
@Composable
private fun ProductDialog(onDismiss: () -> Unit, onAdd: (LocalRacePlans.ProductDraft) -> Unit) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("gel") }
    var carbs by remember { mutableStateOf("25") }
    var sodium by remember { mutableStateOf("0") }
    var potassium by remember { mutableStateOf("0") }
    var caffeine by remember { mutableStateOf("0") }
    var fluid by remember { mutableStateOf("0") }
    var picking by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    if (picking) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Add a product") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(query, { query = it }, label = { Text("Search") }, singleLine = true)
                    val q = query.trim().lowercase()
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(FUEL_CATALOG.filter { q.isEmpty() || it.name.lowercase().contains(q) }, key = { it.name }) { p ->
                            Column(Modifier.fillMaxWidth().clickable {
                                name = p.name; kind = p.kind; carbs = trim(p.carbsG); sodium = "${p.sodiumMg}"
                                potassium = "${p.potassiumMg}"; caffeine = "${p.caffeineMg}"; fluid = "${p.fluidMl}"
                                picking = false
                            }.padding(vertical = 8.dp)) {
                                Text(p.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    listOfNotNull("${trim(p.carbsG)} g carbs", "${p.sodiumMg} mg Na",
                                        p.potassiumMg.takeIf { it > 0 }?.let { "$it mg K" },
                                        p.fluidMl.takeIf { it > 0 }?.let { "$it ml" },
                                        p.caffeineMg.takeIf { it > 0 }?.let { "$it mg caffeine" }).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TonalButton("Custom", onClick = { name = query.trim(); picking = false }) },
            dismissButton = { NeutralButton("Cancel", onClick = onDismiss) },
        )
        return
    }
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
                    Triple("Potassium (mg)", potassium) { v: String -> potassium = v },
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
                onAdd(LocalRacePlans.ProductDraft(
                    name.trim(), kind, carbsG = carbs.toDoubleOrNull() ?: 0.0, sodiumMg = sodium.toIntOrNull() ?: 0,
                    potassiumMg = potassium.toIntOrNull() ?: 0, caffeineMg = caffeine.toIntOrNull() ?: 0,
                    fluidMl = fluid.toIntOrNull() ?: 0,
                ))
            }, enabled = name.isNotBlank())
        },
        dismissButton = { NeutralButton("Back", onClick = { picking = true }) },
    )
}

private fun trim(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()

/** Race clock, h:mm: one width for every row, so the column lines up. */
private fun clock(m: Int): String = "${m / 60}:${(m % 60).toString().padStart(2, '0')}"
