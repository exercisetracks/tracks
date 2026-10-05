// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.strength

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracks.app.ui.body.BodyDiagram
import com.tracks.app.ui.body.BodyView
import com.tracks.app.ui.components.ButtonRow
import com.tracks.app.ui.components.DangerButton
import com.tracks.app.ui.components.NeutralButton
import com.tracks.app.ui.components.PrimaryButton
import com.tracks.app.ui.components.TonalButton
import com.tracks.app.ui.theme.Tokens
import com.tracks.app.ui.theme.statValue
import com.tracks.core.api.Exercise
import com.tracks.core.format.Units
import com.tracks.core.format.displayToKg
import com.tracks.core.format.kgToDisplay
import com.tracks.core.format.weight
import com.tracks.core.format.weightUnit
import com.tracks.core.spec.muscleLabel

/**
 * The guided session: one exercise at a time, a set at a time.
 *
 * The whole screen is built around the two things a hand can do while holding a
 * bar: tick a set off, and read the number that says when to start the next one.
 * So the current set is the biggest thing on screen with steppers a thumb can
 * hit, the rest countdown takes over the top when it runs, and everything else
 * — another exercise, a substitute, the session so far — is one tap away but
 * never in the way.
 *
 * The rest timer starts itself when a set is ticked, and the phone buzzes when
 * it ends. A timer with its own start button measures the time between
 * finishing a set and remembering the timer, which is not the number anyone
 * wants; and a lifter across the room is not watching the screen.
 *
 * The screen is kept awake for the duration. A phone that locks between sets
 * means unlocking it with chalk on your hands to tick a box, and the session
 * quietly stops being logged after the second exercise. The session itself is
 * written to disk on every change (see [SessionLogic]), so the process being
 * reclaimed mid-workout loses nothing.
 */
@Composable
fun SessionRunner(vm: StrengthViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val session = state.session ?: return
    KeepScreenOn()

    when {
        session.saved -> Logged(onDone = vm::closeSession, modifier = modifier)
        session.reviewing -> SessionSummary(
            summary = vm.summary() ?: return,
            saving = session.saving,
            error = session.error,
            onBack = { vm.review(false) },
            onSave = vm::finishSession,
            modifier = modifier,
        )
        else -> SessionRunnerContent(
            session = session,
            substitutes = vm::substitutes,
            actions = RunnerActions(
                select = vm::selectSessionExercise,
                editSet = vm::editSet,
                toggleSet = vm::toggleSetDone,
                addSet = vm::addSet,
                removeSet = vm::removeSet,
                restSeconds = vm::setRestSeconds,
                skipRest = vm::skipRest,
                extendRest = vm::extendRest,
                skipExercise = vm::skipExercise,
                substitute = vm::substitute,
                pause = vm::closeSession,
                discard = vm::discardSession,
                finish = { vm.review(true) },
            ),
            modifier = modifier,
        )
    }
}

/** Everything the runner can ask of its ViewModel — one object so a screenshot test can pass no-ops. */
class RunnerActions(
    val select: (Int) -> Unit = {},
    val editSet: (Int, Double?, Int?) -> Unit = { _, _, _ -> },
    val toggleSet: (Int) -> Unit = {},
    val addSet: () -> Unit = {},
    val removeSet: () -> Unit = {},
    val restSeconds: (Int) -> Unit = {},
    val skipRest: () -> Unit = {},
    val extendRest: (Int) -> Unit = {},
    val skipExercise: () -> Unit = {},
    val substitute: (Exercise) -> Unit = {},
    val pause: () -> Unit = {},
    val discard: () -> Unit = {},
    val finish: () -> Unit = {},
)

