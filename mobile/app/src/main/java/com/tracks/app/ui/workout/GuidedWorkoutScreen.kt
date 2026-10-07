// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.workout

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracks.app.ui.components.EmptyState
import com.tracks.app.ui.components.NeutralButton
import com.tracks.app.ui.components.PrimaryButton
import com.tracks.app.ui.components.TonalButton
import com.tracks.app.ui.theme.Tokens

/**
 * A planned workout, run step by step.
 *
 * ## Why this replaced "Record a run"
 *
 * The old screen recorded *a* run — a stopwatch with GPS behind it, unattached
 * to anything the plan said. It was the right thing to build first and the
 * wrong thing to keep: the plan already knows what today's session is, down to
 * the interval, and a phone that can hold a training plan and a stopwatch but
 * cannot put them in the same screen is asking the user to be the watch.
 *
 * So the entry point moved. You do not start a run, you start *the workout* —
 * from the dashboard or the calendar, where it already says what it is — and
 * what you get is whichever of the two shapes below fits it. A run is recorded;
 * everything else is guided. See [GuidedUiState.isRecorded].
 *
 * The screen stays awake for the whole session, for the same reason the
 * strength runner does: a phone that locks between sets is a phone you unlock
 * with chalk on your hands, and after the second time nobody bothers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuidedWorkoutScreen(
    vm: GuidedWorkoutViewModel,
    onBack: () -> Unit,
    /** Opens another workout — the one already in progress, from [OtherSessionPane]. */
    onOpenWorkout: (Int) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val active by ActiveWorkout.current.collectAsStateWithLifecycle()

    KeepScreenOn(active = state.begun && !state.saved)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.workout?.title?.takeIf { it.isNotBlank() } ?: "Workout",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }

                state.workout == null -> EmptyState(
                    title = "This workout is not on the plan",
                    // The plan is fetched for a fixed horizon, so a workout can
                    // legitimately be missing rather than the request failing.
                    body = "It may have been moved or removed. Open the training " +
                        "plan to see what is scheduled.",
                    modifier = Modifier.fillMaxSize(),
                )

                state.saved -> SavedPane(state, vm, onBack)
                // One session at a time. The phone has one GPS recording and
                // one bar saying what is running, and a second workout begun
                // on top of the first would steer the first one's recording.
                active != null && active?.key != vm.key -> OtherSessionPane(active!!, onOpenWorkout)
                state.isRecorded -> GuidedRunPane(state, vm, onQuit = onBack)
                else -> StepPane(state, vm, onQuit = onBack)
            }
        }
    }
}

/**
 * The step runner: one instruction at a time, and what is coming after it.
 *
 * The whole pane is arranged around being read at arm's length by someone
 * halfway through a set — one thing large, everything else quiet, and the
 * controls where a thumb already is.
 */
