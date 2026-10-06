// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.key
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
import com.tracks.app.ui.components.PrimaryButton
import com.tracks.app.ui.components.TonalButton
import com.tracks.app.ui.theme.Tokens
import com.tracks.core.api.DailyMetricPatch
import com.tracks.core.api.Meal
import com.tracks.core.api.MealLog
import com.tracks.core.api.MealIn
import com.tracks.core.api.MealLogCreate
import com.tracks.core.format.Units
import com.tracks.core.format.displayToKg
import com.tracks.core.format.weightUnit

/**
 * One button, one form, one Save, for everything a person records by hand.
 *
 * ## Why one and not four
 *
 * Weight, water, calories and meals had a section each, and between them they
 * occupied a third of the page to be used for about ten seconds a day. Worse,
 * they were the *only* sections that were permanently expanded, so the readings
 * — the reason anyone opens this screen — were pushed below a stack of empty
 * text fields.
 *
 * They are all the same gesture at heart: "here is something the watch could
 * not know". So they collapse to one button, and the form is a sheet that
 * appears when it is wanted and is gone the rest of the time. The web's
 * LogTodayModal is the same form, laid out the same way.
 *
 * ## What is instant and what waits for Save
 *
 * Everything typed is committed by the one primary button, which says how many
 * things it is about to record. The things that are *not* form fields stay
 * instant: a saved-meal chip logs its meal outright — repeating yesterday's
 * porridge should not cost a form — and the water buttons add a glass to the
 * field, which is how water is actually recorded: one more glass, not a total
 * somebody computes.
 *
 * ## Where saved meals come from
 *
 * The food line, with "Remember this meal" ticked. The phone used to show the
 * chips but had no way to make one; the library had to be curated on the web
 * first. Manage turns the chips into an editor for the rare fix or deletion,
 * and while it is on a tap edits instead of logging, so the two meanings never
 * share a gesture.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HealthLogSheet(
    weightKg: Double?,
    hydrationMl: Double?,
    savedMeals: List<Meal>,
    mealsToday: List<MealLog>,
    caloriesToday: Int,
    onSaveDay: (DailyMetricPatch) -> Unit,
    onLogSaved: (MealLogCreate) -> Unit,
    onLogFood: (MealLogCreate, Boolean) -> Unit,
    onSaveMeal: (Int, MealIn) -> Unit,
    onDeleteSavedMeal: (Int) -> Unit,
    onDeleteEntry: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var weight by remember { mutableStateOf("") }
    var water by remember { mutableStateOf("") }
    var mealName by remember { mutableStateOf("") }
    var mealCalories by remember { mutableStateOf("") }
    var macros by remember { mutableStateOf(Macros()) }
    var macrosOpen by remember { mutableStateOf(false) }
    var rememberMeal by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Meal?>(null) }

    val typedWeight = weight.toDoubleOrNull()
    val typedWater = water.toIntOrNull()
    val typedMeal = mealName.isNotBlank()
    // What Save is about to do, counted so the button can say it. A button
    // whose label changes with the form is the cheapest way to answer "did it
    // notice what I typed" without a second screen.
    val pending = listOf(typedWeight != null, typedWater != null, typedMeal).count { it }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Log today", style = MaterialTheme.typography.headlineSmall)
            Text(
                // What is already down for today, so somebody adding a glass
                // of water can see what they are adding to.
                listOfNotNull(
                    weightKg?.let { com.tracks.core.format.weight(it) },
                    hydrationMl?.takeIf { it > 0 }?.let { "${it.toInt()} ml" },
                    caloriesToday.takeIf { it > 0 }?.let { "$it kcal" },
                ).joinToString(" · ").let { if (it.isEmpty()) "Nothing logged yet today" else "$it so far" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = weight,
                    onValueChange = { input -> weight = input.filter { it.isDigit() || it == '.' } },
                    label = { Text("Weight ${weightUnit(Units.imperial)}") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = water,
                    onValueChange = { input -> water = input.filter { it.isDigit() } },
                    label = { Text("Water ml") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }

            // A glass at a time, added to the day's running total rather than
            // replacing it — nobody knows what they have drunk today, they know
            // they have just drunk a glass.
            ButtonRow {
                GLASSES.forEach { millilitres ->
                    NeutralButton("+$millilitres ml", small = true, onClick = {
                        val base = water.toIntOrNull() ?: hydrationMl?.toInt() ?: 0
                        water = (base + millilitres).toString()
                    })
                }
            }

            SectionLabel("Food")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = mealName,
                    onValueChange = { input ->
                        mealName = input
                        // A saved meal typed by name fills its calories, so a
                        // remembered meal is as quick from the keyboard as
                        // from its chip.
                        if (mealCalories.isEmpty()) {
                            savedMeals.firstOrNull { it.name.equals(input.trim(), ignoreCase = true) }
                                ?.let { mealCalories = it.calories.toString() }
                        }
                    },
                    label = { Text("What did you eat?") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = mealCalories,
                    onValueChange = { input -> mealCalories = input.filter { it.isDigit() } },
                    label = { Text("kcal") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(96.dp),
                )
            }
            if (macrosOpen) {
                MacroFields(macros, onChange = { macros = it })
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    Modifier.clickable { rememberMeal = !rememberMeal },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = rememberMeal, onCheckedChange = { rememberMeal = it })
                    Text("Remember this meal", style = MaterialTheme.typography.bodyMedium)
                }
                NeutralButton(
                    if (macrosOpen) "Fewer details" else "Protein, carbs, fat",
                    small = true,
                    onClick = { macrosOpen = !macrosOpen },
                )
            }

            if (savedMeals.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    SectionLabel("Saved meals")
                    if (managing) {
                        TonalButton("Done", small = true, onClick = { managing = false; editing = null })
                    } else {
                        NeutralButton("Manage", small = true, onClick = { managing = true })
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    savedMeals.take(MAX_SAVED_CHIPS).forEach { meal ->
                        val label = "${meal.name}  ${meal.calories}"
                        when {
                            !managing -> TonalButton(label, small = true, icon = Icons.Default.Add, onClick = {
                                onLogSaved(MealLogCreate(mealId = meal.id, name = meal.name))
                            })
                            editing?.id == meal.id -> PrimaryButton(label, small = true, onClick = { editing = null })
                            else -> NeutralButton(label, small = true, onClick = { editing = meal })
                        }
                    }
                }
                editing?.takeIf { managing }?.let { meal ->
                    key(meal.id) {
                        MealEditor(
                            meal = meal,
                            onSave = { onSaveMeal(meal.id, it); editing = null },
                            onDelete = { onDeleteSavedMeal(meal.id); editing = null },
                            onCancel = { editing = null },
                        )
                    }
                }
            } else {
                Text(
                    "Tick “Remember this meal” and it appears here, one tap to log next time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            PrimaryButton(
                if (pending > 1) "Save $pending entries" else "Save",
                onClick = {
                    if (typedWeight != null || typedWater != null) {
                        onSaveDay(
                            DailyMetricPatch(
                                // Typed in whatever unit the field said; stored in kg.
                                weightKg = typedWeight?.let { displayToKg(it) },
                                hydrationMl = typedWater,
                            )
                        )
                    }
                    if (typedMeal) {
                        onLogFood(
                            MealLogCreate(
                                name = mealName.trim(),
                                calories = mealCalories.toIntOrNull(),
                                proteinG = macros.protein.toDoubleOrNull(),
                                carbsG = macros.carbs.toDoubleOrNull(),
                                fatG = macros.fat.toDoubleOrNull(),
                            ),
                            rememberMeal,
                        )
                    }
                    weight = ""
                    water = ""
                    mealName = ""
                    mealCalories = ""
                    macros = Macros()
                    rememberMeal = false
                },
                // Nothing typed means nothing to send; an empty patch is a
                // request that costs a round trip to change nothing.
                enabled = pending > 0,
                modifier = Modifier.fillMaxWidth(),
            )

            if (mealsToday.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    SectionLabel("Today")
                    Text(
                        "$caloriesToday kcal",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                mealsToday.forEach { entry ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            timeOf(entry.loggedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(56.dp),
                        )
                        Text(
                            entry.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${entry.calories}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        IconButton(onClick = { onDeleteEntry(entry.id) }) {
                            Icon(Icons.Default.Close, contentDescription = "Delete ${entry.name}")
                        }
                    }
                }
            }
        }
    }
}

/** The optional macros, as typed: text until Save, so a half-typed "1." survives. */
private data class Macros(val protein: String = "", val carbs: String = "", val fat: String = "")