@Composable
fun SessionRunnerContent(
    session: SessionState,
    substitutes: () -> List<Exercise>,
    actions: RunnerActions,
    modifier: Modifier = Modifier,
) {
    var choosing by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val exercise = session.exercise ?: return
    val nextIndex = SessionLogic.nextUnfinished(session.exercises, session.current)

    Column(modifier.fillMaxSize()) {
        Progress(session, actions.select)

        session.restRemaining?.let { left ->
            RestBanner(left, onSkip = actions.skipRest, onMore = { actions.extendRest(REST_STEP_SECONDS) })
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Tokens.Space.s4),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.s3),
        ) {
            ExerciseHeader(exercise.exercise)

            exercise.sets.forEachIndexed { index, set ->
                SetRow(
                    number = index + 1,
                    set = set,
                    // The first set not yet done is the one being lifted; it is
                    // drawn larger so the eye lands there between sets.
                    active = index == exercise.sets.indexOfFirst { !it.done },
                    onWeight = { actions.editSet(index, it, null) },
                    onReps = { actions.editSet(index, null, it) },
                    onToggle = { actions.toggleSet(index) },
                )
            }
            ButtonRow {
                TonalButton("Add set", onClick = actions.addSet, small = true)
                if (exercise.sets.size > 1) DangerButton("Remove set", onClick = actions.removeSet, small = true)
                TonalButton("Substitute", onClick = { choosing = true }, small = true)
            }

            if (session.restRemaining == null) {
                RestSetting(exercise.restSeconds, actions.restSeconds)
            }

            nextIndex?.let { i ->
                UpNext(session.exercises[i], onClick = actions.skipExercise)
            }
            Spacer(Modifier.height(Tokens.Space.s2))
        }

        Surface(tonalElevation = 3.dp) {
            Row(
                Modifier.fillMaxWidth().padding(Tokens.Space.s4),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // "Pause", not "Cancel": the session is kept on disk and the
                // strength page offers to resume it. Discarding is a separate,
                // confirmed choice, because it is the one that loses work.
                NeutralButton("Pause", onClick = actions.pause)
                DangerButton("Discard", onClick = { confirmDiscard = true })
                PrimaryButton("Finish", onClick = actions.finish, modifier = Modifier.weight(1f), enabled = session.hasWork)
            }
        }
    }

    if (choosing) {
        SubstituteDialog(
            options = remember(exercise.exercise.name) { substitutes() },
            onPick = { actions.substitute(it); choosing = false },
            onDismiss = { choosing = false },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this session?") },
            text = { Text("Sets ticked so far will not be logged.") },
            confirmButton = { DangerButton("Discard", onClick = { confirmDiscard = false; actions.discard() }) },
            dismissButton = { NeutralButton("Keep going", onClick = { confirmDiscard = false }) },
        )
    }
}

/**
 * Which exercise is showing, and how far through each one is.
 *
 * A row of chips rather than next/previous buttons: a session is not
 * necessarily done in order — a busy squat rack sends people to the accessory
 * work first — and a chip that fills in when its sets are done doubles as the
 * progress bar for the session.
 */
