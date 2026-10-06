// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracks.app.ui.components.BarPill
import com.tracks.app.ui.components.Explain
import com.tracks.app.ui.components.PendingBanner
import com.tracks.core.format.Units
import com.tracks.core.format.kgToDisplay
import com.tracks.core.format.weightUnit
import kotlin.math.roundToInt

/**
 * Body data, and the things only the athlete can record.
 *
 * The measured half — resting heart rate, HRV, sleep, Body Battery, steps —
 * comes off the watch and is drawn as trends. The entered half — medications,
 * meals, injuries, today's weight — is the reason this screen has buttons.
 *
 * ## Dials, not a column of line charts
 *
 * Every metric used to be its own titled card with a sparkline in it: eleven
 * near-identical boxes, two levels of heading saying nearly the same thing, and
 * — for anyone who has not memorised the ranges — no answer to the only
 * question being asked, which is whether the number is all right. The readings
 * are dials against their scales now, grouped three to a row, and the shape
 * over time is one tap behind each of them. See [MetricMeterGroup].
 */
@Composable
fun HealthScreen(vm: HealthViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    var logging by remember { mutableStateOf(false) }

    // Nothing at all — not even an empty shell — until the first load lands, so
    // the screen does not flash a "no data" message at someone who has plenty.
    if (state.loading && state.days.isEmpty() && state.injuries.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    // The watch's half of the page — activity and vitals — only exists with a
    // watch, or with readings one left behind. Without either, the page is
    // the half anyone can fill in: weight, water, meals, medications and
    // injuries. It used to be an empty state saying "Nothing logged yet" with
    // no way to log anything, because nothing had been logged — the page
    // blank on exactly the phones that most needed its buttons.
    val hasDevice = com.tracks.app.ui.components.LocalHasDevice.current
    val showMeasured = hasDevice || state.days.any { it.hasMeasurements() }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PendingBanner(state.pending)

        if (showMeasured) {
            // Activity first. It is the one group that changes hour to hour, and
            // it is what somebody opening this screen at lunchtime came to see.
            MetricMeterGroup(
                "Activity",
                windowStart = state.range.startDate(),
                metrics = listOf(
                    HealthMetric(
                        label = "Steps",
                        trend = state.series { it.steps?.toDouble() },
                        unit = "",
                        scale = MetricScale.Goal(STEP_GOAL, STEP_COLOR),
                        info = Explain.Steps,
                    ),
                    caloriesMetric(state),
                    HealthMetric(
                        label = "Body Battery",
                        trend = state.series { it.bodyBatteryLast?.toDouble() },
                        unit = "",
                        scale = MetricScale.Bands(BODY_BATTERY_ZONES),
                        info = Explain.BodyBattery,
                        // The day's shape, kept behind the dial rather than in a
                        // card of its own. Charge and drain do not reduce to high
                        // minus low — a day can rise and fall several times — so
                        // they are worth carrying even though the level is what
                        // the dial shows.
                        breakdown = listOfNotNull(
                            state.latestOf { it.bodyBatteryHigh?.toDouble() }?.let { "High" to it },
                            state.latestOf { it.bodyBatteryLow?.toDouble() }?.let { "Low" to it },
                            state.latestOf { it.bodyBatteryCharged?.toDouble() }?.let { "Charged" to it },
                            state.latestOf { it.bodyBatteryDrained?.toDouble() }?.let { "Drained" to it },
                        ),
                    ),
                ),
                missingHint = "These come from the watch's daily monitoring files, " +
                    "which sync separately from activities.",
            )

            MetricMeterGroup(
                "Vitals",
                windowStart = state.range.startDate(),
                metrics = listOf(
                    sleepMetric(state, vm::selectNight),
                    HealthMetric(
                        label = "Resting HR",
                        longLabel = "Resting heart rate",
                        trend = state.series { it.restingHr },
                        unit = "bpm",
                        scale = MetricScale.Bands(RESTING_HR_ZONES),
                        info = Explain.RestingHr,
                    ),
                    HealthMetric(
                        label = "HRV",
                        longLabel = "Heart rate variability",
                        trend = state.series { it.hrv },
                        unit = "ms",
                        // Personal, deliberately. A "good" HRV is whatever is
                        // normal for you — which is exactly how the watch's own
                        // HRV status works — so the dial compares you to yourself.
                        scale = MetricScale.Personal(HRV_COLOR),
                        info = Explain.Hrv,
                    ),
                    HealthMetric(
                        label = "SpO₂",
                        longLabel = "Blood oxygen",
                        trend = state.series { it.spo2 },
                        unit = "%",
                        scale = MetricScale.Bands(SPO2_ZONES),
                        info = Explain.Spo2,
                    ),
                    HealthMetric(
                        label = "Respiration",
                        trend = state.series { it.avgRespirationRate },
                        unit = "br/min",
                        scale = MetricScale.Bands(RESPIRATION_ZONES),
                        info = Explain.Respiration,
                    ),
                    HealthMetric(
                        label = "Stress",
                        trend = state.series { it.avgStressLevel },
                        unit = "",
                        scale = MetricScale.Bands(STRESS_ZONES),
                        info = Explain.Stress,
                        // Every reading the watch took, not one dot per day. A
                        // day's average is a single number for something that
                        // moves all day, and two very different days average to
                        // the same figure — see [StressHistoryPanel], which also
                        // explains why the long windows go back to the averages.
                        chart = {
                            StressHistoryPanel(
                                days = state.stress,
                                averages = state.series { it.avgStressLevel },
                                windowStart = state.range.startDate(),
                            )
                        },
                    ),
                ),
                missingHint = "Worn overnight, the watch records these while you sleep.",
            )
        }

        MetricMeterGroup(
            "Body",
            windowStart = state.range.startDate(),
            metrics = listOf(
                HealthMetric(
                    label = "Weight",
                    trend = state.series { day -> day.weightKg?.let { kgToDisplay(it) } },
                    unit = weightUnit(Units.imperial),
                    // No bands, and there will not be any. A dial that told
                    // somebody their body mass was red would be both medically
                    // worthless and a nasty thing to open a health page to.
                    scale = MetricScale.Personal(WEIGHT_COLOR),
                    info = Explain.Weight,
                    decimals = 1,
                    // The one reading here that does not expire. A body mass
                    // is a standing fact rather than an event, and blanking
                    // the dial because nobody stood on the scales this morning
                    // would be pedantry rather than honesty.
                    freshDays = null,
                ),
                HealthMetric(
                    label = "Water",
                    longLabel = "Hydration",
                    trend = state.series { it.hydrationMl },
                    unit = "ml",
                    scale = MetricScale.Goal(HYDRATION_GOAL_ML, HYDRATION_COLOR),
                    info = Explain.Hydration,
                ),
                HealthMetric(
                    label = "Eaten",
                    longLabel = "Calories eaten",
                    // Food logged, added up per day — see [HealthUiState.eaten].
                    trend = state.eaten,
                    unit = "kcal",
                    scale = MetricScale.Personal(CALORIE_COLOR),
                    info = Explain.CaloriesIn,
                ),
            ),
            missingHint = "Nothing here is measured — log a day and the dials " +
                "start from there.",
            // The one button for everything a watch cannot know, inside the
            // card whose dials it fills in. It replaced a permanently-expanded
            // entry card and a nutrition card that between them took a third of
            // the page to be used for ten seconds a day.
            footer = { LogButton("Log today", onClick = { logging = true }) },
        )

        MedicationsSection(
            medications = state.medications,
            doses = state.dosesToday,
            history = state.medicationLog,
            onLog = vm::logDose,
            onSave = vm::saveMedication,
            onDelete = vm::deleteMedication,
        )

        InjuriesSection(
            injuries = state.injuries,
            onLog = vm::logInjury,
            onUpdate = vm::updateInjury,
            onHeal = vm::healInjury,
            onDelete = { vm.deleteInjury(it) },
            loadActivities = vm::activitiesAround,
        )
    }

    if (logging) {
        HealthLogSheet(
            weightKg = state.latestOf { it.weightKg },
            hydrationMl = state.days.lastOrNull { it.date == state.today }?.hydrationMl,
            savedMeals = state.meals,
            mealsToday = state.mealsToday,
            caloriesToday = state.caloriesToday,
            onSaveDay = vm::patchToday,
            onLogSaved = vm::logMeal,
            onLogFood = vm::logFood,
            onSaveMeal = vm::saveMeal,
            onDeleteSavedMeal = vm::deleteSavedMeal,
            onDeleteEntry = vm::deleteMealLog,
            onDismiss = { logging = false },
        )
    }
}

