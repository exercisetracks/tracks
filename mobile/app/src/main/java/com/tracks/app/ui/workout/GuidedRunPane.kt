// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.workout

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracks.app.run.RunPhase
import com.tracks.app.run.RunRecorder
import com.tracks.app.run.RunService
import com.tracks.app.ui.components.DangerButton
import com.tracks.app.ui.components.NeutralButton
import com.tracks.app.ui.components.PrimaryButton
import com.tracks.app.ui.components.TonalButton
import com.tracks.app.ui.theme.Tokens
import com.tracks.core.format.distance
import kotlinx.coroutines.delay

/**
 * A planned run, while it is being run.
 *
 * ## What is on it, and why those
 *
 * The plan's distance, because it is the only number here the phone did not
 * measure — the server worked out that today is 8 km, and a screen that shows
 * how far you have gone without showing how far you were asked to go is a
 * stopwatch again. Everything else is the recording: distance covered, moving
 * time, pace, speed, climb, and what is left.
 *
 * Pace *and* speed, which is a duplication on purpose. Pace is what a runner
 * thinks in and speed is what a hill is judged by, and neither is derivable at
 * a glance from the other by someone forty minutes into an effort.
 *
 * They are dials rather than a grid of figures — the Health page's instrument,
 * with distance and pace large at the top ([RunGauges]).
 *
 * ## The current section
 *
 * A structured run — a warm-up, six intervals, a cool-down — shows the step it
 * is on as the first of the small dials and counts it down in whatever the step is
 * measured in. The counting is the ViewModel's, from the run's own moving time
 * and distance, so it advances itself: that is the difference between a
 * workout on a screen and a workout on a watch.
 *
 * ## Recording is the same door a watch uses
 *
 * The run is encoded as a FIT activity and posted to `/sync/ingest` — byte for
 * byte the path a fenix's files take. Nothing on the server knows this one came
 * from a phone, which is what makes training load, the pace curve and the map
 * work without a second implementation of any of them.
 */
@Composable
fun GuidedRunPane(state: GuidedUiState, vm: GuidedWorkoutViewModel, onQuit: () -> Unit) {
    val run by RunRecorder.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val askNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Refused is survivable: the run records, it just says less. */ }

    val askLocation = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        // Coarse alone is not enough here, unlike on the map. A run drawn from
        // cell-tower fixes is a run drawn wrong, and recording one would be
        // worse than refusing to start.
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            RunService.start(context)
        }
    }

    // The plan follows the recording rather than the button, because a
    // permission dialog sits between the two — and a workout that began its
    // warm-up while the user was reading a permission prompt would be a minute
    // into a step nobody had started.
    LaunchedEffect(run.phase, state.begun, state.running) {
        when (run.phase) {
            RunPhase.Recording -> when {
                !state.begun -> vm.begin()
                !state.running -> vm.togglePause()
                else -> Unit
            }
            RunPhase.Paused -> if (state.running) vm.togglePause()
            else -> Unit
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Distance and pace large, everything else as the Health page's small
        // dials — see [RunGauges].
        RunGauges(state, run)

        // The controls straight under the dials rather than at the foot of the
        // page: before the start the session list below can be long, and the
        // button that starts it was a scroll away from the screen it starts.
        when (run.phase) {
            RunPhase.Idle -> PrimaryButton("Start workout", onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    askLocation.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        )
                    )
                }, modifier = Modifier.fillMaxWidth())

            RunPhase.Recording -> Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                NeutralButton("Pause", onClick = { RunService.send(context, RunService.ACTION_PAUSE) }, modifier = Modifier.weight(1f))
                TonalButton("Next step", onClick = vm::skip, modifier = Modifier.weight(1f))
                PrimaryButton("Finish", onClick = { RunService.send(context, RunService.ACTION_STOP) }, modifier = Modifier.weight(1f))
            }

            RunPhase.Paused -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PrimaryButton("Resume", onClick = { RunService.send(context, RunService.ACTION_RESUME) }, modifier = Modifier.weight(1f))
                    TonalButton("Finish", onClick = { RunService.send(context, RunService.ACTION_STOP) }, modifier = Modifier.weight(1f))
                }
                // Only while paused: a running runner's thumb is not one that
                // should be one tap from losing the run, and pausing first is
                // the natural first half of "I'm abandoning this".
                ConfirmingDangerButton("Delete run and quit") {
                    RunService.send(context, RunService.ACTION_DISCARD)
                    vm.discard()
                    onQuit()
                }
            }

            RunPhase.Finished -> FinishedRun(state, vm, run.fixCount)
        }

        // The whole session, but only before it starts. Deciding whether you
        // have time for this — and whether to take a jacket for the twelve
        // minutes of standing around between reps — is a question you ask at
        // the trailhead, not at rep four; and mid-run the same list would push
        // the step you are actually on off the screen. Once it is running,
        // the distance dial's sections and the "Next" line answer "now and
        // next", which is all a moving runner can read anyway.
        if (run.phase == RunPhase.Idle && state.steps.isNotEmpty()) PlanList(state)
    }
}

