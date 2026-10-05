// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.AssistChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tracks.app.ui.components.ButtonRow
import com.tracks.app.ui.components.NeutralButton
import com.tracks.app.ui.components.PrimaryButton
import com.tracks.app.ui.components.TonalButton
import com.tracks.app.ui.theme.Tokens
import com.tracks.core.api.DailyMetricPatch
import com.tracks.core.api.Meal
import com.tracks.core.api.MealLog
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
 * appears when it is wanted and is gone the rest of the time.
 *
 * ## Why the sections inside it went too
 *
 * The sheet reproduced the split it had just removed: a BODY heading over
 * weight and water with a Save under them, a rule, then a FOOD heading over a
 * meal row with an *Add* under that. Two headings, a divider and two verbs for
 * one act — filling in a form — and no way to tell from looking which button
 * would commit which half. People pressed Save and lost the meal they had
 * typed.
 *
 * Now it is one form. Everything typed is committed by the one primary button,
 * which says how many things it is about to record, and the headings and the
 * rule are gone because with four fields on a screen there is nothing left to
 * organise.
 *
 * The two things that are *not* form fields stay instant, and that is the
 * distinction the layout draws instead: a saved-meal chip and the water
 * shortcuts are single taps that mean something on their own. A chip logs its
 * meal outright — repeating yesterday's porridge should not cost a form — and
 * the water buttons add a glass to whatever is already in the field, which is
 * how water is actually recorded: not as a total somebody computes, but as one
 * more glass.
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
    onLogMeal: (MealLogCreate) -> Unit,
    onDeleteMeal: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var weight by remember { mutableStateOf("") }
    var water by remember { mutableStateOf("") }
    var mealName by remember { mutableStateOf("") }
    var mealCalories by remember { mutableStateOf("") }

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
                ).joinToString(" · ").ifEmpty { "Nothing logged yet today" },
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

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = mealName,
                    onValueChange = { mealName = it },
                    label = { Text("Meal") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = mealCalories,
                    onValueChange = { input -> mealCalories = input.filter { it.isDigit() } },
                    label = { Text("kcal") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(104.dp),
                )
            }

            if (savedMeals.isNotEmpty()) {
                // One tap logs it. Repeating a meal is the common case by a
                // wide margin and should not cost a form, which is also why
                // these are below the fields rather than above: the fields are
                // for the meal you have not had before.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    savedMeals.take(MAX_SAVED_CHIPS).forEach { meal ->
                        AssistChip(
                            onClick = { onLogMeal(MealLogCreate(mealId = meal.id, name = meal.name)) },
                            label = { Text(meal.name) },
                        )
                    }
                }
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
                        onLogMeal(
                            MealLogCreate(
                                name = mealName.trim(),
                                calories = mealCalories.toIntOrNull(),
                            )
                        )
                    }
                    weight = ""
                    water = ""
                    mealName = ""
                    mealCalories = ""
                },
                // Nothing typed means nothing to send; an empty patch is a
                // request that costs a round trip to change nothing.
                enabled = pending > 0,
                modifier = Modifier.fillMaxWidth(),
            )

            if (mealsToday.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                mealsToday.forEach { entry ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            entry.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${entry.calories} kcal",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        IconButton(onClick = { onDeleteMeal(entry.id) }) {
                            Icon(Icons.Default.Close, contentDescription = "Delete ${entry.name}")
                        }
                    }
                }
            }
        }
    }
}

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