@Composable
private fun StepPane(state: GuidedUiState, vm: GuidedWorkoutViewModel, onQuit: () -> Unit) {
    val step = state.step
    if (step == null || state.done) {
        FinishPane(state, vm)
        return
    }

    Column(Modifier.fillMaxSize()) {
        Progress(state)

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            KindBadge(step.kind)

            Text(
                step.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            step.position?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            step.detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (step.seconds != null) {
                Text(
                    countdown(state.remaining),
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            } else if (state.begun) {
                // A set has no clock — see [expandStrength]. Saying so beats an
                // empty space where every other step has a number.
                Text(
                    "Press Done when the set is finished.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            step.note?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            step.cues.forEach { cue ->
                Text(
                    "· $cue",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.next?.let { next ->
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Tokens.Radius.xl),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "UP NEXT",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            listOfNotNull(next.title, next.position, next.detail)
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        Controls(state, vm)

        // The same "delete and quit" as a paused run, and on the same terms:
        // only while paused, and only on the second tap.
        if (state.begun && !state.running) {
            Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                ConfirmingDangerButton("Delete progress and quit") {
                    vm.discard()
                    onQuit()
                }
            }
        }
    }
}

/**
 * A workout opened while a different one is in progress.
 *
 * Says so and offers the way back, rather than offering to start this one: the
 * other is still recording or counting down, and there is one of each of those.
 */
@Composable
private fun OtherSessionPane(active: ActiveWorkout.Session, onOpenWorkout: (Int) -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "${active.title} is in progress",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                "Finish or delete it before starting another workout.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            PrimaryButton("Back to it", onClick = { onOpenWorkout(active.workoutId) })
        }
    }
}

/** Where the session is, as a bar and a count. */
@Composable
private fun Progress(state: GuidedUiState) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "Step ${state.index + 1} of ${state.steps.size}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = {
                if (state.steps.isEmpty()) 0f
                else (state.index + 1).toFloat() / state.steps.size
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun KindBadge(kind: StepKind) {
    val (label, color) = when (kind) {
        StepKind.Warmup -> "Warm-up" to MaterialTheme.colorScheme.tertiary
        StepKind.Work -> "Work" to MaterialTheme.colorScheme.primary
        StepKind.Rest -> "Recovery" to MaterialTheme.colorScheme.secondary
        StepKind.Cooldown -> "Cool-down" to MaterialTheme.colorScheme.tertiary
    }
    Surface(shape = RoundedCornerShape(Tokens.Radius.md), color = color.copy(alpha = 0.16f)) {
        Text(
            label.uppercase(),
            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

/**
 * Start, then three buttons that do not move.
 *
 * Back, pause and forward stay in the same places for the whole session, so the
 * one that gets pressed without looking is always the same one. The forward
 * button changes its word rather than its position: a timed step is skipped, a
 * set is done.
 */
@Composable
private fun Controls(state: GuidedUiState, vm: GuidedWorkoutViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!state.begun) {
            PrimaryButton("Start workout", onClick = vm::begin, modifier = Modifier.fillMaxWidth())
            return@Row
        }

        NeutralButton("Back", onClick = vm::back, modifier = Modifier.weight(1f), enabled = state.index > 0)

        TonalButton(if (state.running) "Pause" else "Resume", onClick = vm::togglePause, modifier = Modifier.weight(1f))

        PrimaryButton(if (state.step?.isOpen == true) "Done" else "Skip", onClick = vm::skip, modifier = Modifier.weight(1f))
    }
}

/**
 * The end of a guided session.
 *
 * RPE is asked for once, here, and is optional. It is the one number the server
 * cannot derive from a session with no heart rate behind it, and the one people
 * will actually answer — at the end, in one tap.
 */
@Composable
private fun FinishPane(state: GuidedUiState, vm: GuidedWorkoutViewModel) {
    var rpe by remember { mutableStateOf<Int?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Workout complete",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "How hard was it?",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            (1..10).forEach { value ->
                FilterChip(
                    selected = rpe == value,
                    onClick = { rpe = if (rpe == value) null else value },
                    label = { Text("$value") },
                )
            }
        }

        state.error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        PrimaryButton(if (state.saving) "Saving…" else "Log this session", onClick = { vm.finishSession(rpe) }, modifier = Modifier.fillMaxWidth(), enabled = !state.saving)
    }
}

/**
 * What happened to the session, once it has been dealt with.
 *
 * "Queued" is reported as a success, because it is one: the write is on the
 * phone in a form that survives the process, and saying "failed" next to a
 * retry button would invite someone to log the same session twice.
 */
@Composable
private fun SavedPane(state: GuidedUiState, vm: GuidedWorkoutViewModel, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                if (state.isRecorded) "Run saved" else "Session logged",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                when {
                    state.error != null -> state.error
                    state.queued -> "Waiting for a connection — it will sync by itself."
                    state.isRecorded -> "Uploaded, and the workout is ticked off your plan."
                    else -> "Your plan and your progression have been updated."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            PrimaryButton("Done", onClick = {
                    if (state.isRecorded) vm.clearRun()
                    onBack()
                })
        }
    }
}

/**
 * Keep the screen on while a session is running.
 *
 * Only while it is: a workout left open on the finished card should let the
 * phone sleep like anything else.
 */
@Composable
internal fun KeepScreenOn(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, active) {
        view.keepScreenOn = active
        onDispose { view.keepScreenOn = false }
    }
}

/**
 * "2:30", or "0:08" — a countdown, so never longer than it has to be.
 *
 * Deliberately not [com.tracks.app.run.clock], which formats a *recording's*
 * elapsed milliseconds. Two functions because they take different things and
 * mean different things; sharing one would mean converting a countdown to
 * milliseconds to print it.
 */
internal fun countdown(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val rest = safe % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${rest.toString().padStart(2, '0')}"
    } else {
        "$minutes:${rest.toString().padStart(2, '0')}"
    }
}
