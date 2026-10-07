// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.workout

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.tracks.app.run.RunPhase
import com.tracks.app.run.RunUiState
import com.tracks.app.ui.theme.ThemeMode
import com.tracks.app.ui.theme.TracksTheme
import com.tracks.core.api.PlannedWorkout
import com.tracks.core.fit.vdotToPaces
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The guided run's dials and the "back to your workout" bar, rendered on the
 * JVM into build/outputs/roborazzi/ — pictures to look at, not assertions. See
 * TrainingScreenshotTest for why.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, qualifiers = "w400dp-h860dp-xxhdpi")
class RunGaugesScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, mode: ThemeMode, content: @Composable () -> Unit) {
        compose.setContent { TracksTheme(mode = mode) { Surface { content() } } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private val warmup = GuidedStep("Warm-up", seconds = 900, kind = StepKind.Warmup, paceZone = "easy")
    private fun rep(n: Int) = GuidedStep("Interval", "800 m · Interval", metres = 800.0, position = "$n of 5", paceZone = "interval")
    private val rest = GuidedStep("Recovery", "90s", seconds = 90, kind = StepKind.Rest)
    private val cooldown = GuidedStep("Cool-down", seconds = 600, kind = StepKind.Cooldown, paceZone = "easy")
    private val steps = buildList {
        add(warmup)
        for (n in 1..5) { add(rep(n)); if (n < 5) add(rest) }
        add(cooldown)
    }

    /** Third rep, 500 m in, a little quicker than interval pace, on a hilly loop. */
    private val midRun = GuidedUiState(
        loading = false,
        workout = PlannedWorkout(id = 1, scheduledDate = "2026-10-06", sport = "running", title = "5 × 800 m", distanceMeters = 9000.0),
        steps = steps,
        index = 5,
        stepCoveredM = 500.0,
        stepMs = 115_000,
        stepStartsM = listOf(0.0, 2500.0, 3300.0, 3520.0, 4320.0, 4540.0),
        paces = vdotToPaces(48.0),
        running = true,
        begun = true,
    )
    private val run = RunUiState(
        phase = RunPhase.Recording,
        distanceM = 5040.0,
        ascentM = 84.0,
        descentM = 61.0,
        elapsedMs = 1_710_000,
        movingMs = 1_650_000,
        runningMs = 1_650_000,
        speedMps = 1000.0 / 232.0,
    )

    @Test fun run_gauges_interval_dark() = shoot("run_gauges_interval_dark", ThemeMode.Dark) {
        Column(Modifier.padding(20.dp)) { RunGauges(midRun, run) }
    }

    @Test fun run_gauges_interval_light() = shoot("run_gauges_interval_light", ThemeMode.Light) {
        Column(Modifier.padding(20.dp)) { RunGauges(midRun, run.copy(speedMps = 1000.0 / 262.0)) }
    }

    @Test fun active_workout_bar_light() = shoot("active_workout_bar_light", ThemeMode.Light) {
        ActiveWorkoutBar(ActiveWorkout.Session(1, "5 × 800 m", "k"), midRun.copy(workout = null, steps = steps), onOpen = {})
    }
}