@Composable
private fun Progress(session: SessionState, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Tokens.Space.s4, vertical = Tokens.Space.s2),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s2),
    ) {
        session.exercises.forEachIndexed { index, ex ->
            val done = ex.sets.count { it.done }
            FilterChip(
                selected = index == session.current,
                onClick = { onSelect(index) },
                label = { Text("${ex.exercise.name}  $done/${ex.sets.size}") },
                leadingIcon = if (ex.allDone) {
                    { Surface(Modifier.size(8.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {} }
                } else null,
            )
        }
    }
}

/**
 * The exercise, and what it works.
 *
 * The body diagram is the same artwork the web draws, lit by the exercise's
 * primary muscles (full) and secondary ones (half) — a glance tells you which
 * way round to set up, which the name alone often does not.
 */
@Composable
private fun ExerciseHeader(exercise: Exercise) {
    val activation = remember(exercise.name) {
        exercise.secondaryMuscles.associateWith { 0.5f } + exercise.primaryMuscles.associateWith { 1f }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s3)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s1)) {
            Text(exercise.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            val muscles = exercise.primaryMuscles.map(::muscleLabel)
            if (muscles.isNotEmpty()) {
                Text(
                    muscles.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            exercise.cues.firstOrNull()?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row {
            BodyDiagram(activation, BodyView.Front, Modifier.width(44.dp).height(96.dp))
            BodyDiagram(activation, BodyView.Back, Modifier.width(44.dp).height(96.dp))
        }
    }
}

/**
 * One set, with steppers.
 *
 * Steppers rather than text fields: between sets the change is almost always
 * one plate or one rep, and a stepper is one tap where a keyboard is four. The
 * weight is shown and stepped in the account's unit (2.5 kg or 5 lb), and
 * stored in kilograms as the server stores it.
 */
@Composable
private fun SetRow(
    number: Int,
    set: SessionSet,
    active: Boolean,
    onWeight: (Double) -> Unit,
    onReps: (Int) -> Unit,
    onToggle: () -> Unit,
) {
    val imperial = Units.imperial
    val shown = kgToDisplay(set.weightKg, imperial)
    val step = if (imperial) 5.0 else 2.5
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Tokens.Radius.xl),
        colors = CardDefaults.cardColors(
            containerColor = when {
                set.done -> MaterialTheme.colorScheme.primaryContainer
                active -> MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Row(
            Modifier.padding(horizontal = Tokens.Space.s3, vertical = if (active) Tokens.Space.s3 else Tokens.Space.s2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s2),
        ) {
            Text("$number", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Stepper(
                text = if (set.weightKg <= 0) "BW" else weight(set.weightKg, imperial),
                onMinus = { onWeight(displayToKg((shown - step).coerceAtLeast(0.0), imperial)) },
                onPlus = { onWeight(displayToKg(shown + step, imperial)) },
                modifier = Modifier.weight(1.3f),
            )
            Stepper(
                text = "${set.reps} reps",
                onMinus = { onReps((set.reps - 1).coerceAtLeast(0)) },
                onPlus = { onReps(set.reps + 1) },
                modifier = Modifier.weight(1f),
            )
            FilledIconButton(onClick = onToggle, modifier = Modifier.size(if (active) 52.dp else 44.dp)) {
                Text(if (set.done) "✓" else "○", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun Stepper(text: String, onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier) {
    // Tinted from the accent, not Material's secondary: the theme leaves
    // secondary at its default lavender, which is no colour Tracks uses.
    val colors = androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(
        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        contentColor = MaterialTheme.colorScheme.primary,
    )
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = onMinus, modifier = Modifier.size(34.dp), colors = colors) { Text("−") }
        Text(
            text,
            style = statValue,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        FilledTonalIconButton(onClick = onPlus, modifier = Modifier.size(34.dp), colors = colors) { Text("+") }
    }
}

/**
 * The rest countdown, across the top while it runs.
 *
 * It replaces the rest setting rather than sitting beside it — mid-rest there is
 * exactly one question, and two large numbers next to each other is how you
 * answer the wrong one.
 */
@Composable
private fun RestBanner(remaining: Int, onSkip: () -> Unit, onMore: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = Tokens.Space.s4, vertical = Tokens.Space.s3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s3),
        ) {
            Column(Modifier.weight(1f)) {
                Text("REST", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    clock(remaining),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            TonalButton("+${REST_STEP_SECONDS}s", onClick = onMore)
            PrimaryButton("Go", onClick = onSkip)
        }
    }
}

@Composable
private fun RestSetting(seconds: Int, onSeconds: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Rest between sets ${clock(seconds)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TonalButton("−15s", onClick = { onSeconds(seconds - REST_STEP_SECONDS) }, small = true)
        TonalButton("+15s", onClick = { onSeconds(seconds + REST_STEP_SECONDS) }, small = true)
    }
}

/** A peek at what comes next — tapping it goes there, leaving this exercise as it is. */
@Composable
private fun UpNext(next: SessionExercise, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(Tokens.Radius.xl),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(Tokens.Space.s3), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("UP NEXT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(next.exercise.name, style = MaterialTheme.typography.titleMedium)
                val first = next.sets.firstOrNull()
                if (first != null) {
                    Text(
                        "${next.sets.size} × ${first.reps} · ${weight(first.weightKg)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text("Skip to →", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SubstituteDialog(options: List<Exercise>, onPick: (Exercise) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Swap for…") },
        text = {
            if (options.isEmpty()) {
                Text("Nothing in the library works the same muscles with your equipment.")
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    options.forEach { e ->
                        Column(
                            Modifier.fillMaxWidth().clickable { onPick(e) }.padding(vertical = Tokens.Space.s2),
                        ) {
                            Text(e.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                e.primaryMuscles.joinToString(" · ", transform = ::muscleLabel),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { NeutralButton("Cancel", onClick = onDismiss) },
    )
}

/**
 * What the session added up to, and how hard it was — shown before it is
 * logged, so a mis-ticked set can still be fixed by going back.
 */
@Composable
fun SessionSummary(
    summary: SessionLogic.Summary,
    saving: Boolean,
    error: String?,
    onBack: () -> Unit,
    onSave: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var rpe by remember { mutableStateOf<Int?>(null) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Tokens.Space.s4),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.s4),
    ) {
        Text("Session summary", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Card(shape = RoundedCornerShape(Tokens.Radius.xl), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(Tokens.Space.s4), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("${summary.setsDone}", "sets")
                Stat("${summary.exercisesDone}", "exercises")
                Stat(weight(summary.volumeKg).removeSuffix(" ${weightUnit(Units.imperial)}"), "${weightUnit(Units.imperial)} lifted")
                Stat("${summary.minutes}", "min")
            }
        }
        RpeRow(rpe = rpe, onRpe = { rpe = it })
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s3)) {
            NeutralButton("Back to sets", onClick = onBack, modifier = Modifier.weight(1f))
            PrimaryButton(if (saving) "Saving…" else "Log session", onClick = { onSave(rpe) }, modifier = Modifier.weight(1f), enabled = !saving)
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = statValue)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Logged(onDone: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Tokens.Space.s4)) {
            Text("Session logged", style = MaterialTheme.typography.headlineSmall)
            Text(
                // Said plainly, because it is the reason logging matters: every
                // lift in this session just moved.
                "Your maxes and progression have been updated.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrimaryButton("Done", onClick = onDone)
        }
    }
}

/**
 * How hard the whole session was, 1-10. Optional; tapping the chosen value
 * again clears it, because an optional field that cannot be un-answered is not
 * optional.
 */
@Composable
private fun RpeRow(rpe: Int?, onRpe: (Int?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s1_5)) {
        Text("SESSION EFFORT", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s1_5)) {
            (1..10).forEach { value ->
                FilterChip(selected = rpe == value, onClick = { onRpe(if (rpe == value) null else value) }, label = { Text("$value") })
            }
        }
    }
}

/** Keeps the display on while this composable is in the tree. */
@Composable
internal fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

internal fun clock(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

/** The increment a rest control moves in — the granularity anyone actually rests at. */
private const val REST_STEP_SECONDS = 15
