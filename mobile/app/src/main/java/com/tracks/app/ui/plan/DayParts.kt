// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tracks.app.AppContainer
import com.tracks.app.ui.components.InfoTip
import com.tracks.app.ui.components.MetricInfo
import com.tracks.app.ui.components.TonalButton
import com.tracks.app.ui.components.dayLabel
import com.tracks.app.ui.components.shortDay
import com.tracks.app.ui.theme.Tokens
import com.tracks.core.api.PlannedWorkout
import com.tracks.core.api.PlannedWorkoutCreate
import com.tracks.core.api.PlannedWorkoutUpdate
import com.tracks.core.api.TrainingGoal
import com.tracks.core.api.WorkoutStep
import com.tracks.core.format.Units
import com.tracks.core.format.distance
import com.tracks.core.format.weight
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One day, in full, as a titled card of its own — the day sheet the week
 * agenda opens.
 */
@Composable
internal fun DayCard(
    date: LocalDate,
    workouts: List<PlannedWorkout>,
    onToggle: (PlannedWorkout) -> Unit,
    onOpen: (Int) -> Unit,
    onEdit: (PlannedWorkout) -> Unit,
    onAdd: () -> Unit,
    /** Gut-training carbs g/h by workout id; see PlanUiState.gutTargets. */
    gutTargets: Map<Int, Int> = emptyMap(),
    onLogFuel: (PlannedWorkout, Double, Int, String?) -> Unit = { _, _, _, _ -> },
) = Section(dayLabel(date.toString())) {
    DayDetails(workouts, onToggle, onOpen, onEdit, onAdd, gutTargets, onLogFuel)
}

/**
 * A day's workouts and its "Add a workout" — the body of [DayCard] without a
 * card around it.
 *
 * This is the web app's detail panel, moved under the grid rather than beside
 * it — there is no room for a side panel on a phone, and putting it below keeps
 * the grid visible while reading a session. The month view draws it inside the
 * grid's own card: in a card of its own below the grid it read as a separate
 * thing rather than as the day the grid had selected.
 */