/**
 * Calories burned: what the body spent existing, plus what you did on top.
 *
 * One dial, two colours, because that is what the number is. Garmin's own
 * "calories" is the total — a person who walked 3,000 steps did not burn 220
 * calories that day, they burned about 2,700 — and showing only the active part
 * made the app disagree with the watch face about the same day.
 *
 * The base is always filled, since resting burn is not an achievement, and the
 * scale runs to resting plus a day's active goal so the arc measures the part
 * that is actually up to you.
 */
@Composable
private fun caloriesMetric(state: HealthUiState): HealthMetric {
    val active = state.series { it.activeCalories?.toDouble() }
    // The *current* figures, so the arc's two colours describe the same day
    // the dial's number does. A day old enough to blank the figure would
    // otherwise leave last week's split painted on the ring under an em dash.
    val resting = state.series { it.restingCalories?.toDouble() }.current(FRESH_DAYS)
    val latestActive = active.current(FRESH_DAYS)

    // Totals per day, so the history sheet plots the same number the dial does.
    val totals = state.series { day ->
        val a = day.activeCalories?.toDouble()
        val r = day.restingCalories?.toDouble()
        if (a == null && r == null) null else (a ?: 0.0) + (r ?: 0.0)
    }

    return HealthMetric(
        label = "Calories",
        longLabel = "Calories burned",
        trend = totals,
        unit = "kcal",
        scale = if (resting != null) {
            MetricScale.Stacked(
                parts = listOf(
                    MetricScale.Stacked.Part("Just existing", resting, RESTING_COLOR),
                    MetricScale.Stacked.Part("Earned", latestActive ?: 0.0, ACTIVE_COLOR),
                ),
                // Resting is a given, so the arc's remaining stretch is exactly
                // the active goal: the gap you can still close today.
                target = resting + ACTIVE_CALORIE_GOAL,
                remainderColor = ACTIVE_COLOR,
                remainderLabel = "Still to earn",
            )
        } else {
            MetricScale.Goal(ACTIVE_CALORIE_GOAL, ACTIVE_COLOR)
        },
        // The arc's own bands are the two halves of the total, which cannot say
        // whether the day was a big one — that is what these are for.
        verdict = activityZones(resting ?: 0.0),
        info = Explain.CaloriesBurned,
        caption = latestActive?.let { "+${it.roundToInt()} active" },
    )
}