/**
 * A destructive button that asks before it acts: the first tap turns it into
 * "Are you sure?", the second does it. Left alone, it settles back after a few
 * seconds, so a stray tap in a pocket cannot arm it for a later one.
 *
 * In place rather than a dialog, because a dialog's buttons land somewhere new
 * and this is pressed by someone out of breath; here the confirming tap is on
 * the spot the first one already found.
 */
@Composable
internal fun ConfirmingDangerButton(text: String, onConfirm: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(CONFIRM_WINDOW_MS)
            armed = false
        }
    }
    DangerButton(
        if (armed) "Are you sure? Tap to delete" else text,
        onClick = { if (armed) onConfirm() else armed = true },
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val CONFIRM_WINDOW_MS = 4_000L

/**
 * Every step of the session, in order, before any of it has happened.
 *
 * ## Why the whole thing rather than the first few
 *
 * Because the question this answers is about the shape of the session, and the
 * shape is in the repeats: six by four hundred reads completely differently
 * from three by a mile, and both are "intervals" until you can count them. The
 * runner is already flattened — see [guidedSteps] — so a set of six is six rows
 * with their positions on them, which is the same thing the screen will say
 * one at a time in twenty minutes' time.
 *
 * The description under the title is the plan generator's own prose, which had
 * nowhere to appear on this screen at all: the recording pane shows numbers,
 * and the sentence explaining what the session is *for* was reachable only from
 * the calendar.
 */
@Composable
private fun PlanList(state: GuidedUiState) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Tokens.Radius.xl),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "THE SESSION · ${state.steps.size} ${if (state.steps.size == 1) "step" else "steps"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )

            state.workout?.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.steps.forEachIndexed { index, step ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Numbered, because "step 7 of 24" is what the runner says
                    // once it is going and the two have to be the same seven.
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            listOfNotNull(step.title, step.position).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        step.note?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // The target, right-aligned in its own column so a list of
                    // reps reads down the page as a column of distances rather
                    // than as ragged text.
                    Text(
                        stepTarget(step),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/**
 * What ends a step, in the unit it ends in.
 *
 * Distance beats time when a step has both, matching the runner: a rep
 * prescribed as 400 m with a 90-second cap ends at the line.
 */
private fun stepTarget(step: GuidedStep): String = when {
    step.metres != null -> distance(step.metres)
    step.seconds != null -> countdown(step.seconds)
    else -> step.detail ?: "open"
}

/**
 * What happens to a finished run.
 *
 * Explicit rather than automatic, and it stays on the phone until it lands. A
 * run uploaded silently at a trailhead with one bar either works or is lost,
 * and "lost" is not an acceptable outcome for the hour someone just spent.
 */
@Composable
private fun FinishedRun(state: GuidedUiState, vm: GuidedWorkoutViewModel, fixCount: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        PrimaryButton(if (state.saving) "Saving…" else "Save this run", onClick = vm::saveRun, modifier = Modifier.fillMaxWidth(), enabled = !state.saving && fixCount > 0)

        if (fixCount == 0) {
            Text(
                "No GPS fixes were recorded, so there is nothing to save.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DangerButton("Discard", onClick = vm::clearRun, modifier = Modifier.fillMaxWidth())
    }
}