@Composable
private fun MacroFields(macros: Macros, onChange: (Macros) -> Unit) {
    fun clean(input: String) = input.filter { it.isDigit() || it == '.' }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MacroField("Protein g", macros.protein, Modifier.weight(1f)) { onChange(macros.copy(protein = clean(it))) }
        MacroField("Carbs g", macros.carbs, Modifier.weight(1f)) { onChange(macros.copy(carbs = clean(it))) }
        MacroField("Fat g", macros.fat, Modifier.weight(1f)) { onChange(macros.copy(fat = clean(it))) }
    }
}

@Composable
private fun MacroField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

/** A saved meal opened under Manage: its name, calories and macros, and its fate. */
@Composable
private fun MealEditor(meal: Meal, onSave: (MealIn) -> Unit, onDelete: () -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(meal.name) }
    var calories by remember { mutableStateOf(meal.calories.toString()) }
    var macros by remember {
        mutableStateOf(
            Macros(
                meal.proteinG?.let(::plain).orEmpty(),
                meal.carbsG?.let(::plain).orEmpty(),
                meal.fatG?.let(::plain).orEmpty(),
            )
        )
    }
    Surface(
        shape = RoundedCornerShape(Tokens.Radius.xl),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("Name") },
                    singleLine = true, modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = calories, onValueChange = { input -> calories = input.filter { it.isDigit() } },
                    label = { Text("kcal") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(96.dp),
                )
            }
            MacroFields(macros, onChange = { macros = it })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DangerButton("Delete", small = true, onClick = onDelete)
                Spacer(Modifier.weight(1f))
                NeutralButton("Cancel", small = true, onClick = onCancel)
                PrimaryButton(
                    "Save",
                    small = true,
                    enabled = name.isNotBlank(),
                    onClick = {
                        onSave(
                            MealIn(
                                name = name.trim(),
                                calories = calories.toIntOrNull() ?: 0,
                                proteinG = macros.protein.toDoubleOrNull(),
                                carbsG = macros.carbs.toDoubleOrNull(),
                                fatG = macros.fat.toDoubleOrNull(),
                            )
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** "12.5" rather than "12.5000001", and "12" rather than "12.0". */
private fun plain(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

/** The local time a food entry was eaten at, "8:10 AM". */
private fun timeOf(stamp: String): String =
    runCatching {
        java.time.OffsetDateTime.parse(stamp)
            .atZoneSameInstant(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
    }.getOrDefault("")

/** A glass, a bottle, a big bottle. Round numbers people actually drink in. */
private val GLASSES = listOf(250, 500, 750)

/**
 * The button that opens a form.
 *
 * Shared so that "Log" on the Body group and "Log injury" on the injury list
 * are visibly the same offer. They were a filled Button and a bare TextButton
 * before, which made two identical actions look like an action and an
 * afterthought.
 */
@Composable
fun LogButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // Centred, and centring itself rather than leaving each caller to do it:
    // left-aligned it read as a form field that had lost its label, and the two
    // places this appears would have drifted apart the first time one of them
    // was moved.
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        TonalButton(text, onClick = onClick, icon = Icons.Default.Add)
    }
}

private const val MAX_SAVED_CHIPS = 12