/**
 * Last night's sleep: the total on the dial, the stages in the arc.
 *
 * The ring used to be the duration bands — red under five hours, teal at eight
 * — which answered "was that enough" and nothing else. Stacking the stages
 * answers the other half in the same space: six and a half hours made of a
 * solid block of deep sleep is not the same night as six and a half hours of
 * almost entirely light, and until now the only way to see that was to open the
 * hypnogram.
 *
 * The verdict is not lost, it moves. [HealthMetric.verdict] keeps the duration
 * bands for the word under the figure, so the dial still says "Good" — it is
 * simply no longer the thing the colours are spent on.
 *
 * Waking is deliberately not a band. It is not sleep, the total in the middle
 * would stop matching the arc if it were counted, and the hypnogram behind the
 * dial shows every break in the night anyway.
 */
@Composable
private fun sleepMetric(state: HealthUiState, onSelectNight: (String) -> Unit): HealthMetric {
    // Last night, and only while it *is* last night. The stages painted on
    // the arc have to describe the same night the figure in the middle does,
    // and a week-old night left on the ring under an em dash would be the
    // stale-reading problem wearing a different shape.
    val last = state.nights.lastOrNull()?.takeIf { isFresh(it.date, FRESH_DAYS) }
    val stages = listOfNotNull(
        last?.let { MetricScale.Stacked.Part("Deep", it.deep, DEEP) },
        last?.let { MetricScale.Stacked.Part("REM", it.rem, REM) },
        last?.let { MetricScale.Stacked.Part("Light", it.light, LIGHT) },
        // Nights the watch timed but did not stage — and every night recorded
        // before the parser kept stages. Without this the arc would be empty
        // under a perfectly good total.
        last?.let { night ->
            (night.total - night.deep - night.rem - night.light)
                .takeIf { it > 0.05 }
                ?.let { MetricScale.Stacked.Part("Asleep", it, SLEEP_COLOR) }
        },
    )

    return HealthMetric(
        label = "Sleep",
        longLabel = "Sleep",
        // Zero-length nights filtered out, exactly as [HealthUiState.nights]
        // does — otherwise a day carrying a 0.0 could make the dial's figure
        // and its stacked bands describe two different nights.
        trend = state.series { day -> day.sleepHours?.takeIf { it > 0 } },
        unit = "h",
        scale = MetricScale.Stacked(stages, target = SLEEP_GOAL_HOURS),
        verdict = SLEEP_ZONES,
        info = Explain.Sleep,
        decimals = 1,
        // The stage chart itself, not a line of total hours. The stages are the
        // reason to look at sleep history, and this is the one place with room
        // to draw them properly.
        chart = {
            SleepHistoryPanel(
                nights = state.nights,
                windowStart = state.range.startDate(),
                selected = state.selectedNight,
                night = state.night,
                onSelect = onSelectNight,
            )
        },
        // The panel opens with the chosen night's totals and its hypnogram,
        // so the sheet's own headline would be the same figure a second time
        // — and the dial the sheet was opened from is a third.
        headline = false,
    )
}

/**
 * How far back the trends look — in the app bar, beside the page title.
 *
 * The same control the dashboard puts there, built from the same [BarPill], and
 * for the same reason: the window governs every chart below it, so it belongs
 * where it is visible without scrolling rather than as the first row of a page
 * that is mostly scrolled past. Two tabs, one place, one shape.
 */
@Composable
fun HealthRangeActions(vm: HealthViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    Row(
        modifier.padding(end = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HealthRange.entries.forEach { range ->
            BarPill(
                text = range.short,
                selected = range == state.range,
                onClick = { vm.setRange(range) },
            )
        }
    }
}

/** Whether a day holds anything a watch measured, as opposed to only what was logged by hand. */
private fun com.tracks.core.api.DailyMetricFull.hasMeasurements(): Boolean =
    restingHr != null || hrv != null || sleepHours != null || steps != null || activeCalories != null ||
        avgStressLevel != null || avgRespirationRate != null || spo2 != null || bodyBatteryLast != null