@Composable
internal fun DayDetails(
    workouts: List<PlannedWorkout>,
    onToggle: (PlannedWorkout) -> Unit,
    onOpen: (Int) -> Unit,
    onEdit: (PlannedWorkout) -> Unit,
    onAdd: () -> Unit,
    gutTargets: Map<Int, Int> = emptyMap(),
    onLogFuel: (PlannedWorkout, Double, Int, String?) -> Unit = { _, _, _, _ -> },
) = Column {
    var logging by remember { mutableStateOf<PlannedWorkout?>(null) }
    logging?.let { w ->
        FuelLogDialog(
            workout = w,
            target = gutTargets[w.id],
            onDismiss = { logging = null },
            onSave = { carbs, comfort, notes -> onLogFuel(w, carbs, comfort, notes); logging = null },
        )
    }
    if (workouts.isEmpty()) {
        Text(
            "Rest day.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }

    workouts.forEachIndexed { index, workout ->
        if (index > 0) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        }
        Column(
            Modifier.padding(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    Modifier.size(8.dp),
                    shape = CircleShape,
                    color = workoutDotColor(workout),
                ) {}
                Column(
                    Modifier
                        .weight(1f)
                        // The name opens the guided session; the checkbox
                        // beside it still just ticks the box. Two targets
                        // rather than one, because "I did this" and "I am
                        // about to do this" are different intentions and
                        // the wrong one is annoying to undo.
                        .clickable { onOpen(workout.id) },
                ) {
                    Text(
                        workout.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        buildString {
                            append(workoutTypeLabel(workout.workoutType))
                            workout.durationMinutes?.let { append(" · $it min") }
                            workout.distanceMeters?.takeIf { it > 0 }?.let {
                                append(" · ${distance(it)}")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // A checkbox, not a button: it is a two-way state, and
                // ticking the wrong day has to be undoable in one tap.
                Checkbox(
                    checked = workout.isComplete,
                    onCheckedChange = { onToggle(workout) },
                )
            }

            workout.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            workout.steps.forEach { step -> StepRow(step) }

            val gut = gutTargets[workout.id]
            if (gut != null) {
                Text(
                    "Fuel practice: $gut g carbs an hour",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TonalButton("Edit", onClick = { onEdit(workout) }, small = true)
                if (gut != null && workout.isComplete) {
                    TonalButton("Log fuelling", onClick = { logging = workout }, small = true)
                }
                if (workout.origin == "user") {
                    Text(
                        "Yours",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
            }
        }
    }

    TonalButton("Add a workout", onClick = onAdd, icon = Icons.Default.Add, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
}

/**
 * One step of a structured workout.
 *
 * Mirrors the web app's `StepRow`, including its rule that an unrecognised step
 * type still renders — by its own name — rather than disappearing. A plan that
 * silently drops a step is worse than one that shows a step it does not fully
 * understand.
 *
 * ## Exercises are steps too
 *
 * This used to label every step by its *type*, which is right for cardio —
 * "Warm-up", "Threshold", "6 × 400 m" are the whole instruction — and useless
 * for the two step types that carry a name. A mobility session came out as
 * "Mobility exercise" five times over: five rows, five identical labels, and
 * the five stretches the generator actually chose nowhere on the screen. Same
 * for a strength day and its lifts.
 *
 * So the exercise families are labelled by [WorkoutStep.name] and detailed by
 * what they prescribe — sets, reps, working weight, the hold and the side —
 * which is the same split the browser makes and the same data the guided runner
 * already walks you through.
 */
@Composable
private fun StepRow(step: WorkoutStep) {
    val label = stepLabel(step)
    val detail = stepDetail(step)
    // The first coaching cue only. The library carries up to three and a plan
    // is read at a glance; the rest are in the guided session, one screen at a
    // time, which is where somebody is actually holding the position.
    val cue = step.cues.firstOrNull()?.takeIf { it.isNotBlank() }
        ?: step.breathCue?.takeIf { it.isNotBlank() }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            Modifier.size(5.dp),
            shape = CircleShape,
            color = stepColor(step),
        ) {}
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (detail.isNotEmpty()) {
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    // Both hang under the name at the same indent: a cue explains the movement,
    // a note explains the prescription, and neither is the step's identity.
    listOfNotNull(cue, step.note?.takeIf { it.isNotBlank() }).forEach {
        Text(
            it,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 31.dp),
        )
    }
}

/**
 * The name a step goes by.
 *
 * Pure, and separate from the composable, because this and [stepDetail] are the
 * part that can be wrong — a cardio step labelled by its exercise name or an
 * exercise labelled by its type both render perfectly and say the wrong thing.
 */
internal fun stepLabel(step: WorkoutStep): String = when (step.type) {
    "warmup" -> "Warm-up"
    "cooldown" -> "Cool-down"
    "walk" -> "Walk"
    "strength_exercise", "mobility_exercise" ->
        step.name?.takeIf { it.isNotBlank() } ?: workoutTypeLabel(step.type)
    "interval_set" -> step.reps?.let { reps ->
        step.distanceM?.let { "$reps × ${it.roundToInt()} m" } ?: "$reps ×"
    } ?: "Intervals"
    "run" -> paceLabel(step.pace)
    else -> workoutTypeLabel(step.type)
}

/**
 * What the step asks for, in whatever it is actually measured in.
 *
 * Sets and reps for a lift, a hold and a side for a stretch, minutes for a
 * block of running. Each clause is skipped when the generator did not fill it
 * in, so a bodyweight movement does not claim a weight and a two-sided stretch
 * does not claim a side.
 *
 * @param imperial defaulted from the account preference, and a parameter so a
 * test can pin it — the same convention as `core.format`.
 */
internal fun stepDetail(
    step: WorkoutStep,
    imperial: Boolean = Units.imperial,
): String = buildString {
    fun part(text: String) {
        if (isNotEmpty()) append(" · ")
        append(text)
    }
    when (step.type) {
        "strength_exercise" -> {
            val sets = step.sets
            val reps = step.reps
            when {
                sets != null && reps != null -> part("$sets × $reps")
                sets != null -> part("$sets sets")
                reps != null -> part("$reps reps")
            }
            // Zero is bodyweight, and `weight()` already says "BW" for it —
            // but only where a weight belongs at all, which is why this asks
            // for a non-null rather than for a positive number.
            step.weightKg?.let { part(weight(it, imperial)) }
            step.tempo?.takeIf { it.isNotBlank() }?.let { part("tempo $it") }
            step.targetRpe?.takeIf { it > 0 }?.let { part("RPE ${trimZero(it)}") }
            step.restSeconds?.takeIf { it > 0 }?.let { part("${it}s rest") }
        }

        "mobility_exercise" -> {
            step.durationSeconds?.takeIf { it > 0 }?.let {
                // A one-sided stretch is two holds, not one held twice as long,
                // and a stretch sheet that omits the side has you doing half
                // the session.
                part(if (step.eachSide) "${it}s each side" else "${it}s")
            }
            step.sets?.takeIf { it > 1 }?.let { part("× $it") }
            step.restSeconds?.takeIf { it > 0 }?.let { part("${it}s rest") }
        }

        "interval_set" -> {
            val each = when {
                step.distanceM?.takeIf { it > 0 } != null -> "${step.distanceM!!.roundToInt()} m"
                step.durationSecEach?.takeIf { it > 0 } != null -> "${step.durationSecEach!!.roundToInt()}s"
                step.durationMinEach?.takeIf { it > 0 } != null -> "${step.durationMinEach!!.roundToInt()} min"
                else -> null
            }
            val reps = step.reps?.takeIf { it > 1 }
            when {
                reps != null && each != null -> part("$reps × $each")
                each != null -> part(each)
                reps != null -> part("$reps reps")
            }
            step.restSec?.takeIf { it > 0 }?.let { part("${it}s recovery") }
            step.restMin?.takeIf { it > 0 }?.let { part("${trimZero(it)} min recovery") }
        }

        "effort_set" -> {
            val each = step.durationMinEach?.takeIf { it > 0 }?.let { "${it.roundToInt()} min" }
                ?: step.durationSecEach?.takeIf { it > 0 }?.let { "${it.roundToInt()}s" }
            val reps = step.reps?.takeIf { it > 1 }
            when {
                reps != null && each != null -> part("$reps × $each")
                each != null -> part(each)
                reps != null -> part("$reps efforts")
            }
            step.restMin?.takeIf { it > 0 }?.let { part("${trimZero(it)} min recovery") }
            step.restSec?.takeIf { it > 0 }?.let { part("${it}s recovery") }
        }

        "fartlek" -> {
            val hard = step.hardMin?.takeIf { it > 0 }
            val easy = step.easyMin?.takeIf { it > 0 }
            if (hard != null && easy != null) {
                part("${trimZero(hard)} hard / ${trimZero(easy)} easy")
            }
            step.reps?.takeIf { it > 1 }?.let { part("× $it") }
        }

        else -> {
            step.durationMin?.takeIf { it > 0 }?.let { part("${it.roundToInt()} min") }
            step.restSec?.takeIf { it > 0 }?.let { part("${it}s recovery") }
        }
    }
}

/** RPE 8 rather than RPE 8.0; the halves the generator does emit survive. */
internal fun trimZero(value: Double): String =
    if (value % 1.0 == 0.0) "${value.toInt()}" else "$value"

/**
 * The bullet's colour, matching the browser's palette for the same step.
 *
 * Cardio steps keep the neutral dot they have always had. The two exercise
 * families get their own so a mixed day — a run with a mobility finisher, a
 * lifting session with a stretch warm-up — reads as two kinds of work rather
 * than one long list.
 */
@Composable
private fun stepColor(step: WorkoutStep) = when (step.type) {
    "strength_exercise" -> androidx.compose.ui.graphics.Color(0xFFA78BFA)
    "mobility_exercise" -> androidx.compose.ui.graphics.Color(0xFF2DD4BF)
    "interval_set", "effort_set" -> androidx.compose.ui.graphics.Color(0xFFF87171)
    else -> MaterialTheme.colorScheme.outline
}

/** Pace-zone names as the plan generator writes them. */
private fun paceLabel(pace: String?): String = when (pace) {
    "easy" -> "Easy"
    "marathon" -> "Marathon pace"
    "threshold" -> "Threshold"
    "interval" -> "Interval"
    "repetition" -> "Rep pace"
    null -> "Run"
    else -> pace.replaceFirstChar { it.uppercase() }
}

/**
 * `YearMonth` and `LocalDate` are not Parcelable, so the grid's position would
 * be lost on a rotation without these. Stored as their ISO text, which is
 * stable and short.
 */
private val YearMonthSaver = androidx.compose.runtime.saveable.Saver<YearMonth, String>(
    save = { it.toString() },
    restore = { runCatching { YearMonth.parse(it) }.getOrDefault(YearMonth.now()) },
)

private val LocalDateSaver = androidx.compose.runtime.saveable.Saver<LocalDate?, String>(
    save = { it?.toString() ?: "" },
    restore = { it.takeIf { s -> s.isNotEmpty() }?.let { s -> runCatching { LocalDate.parse(s) }.getOrNull() } },
)

/** "Today", "Tomorrow", or a weekday — relative reads faster than a date. */

@Composable
internal fun Section(
    title: String,
    info: MetricInfo? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            info?.let { InfoTip(it) }
        }
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Tokens.Radius.xl),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { content() }
        }
    }
}
